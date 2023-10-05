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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.cryostat.agent.FlightRecorderHelper;
import io.cryostat.agent.harvest.Harvester;
import io.cryostat.agent.model.MBeanInfo;
import io.cryostat.agent.triggers.SmartTrigger.TriggerState;

import com.google.api.expr.v1alpha1.Decl;
import com.google.api.expr.v1alpha1.Type;
import com.google.api.expr.v1alpha1.Type.PrimitiveType;
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
    private final ConcurrentLinkedQueue<SmartTrigger> triggers = new ConcurrentLinkedQueue<>();
    private Future<?> task;
    private final Logger log = LoggerFactory.getLogger(getClass());

    public TriggerEvaluator(
            ScheduledExecutorService scheduler,
            List<String> definitions,
            TriggerParser parser,
            FlightRecorderHelper flightRecorderHelper,
            Harvester harvester,
            long evaluationPeriodMs) {
        this.scheduler = scheduler;
        this.definitions = Collections.unmodifiableList(definitions);
        this.parser = parser;
        this.scriptHost = ScriptHost.newBuilder().build();
        this.flightRecorderHelper = flightRecorderHelper;
        this.harvester = harvester;
        this.evaluationPeriodMs = evaluationPeriodMs;
    }

    public void start(String[] args) {
        this.stop();
        parser.parse(args).forEach(this::registerTrigger);
        parser.parse(definitions.toArray(new String[0])).forEach(this::registerTrigger);
        this.start();
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel(false);
        }
    }

    private void registerTrigger(SmartTrigger t) {
        log.info("Registering Smart Trigger: {}", t);
        if (!triggers.contains(t)) {
            triggers.add(t);
        }
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
            for (SmartTrigger t : triggers) {
                log.trace("Evaluating {}", t);
                Date currentTime = new Date(System.currentTimeMillis());
                long difference = 0;
                if (t.getTimeConditionFirstMet() != null) {
                    difference = currentTime.getTime() - t.getTimeConditionFirstMet().getTime();
                }
                switch (t.getState()) {
                    case COMPLETE:
                        /* Trigger condition has been met, can remove it */
                        log.trace("Completed {} , removing", t);
                        triggers.remove(t);
                        conditionScriptCache.remove(t);
                        durationScriptCache.remove(t);
                        break;
                    case NEW:
                        // Simple Constraint, no duration specified so condition only needs to be
                        // met once
                        if (t.isSimple() && evaluateTriggerConstraint(t, t.getTargetDuration())) {
                            log.trace("Trigger {} satisfied, starting recording...", t);
                            startRecording(t);
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
                .createRecording(t.getRecordingTemplateName())
                .ifPresent(
                        tr -> {
                            String recordingName =
                                    String.format(
                                            "cryostat-smart-trigger-%d", tr.getRecording().getId());
                            tr.getRecording().setName(recordingName);
                            harvester.handleNewRecording(tr);
                            tr.getRecording().start();
                            t.setState(TriggerState.COMPLETE);
                            log.info(
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
            Boolean durationResult;
            Map<String, Object> durationVar = Map.of("TargetDuration", targetDuration);
            if (Duration.ZERO.equals(targetDuration)) {
                durationResult = Boolean.TRUE;
            } else {
                durationResult =
                        buildDurationScript(trigger, durationVar)
                                .execute(Boolean.class, durationVar);
            }
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
}
