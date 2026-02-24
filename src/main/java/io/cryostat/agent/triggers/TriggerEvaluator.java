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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.cryostat.agent.CryostatClient;
import io.cryostat.agent.FlightRecorderHelper;
import io.cryostat.agent.harvest.Harvester;
import io.cryostat.agent.model.MBeanInfo;
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

    private final ScheduledExecutorService scheduler;
    private final List<String> definitions;
    private final TriggerParser parser;
    private final ScriptHost scriptHost;
    private final FlightRecorderHelper flightRecorderHelper;
    private final Harvester harvester;
    private final long evaluationPeriodMs;
    private final ConcurrentHashMap<SmartTrigger, Script> conditionScriptCache =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SmartTrigger, Script> durationScriptCache =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SmartTrigger> triggers = new ConcurrentHashMap<>();
    private Future<?> task;
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final CryostatClient client;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public TriggerEvaluator(
            ScheduledExecutorService scheduler,
            ScriptHost scriptHost,
            List<String> definitions,
            TriggerParser parser,
            FlightRecorderHelper flightRecorderHelper,
            Harvester harvester,
            long evaluationPeriodMs,
            CryostatClient client) {
        this.scheduler = scheduler;
        this.definitions = Collections.unmodifiableList(definitions);
        this.parser = parser;
        this.scriptHost = scriptHost;
        this.flightRecorderHelper = flightRecorderHelper;
        this.harvester = harvester;
        this.evaluationPeriodMs = evaluationPeriodMs;
        this.client = client;
    }

    public void start(String args) {
        this.stop();
        parser.parseFromFiles().forEach(this::registerTrigger);
        parser.parse(args).forEach(this::registerTrigger);
        parser.parse(String.join(",", definitions)).forEach(this::registerTrigger);
        this.start();
    }

    // start(args) will re-parse the triggers directory, we don't need to do that
    // for requests that come in later through the api since existing triggers
    // are already stored.
    public List<String> append(String definitions) {
        // Sanity check the trigger definitions before we stop/start the evaluation
        if (!parser.isValid(definitions)) {
            log.warn("Invalid Trigger definition {}", definitions);
            throw new IllegalArgumentException();
        }
        ArrayList<String> uuids = new ArrayList<String>();
        this.stop();
        parser.parse(definitions)
                .forEach(
                        (SmartTrigger t) -> {
                            String uuid = registerTrigger(t);
                            if (Objects.isNull(uuid)) {
                                log.warn(
                                        "Duplicate smart trigger definition: {0}",
                                        t.getExpression());
                            } else {
                                uuids.add(uuid);
                            }
                        });
        this.start();
        return uuids;
    }

    public boolean remove(String uuid) {
        // Check if the trigger is registered
        if (!this.triggers.containsKey(uuid)) {
            log.warn("Trigger with UUID {0} not found", uuid);
            return false;
        }

        this.stop();
        this.triggers.remove(uuid);
        this.start();
        return true;
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel(false);
        }
    }

    private String registerTrigger(SmartTrigger t) {
        log.trace("Registering Smart Trigger: {}", t);
        if (!triggers.values().contains(t)) {
            triggers.put(t.getID(), t);
        }
        return t.getID();
    }

    private void start() {
        this.stop();
        if (this.triggers.isEmpty()) {
            return;
        }
        this.task =
                scheduler.scheduleAtFixedRate(
                        this::evaluate, 0, evaluationPeriodMs, TimeUnit.MILLISECONDS);
    }

    private void evaluate() {
        try {
            for (SmartTrigger t : triggers.values()) {
                log.trace("Evaluating {}", t);
                Date currentTime = new Date(System.currentTimeMillis());
                long difference = 0;
                if (t.getTimeConditionFirstMet().getTime() != 0L) {
                    difference = currentTime.getTime() - t.getTimeConditionFirstMet().getTime();
                }
                switch (t.getState()) {
                    case COMPLETE:
                        /* Trigger condition has been met, can remove it */
                        log.trace("Completed {} , removing", t);
                        triggers.values().remove(t);
                        conditionScriptCache.remove(t);
                        durationScriptCache.remove(t);
                        break;
                    case NEW:
                        // Simple Constraint, no duration specified so condition only needs to be
                        // met once
                        if (t.isSimple() && evaluateTriggerConstraint(t, t.getTargetDuration())) {
                            log.trace("Trigger {} satisfied, starting recording...", t);
                            startRecording(t);
                            client.syncSmartTrigger(
                                    new SmartTriggerUpdate(
                                            Collections.emptyList(),
                                            List.of(t.getID()),
                                            Collections.emptyList()));
                        } else if (!t.isSimple()) {
                            if (evaluateTriggerConstraint(t, Duration.ZERO)) {
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
                        if (evaluateTriggerConstraint(t, Duration.ofMillis(difference))) {
                            log.trace("Trigger {} satisfied, completing...", t);
                            startRecording(t);
                            client.syncSmartTrigger(
                                    new SmartTriggerUpdate(
                                            Collections.emptyList(),
                                            List.of(t.getID()),
                                            Collections.emptyList()));
                        } else if (evaluateTriggerConstraint(t, Duration.ZERO)) {
                            log.trace("Trigger {} satisfied, waiting for duration...", t);
                        } else {
                            t.setState(TriggerState.WAITING_LOW);
                            log.trace("Trigger {} not satisfied, going WAITING_LOW...", t);
                        }
                        break;
                    case WAITING_LOW:
                        log.trace("Trigger {} in WAITING_LOW, checking...", t);
                        if (evaluateTriggerConstraint(t, Duration.ZERO)) {
                            log.trace(
                                    "Trigger {} met for the first time! Going to WAITING_HIGH", t);
                            t.setTimeConditionFirstMet(new Date(System.currentTimeMillis()));
                            t.setState(TriggerState.WAITING_HIGH);
                        }
                        break;
                }
            }
        } catch (Exception e) {
            log.error("Unexpected exception during evaluation", e);
        }
    }

    private void startRecording(SmartTrigger t) {
        flightRecorderHelper
                .createRecordingWithPredefinedTemplate(t.getRecordingTemplateName())
                .ifPresent(
                        tr -> {
                            String recordingName =
                                    String.format(
                                            "cryostat-smart-trigger-%d", tr.getRecording().getId());
                            tr.getRecording().setName(recordingName);
                            harvester.handleNewNamedRecording(tr, recordingName);
                            tr.getRecording().start();
                            t.setState(TriggerState.COMPLETE);
                            log.debug(
                                    "Started recording \"{}\" using template \"{}\" due to trigger"
                                            + " \"{}\"",
                                    recordingName,
                                    t.getRecordingTemplateName(),
                                    t.getExpression());
                        });
    }

    private boolean evaluateTriggerConstraint(SmartTrigger trigger, Duration targetDuration) {
        try {
            Map<String, Object> conditionVars = new MBeanInfo().getSimplifiedMetrics();
            log.trace("evaluating mbean map:\n{}", conditionVars);
            Boolean conditionResult =
                    buildConditionScript(trigger, conditionVars)
                            .execute(Boolean.class, conditionVars);

            Map<String, Object> durationVar = Map.of("TargetDuration", targetDuration);
            Boolean durationResult =
                    Duration.ZERO.equals(targetDuration)
                            ? Boolean.TRUE
                            : buildDurationScript(trigger, durationVar)
                                    .execute(Boolean.class, durationVar);

            return Boolean.TRUE.equals(conditionResult) && Boolean.TRUE.equals(durationResult);
        } catch (Exception e) {
            log.error("Failed to create or execute script", e);
            return false;
        }
    }

    private Script buildConditionScript(SmartTrigger trigger, Map<String, Object> scriptVars) {
        return conditionScriptCache.computeIfAbsent(
                trigger, t -> buildScript(t.getTriggerCondition(), scriptVars));
    }

    private Script buildDurationScript(SmartTrigger trigger, Map<String, Object> scriptVars) {
        return durationScriptCache.computeIfAbsent(
                trigger, t -> buildScript(t.getDurationConstraint(), scriptVars));
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
