/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat.agent.triggers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.cryostat.agent.CryostatClient;
import io.cryostat.agent.FlightRecorderHelper;
import io.cryostat.agent.FlightRecorderHelper.TemplatedRecording;
import io.cryostat.agent.harvest.Harvester;
import io.cryostat.libcryostat.triggers.SmartTrigger;
import io.cryostat.libcryostat.triggers.SmartTrigger.TriggerState;

import com.google.api.expr.v1alpha1.Decl;
import com.google.api.expr.v1alpha1.Type;
import com.google.api.expr.v1alpha1.Type.PrimitiveType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.projectnessie.cel.checker.Decls;
import org.projectnessie.cel.tools.Script;
import org.projectnessie.cel.tools.ScriptCreateException;
import org.projectnessie.cel.tools.ScriptHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TriggerEvaluator {

    // Keys for extra state to customize how triggers execute
    private static final String ACTIVATION_KEY = "triggerActivationCount";
    private static final String LAST_ACTIVATION_KEY = "timeLastActivated";
    private static final String TIME_LAST_ACTIVATED_KEY = "durationSinceLastActivation";
    private final ScheduledExecutorService scheduler;
    private final String definitions;
    private final TriggerParser parser;
    private final ScriptHost scriptHost;
    private final FlightRecorderHelper flightRecorderHelper;
    private final Harvester harvester;
    private final long evaluationPeriodMs;
    private List<String> orphanAttributes = new ArrayList<>();
    private final ConcurrentHashMap<SmartTrigger, Script> conditionScriptCache =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SmartTrigger, Script> stopConditionCache =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SmartTrigger, Long> activationCounts =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SmartTrigger> triggers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TemplatedRecording> recordings =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SmartTrigger, Long> lastActivations = new ConcurrentHashMap<>();
    private Future<?> task;
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final CryostatClient client;
    private final MBeanCache cache;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public TriggerEvaluator(
            ScheduledExecutorService scheduler,
            ScriptHost scriptHost,
            String definitions,
            TriggerParser parser,
            FlightRecorderHelper flightRecorderHelper,
            Harvester harvester,
            long evaluationPeriodMs,
            MBeanCache cache,
            CryostatClient client) {
        this.scheduler = scheduler;
        this.definitions = definitions;
        this.parser = parser;
        this.scriptHost = scriptHost;
        this.flightRecorderHelper = flightRecorderHelper;
        this.harvester = harvester;
        this.evaluationPeriodMs = evaluationPeriodMs;
        this.client = client;
        this.cache = cache;
    }

    public void start() {
        this.stop();
        parser.parseFromFiles().forEach(this::registerTrigger);
        parser.parseFromJson(definitions).forEach(this::registerTrigger);
        this.refresh();
    }

    // start(args) will re-parse the triggers directory, we don't need to do that
    // for requests that come in later through the api since existing triggers
    // are already stored.
    public List<String> append(SmartTriggerReq[] reqs) {
        for (SmartTriggerReq req : reqs) {
            if (!parser.isValid(req)) {
                log.warn("Invalid Trigger definition");
                return Collections.emptyList();
            }
        }
        this.stop();
        var returnVal = new ArrayList<String>();
        for (SmartTriggerReq req : reqs) {
            var trigger = parser.parse(req);
            String uuid = registerTrigger(trigger);
            if (Objects.isNull(uuid)) {
                log.warn(
                        "Duplicate smart trigger definition: {} {} {}",
                        trigger.getTriggerCondition(),
                        trigger.getTargetDuration().toMillis(),
                        trigger.getRecordingTemplateName());
            }
            returnVal.add(uuid);
        }
        this.refresh();
        return returnVal;
    }

    public boolean remove(String uuid) {
        // Check if the trigger is registered
        if (!this.triggers.containsKey(uuid)) {
            log.warn("Trigger with UUID {0} not found", uuid);
            return false;
        }

        this.stop();
        try {
            cleanupListeners(this.triggers.get(uuid));
        } catch (Exception e) {
            // Exception gets propagated if the listeners remained
            // registered. Retain the trigger and restart evaluation
            log.warn("Failed to cleanup listeners for trigger {}, retaining trigger.", uuid);
            this.refresh();
            return false;
        }
        cleanupState(triggers.get(uuid));
        this.refresh();
        return true;
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel(false);
        }
    }

    private String registerTrigger(SmartTrigger t) {
        var registeredListeners = new ArrayList<String>();
        var parsedAttributes = new ArrayList<String>();
        try {
            parsedAttributes.addAll(parser.parseAttributesFromCondition(t.getTriggerCondition()));
            parsedAttributes.addAll(parser.parseAttributesFromCondition(t.getStopCondition()));
            if (parsedAttributes.isEmpty()) {
                log.warn(
                        "No valid attributes found in expression {}, rejecting trigger",
                        t.getTriggerCondition());
                return null;
            }
            for (String s : parsedAttributes) {
                cache.monitorAttribute(s);
                registeredListeners.add(s);
            }
        } catch (Exception e) {
            log.warn(
                    "Invalid Attribute referenced in Trigger condition {}, skipping trigger",
                    t.getTriggerCondition());
            for (String s : registeredListeners) {
                try {
                    cache.deregister(s);
                } catch (Exception e2) {
                    log.warn("Failed to de-register attribute: {}", s);
                    orphanAttributes.add(s);
                }
            }
            return null;
        }
        if (!triggers.values().contains(t)) {
            triggers.put(t.getID(), t);
        }
        return t.getID();
    }

    private synchronized void refresh() {
        this.stop();
        if (this.triggers.isEmpty()) {
            return;
        }
        this.task =
                scheduler.scheduleAtFixedRate(
                        this::evaluate, 0, evaluationPeriodMs, TimeUnit.MILLISECONDS);
    }

    void evaluate() {
        try {
            for (SmartTrigger t : triggers.values()) {
                log.trace("Evaluating {}", t);
                log.trace("Trigger state {} ", t.getState());
                Date currentTime = new Date(System.currentTimeMillis());
                long difference = 0;
                if (t.getTimeConditionFirstMet().getTime() != 0L) {
                    difference = currentTime.getTime() - t.getTimeConditionFirstMet().getTime();
                }
                switch (t.getState()) {
                    case COMPLETE:
                        /* Trigger condition has been met, can remove it */
                        log.trace("Completed {} , removing", t);
                        try { // Exception is propagated if the mbean remained registered
                            cleanupListeners(t);
                        } catch (Exception e) {
                            log.warn("Failed to clean up listeners, retaining trigger");
                            break;
                        }
                        cleanupState(t);
                        break;
                    case NEW:
                        // Simple Constraint, no duration specified so condition only needs to be
                        // met once
                        if (t.isSimple() && evaluateTrigger(t, t.getTargetDuration(), false)) {
                            log.trace("Trigger {} satisfied, starting recording...", t);
                            startRecording(t);
                            client.syncSmartTrigger(
                                    new SmartTriggerUpdate(
                                            Collections.emptyList(),
                                            List.of(t.getID()),
                                            Collections.emptyList()));
                        } else if (!t.isSimple()) {
                            if (evaluateTrigger(t, Duration.ZERO, false)) {
                                // Condition was met, set the state accordingly
                                log.trace("Trigger {} satisfied, watching...", t);
                                t.setState(TriggerState.WAITING_HIGH);
                                t.setTimeConditionFirstMet(new Date(System.currentTimeMillis()));
                            } else {
                                // Condition wasn't met, keep waiting.
                                log.trace("Trigger {} not yet satisfied...", t);
                                t.setState(TriggerState.WAITING_LOW);
                            }
                        }
                        break;
                    case WAITING_HIGH:
                        // Condition was met at last check but duration hasn't passed
                        if (evaluateTrigger(t, Duration.ofMillis(difference), false)) {
                            log.trace("Trigger {} satisfied, completing...", t);
                            startRecording(t);
                            client.syncSmartTrigger(
                                    new SmartTriggerUpdate(
                                            Collections.emptyList(),
                                            List.of(t.getID()),
                                            Collections.emptyList()));
                        } else if (evaluateTrigger(t, Duration.ZERO, false)) {
                            log.trace("Trigger {} satisfied, waiting for duration...", t);
                        } else {
                            t.setState(TriggerState.WAITING_LOW);
                            log.trace("Trigger {} not satisfied, going WAITING_LOW...", t);
                        }
                        break;
                    case WAITING_LOW:
                        log.trace("Trigger {} in WAITING_LOW, checking...", t);
                        if (evaluateTrigger(t, Duration.ZERO, false)) {
                            log.trace(
                                    "Trigger {} met for the first time! Going to WAITING_HIGH", t);
                            t.setTimeConditionFirstMet(new Date(System.currentTimeMillis()));
                            t.setState(TriggerState.WAITING_HIGH);
                        }
                        break;
                    case RECORDING_ACTIVE:
                        log.trace("Trigger {} in RECORDING_ACTIVE, monitoring conditions", t);
                        // If no stopping condition was provided, no need to take any action.
                        if (t.getStopCondition().isBlank()) {
                            break;
                        }
                        if (evaluateTrigger(t, Duration.ZERO, true)) {
                            log.trace(
                                    "Trigger {} met stopping condition, transitioning to"
                                            + " RECORDING_STOPPING",
                                    t);
                            t.setTimeStopConditionFirstMet(new Date(System.currentTimeMillis()));
                            t.setState(TriggerState.RECORDING_STOPPING);
                        }
                        break;
                    case RECORDING_STOPPING:
                        log.trace("Trigger {} in RECORDING_STOPPING, checking...", t);
                        // Condition was met at last check but duration hasn't passed
                        long stopDifference =
                                currentTime.getTime() - t.getTimeStopConditionFirstMet().getTime();
                        if (evaluateTrigger(t, Duration.ofMillis(stopDifference), true)) {
                            log.trace("Trigger {} satisfied, completing...", t);
                            stopRecording(t);
                            client.syncSmartTrigger(
                                    new SmartTriggerUpdate(
                                            Collections.emptyList(),
                                            List.of(t.getID()),
                                            Collections.emptyList()));
                        } else if (evaluateTrigger(t, Duration.ZERO, true)) {
                            log.trace("Trigger {} satisfied, waiting for duration...", t);
                        } else {
                            t.setState(TriggerState.RECORDING_ACTIVE);
                            log.trace("Trigger {} not satisfied, going RECORDING_STOPPING...", t);
                        }
                        break;
                }
            }
        } catch (Exception e) {
            log.error("Unexpected exception during evaluation", e);
        }
    }

    private void stopRecording(SmartTrigger t) {
        TemplatedRecording recording = recordings.get(t.getID());
        if (Objects.isNull(recording)) {
            log.error("Trigger {} has no associated recording.", t);
            throw new IllegalArgumentException();
        }
        recording.getRecording().stop();
        log.trace(
                "Recording {} stopped, delegating to harvester",
                recording.getRecording().getName());
        harvester.recordingStateChanged(recording.getRecording());
        log.trace("Activation Count: {}", activationCounts.getOrDefault(t, 0L));
        log.trace("Invocation Target: {}", t.getInvocationCountTarget());
        if (activationCounts.getOrDefault(t, 0L) >= t.getInvocationCountTarget()) {
            log.trace("Trigger exceeded invocation target, completing");
            t.setState(TriggerState.COMPLETE);
        } else {
            // Trigger can keep firing, reset the state
            t.setState(TriggerState.NEW);
        }
    }

    private void startRecording(SmartTrigger t) {
        Optional<TemplatedRecording> rec =
                flightRecorderHelper.createRecordingWithPredefinedTemplate(
                        t.getRecordingTemplateName());
        if (rec.isEmpty()) {
            log.warn("Failed to create recording, leaving trigger state unchanged");
            return;
        }
        TemplatedRecording tr = rec.get();
        String recordingName =
                String.format("cryostat-smart-trigger-%d", tr.getRecording().getId());
        tr.getRecording().setName(recordingName);
        harvester.handleNewNamedRecording(tr, recordingName);
        tr.getRecording().start();
        recordings.put(t.getID(), tr);
        t.setState(TriggerState.RECORDING_ACTIVE);
        activationCounts.merge(t, 1l, Long::sum);
        lastActivations.put(t, System.currentTimeMillis());
        log.debug(
                "Started recording \"{}\" using template \"{}\" due to trigger" + " \"{}\"",
                recordingName,
                t.getRecordingTemplateName(),
                t.getTriggerCondition());
    }

    private boolean evaluateTrigger(SmartTrigger trigger, Duration targetDuration, boolean stop) {
        try {
            Map<String, Object> conditionVars = cache.snapshot();
            var lastActivation = lastActivations.getOrDefault(trigger, 0l);
            // Inject extra state to allow control over how triggers activate
            conditionVars.put(ACTIVATION_KEY, activationCounts.getOrDefault(trigger, 0l));
            conditionVars.put(LAST_ACTIVATION_KEY, lastActivation);
            conditionVars.put(TIME_LAST_ACTIVATED_KEY, System.currentTimeMillis() - lastActivation);
            log.trace("evaluating mbean map:\n{}", conditionVars);

            Boolean conditionResult =
                    stop
                            ? stopConditionCache
                                    .computeIfAbsent(
                                            trigger,
                                            t -> buildScript(t.getStopCondition(), conditionVars))
                                    .execute(Boolean.class, conditionVars)
                            : buildConditionScript(trigger, conditionVars)
                                    .execute(Boolean.class, conditionVars);

            var durationResult = false;
            if (targetDuration.equals(Duration.ZERO)) {
                durationResult = true;
            }
            if (stop) {
                if (targetDuration.toMillis() >= trigger.getStopDuration()) {
                    durationResult = true;
                }
            } else {
                if (targetDuration.toMillis() >= trigger.getTargetDuration().toMillis()) {
                    durationResult = true;
                }
            }
            boolean satisfied = conditionResult && durationResult;
            return satisfied;
        } catch (Exception e) {
            log.error("Failed to create or execute script", e);
            return false;
        }
    }

    private Script buildConditionScript(SmartTrigger trigger, Map<String, Object> scriptVars) {
        return conditionScriptCache.computeIfAbsent(
                trigger, t -> buildScript(t.getTriggerCondition(), scriptVars));
    }

    private Script buildScript(String script, Map<String, Object> scriptVars) {
        try {
            return scriptHost
                    .buildScript(script)
                    .withDeclarations(buildDeclarations(scriptVars))
                    .build();
        } catch (ScriptCreateException sce) {
            log.error("Failed to create script", sce);
            throw new RuntimeException(sce);
        }
    }

    private void cleanupListeners(SmartTrigger t) throws Exception {
        ArrayList<String> conditions = new ArrayList<>();
        conditions.addAll(parser.parseAttributesFromCondition(t.getTriggerCondition()));
        conditions.addAll(parser.parseAttributesFromCondition(t.getStopCondition()));
        for (String c : conditions) {
            cache.deregister(c);
        }
        // Attempt removal of any orphaned attributes that previously failed
        try {
            for (String c : orphanAttributes) {
                cache.deregister(c);
                orphanAttributes.remove(c);
            }
        } catch (Exception e) {
            // If we failed again it will still be in the list for the next retry.
            log.warn("Failed to remove orphaned listener, retrying later");
        }
    }

    private List<Decl> buildDeclarations(Map<String, Object> scriptVars) {
        ArrayList<Decl> decls = new ArrayList<>();
        for (Map.Entry<String, Object> s : scriptVars.entrySet()) {
            String key = s.getKey();
            Type parseType = parseType(s.getValue());
            log.trace("Declaring script var {} [{}]", key, parseType);
            decls.add(Decls.newVar(key, parseType));
        }
        return decls;
    }

    private Type parseType(Object obj) {
        if (obj.getClass().equals(String.class)) return Decls.String;
        else if (obj.getClass().equals(Boolean.class)) return Decls.Bool;
        else if (obj.getClass().equals(Integer.class)) return Decls.Int;
        else if (obj.getClass().equals(Long.class))
            return Decls.newPrimitiveType(PrimitiveType.INT64);
        else if (obj.getClass().equals(Double.class)) return Decls.Double;
        else if (obj.getClass().equals(Duration.class)) return Decls.Duration;
        else
            // Default to String so we can still do some comparison
            return Decls.String;
    }

    public List<SmartTrigger> getDefinitions() {
        return new ArrayList<SmartTrigger>(triggers.values());
    }

    private void cleanupState(SmartTrigger t) {
        triggers.values().remove(t);
        conditionScriptCache.remove(t);
        stopConditionCache.remove(t);
        activationCounts.remove(t);
        lastActivations.remove(t);
        recordings.remove(t.getID());
    }

    public static class SmartTriggerUpdate {
        List<String> addedTriggers;
        List<String> removedTriggers;
        List<String> updatedTriggers;

        public SmartTriggerUpdate(List<String> added, List<String> removed, List<String> updated) {
            this.addedTriggers = new ArrayList<String>(added);
            this.removedTriggers = new ArrayList<String>(removed);
            this.updatedTriggers = new ArrayList<String>(updated);
        }

        public List<String> getAddedTriggers() {
            return Collections.unmodifiableList(this.addedTriggers);
        }

        public List<String> getRemovedTriggers() {
            return Collections.unmodifiableList(this.removedTriggers);
        }

        public List<String> getUpdatedTriggers() {
            return Collections.unmodifiableList(this.updatedTriggers);
        }
    }
}
