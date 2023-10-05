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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.projectnessie.cel.tools.ScriptHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TriggerEvaluator {

    private final ScheduledExecutorService scheduler;
    private final List<String> definitions;
    private final TriggerParser parser;
    private final FlightRecorderHelper flightRecorderHelper;
    private final Harvester harvester;
    private final long evaluationPeriodMs;
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
                                    "Started recording \"{}\" using template \"{}\"",
                                    recordingName,
                                    t.getRecordingTemplateName());
                        });
    }

    private boolean evaluateTriggerConstraint(SmartTrigger trigger, Duration targetDuration) {
        try {
            Map<String, Object> scriptVars = new HashMap<>(new MBeanInfo().getSimplifiedMetrics());
            ScriptHost scriptHost = ScriptHost.newBuilder().build();
            Script script =
                    scriptHost
                            .buildScript(
                                    Duration.ZERO.equals(targetDuration)
                                            ? trigger.getTriggerCondition()
                                            : trigger.getExpression())
                            .withDeclarations(buildDeclarations(scriptVars))
                            .build();
            scriptVars.put("TargetDuration", targetDuration);
            log.trace("evaluating mbean map:\n{}", scriptVars);
            Boolean result = script.execute(Boolean.class, scriptVars);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Failed to create or execute script", e);
            return false;
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
        decls.add(Decls.newVar("TargetDuration", Decls.Duration));
        return decls;
    }

    private Type parseType(Object obj) {
        if (obj.getClass().equals(String.class)) return Decls.String;
        else if (obj.getClass().equals(Double.class)) return Decls.Double;
        else if (obj.getClass().equals(Integer.class)) return Decls.Int;
        else if (obj.getClass().equals(Boolean.class)) return Decls.Bool;
        else if (obj.getClass().equals(Long.class))
            return Decls.newPrimitiveType(PrimitiveType.INT64);
        else
            // Default to String so we can still do some comparison
            return Decls.String;
    }
}
