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

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import io.cryostat.agent.model.MBeanInfo;
import io.cryostat.agent.triggers.SmartTrigger.TriggerState;

import com.google.api.expr.v1alpha1.Decl;
import com.google.api.expr.v1alpha1.Type;
import com.google.api.expr.v1alpha1.Type.PrimitiveType;
import jdk.management.jfr.ConfigurationInfo;
import jdk.management.jfr.FlightRecorderMXBean;
import org.projectnessie.cel.checker.Decls;
import org.projectnessie.cel.tools.Script;
import org.projectnessie.cel.tools.ScriptHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TriggerEvaluator extends Thread {

    private ConcurrentLinkedQueue<SmartTrigger> triggers;
    private final Logger log = LoggerFactory.getLogger(getClass());

    public TriggerEvaluator(List<SmartTrigger> triggers) {
        this.triggers = new ConcurrentLinkedQueue<>(triggers);
    }

    public void registerTrigger(SmartTrigger t) {
        triggers.add(t);
    }

    public void evaluateTriggers() {}

    @Override
    public void run() {
        while (true) {
            for (SmartTrigger t : triggers) {
                Date currentTime = new Date(System.currentTimeMillis());
                long difference = 0;
                if (t.getTimeConditionFirstMet() != null) {
                    difference = currentTime.getTime() - t.getTimeConditionFirstMet().getTime();
                }
                switch (t.getState()) {
                    case COMPLETE:
                        /* Trigger condition has been met, can remove it */
                        triggers.remove(t);
                        break;
                    case NEW:
                        // Simple Constraint, no duration specified so condition only needs to be
                        // met once
                        if (t.getTargetDuration().equals(Duration.ZERO)
                                && evaluateTriggerConstraint(t, t.getTargetDuration()) == true) {
                            handleRecordingStart(t.getRecordingTemplateName());
                            t.setState(TriggerState.COMPLETE);
                        } else if (!t.getTargetDuration().equals(Duration.ZERO)) {
                            if (evaluateTriggerConstraint(t, Duration.ZERO) == true) {
                                // Condition was met, set the state accordingly
                                t.setState(TriggerState.WAITING_HIGH);
                                t.setTimeConditionFirstMet(new Date(System.currentTimeMillis()));
                            } else {
                                // Condition wasn't met, keep waiting.
                                t.setState(TriggerState.WAITING_LOW);
                            }
                        }
                        break;
                    case WAITING_HIGH:
                        // Condition was met at last check but duration hasn't passed
                        if (handleDuration(difference, t)) {
                            if (evaluateTriggerConstraint(t, Duration.ofMillis(difference))
                                    == true) {
                                t.setState(TriggerState.COMPLETE);
                                handleRecordingStart(t.getRecordingTemplateName());
                            } else {
                                t.setState(TriggerState.WAITING_LOW);
                            }
                        }
                        break;
                    case WAITING_LOW:
                        if (evaluateTriggerConstraint(t, Duration.ofMillis(difference)) == true) {
                            t.setState(TriggerState.WAITING_HIGH);
                            t.setTimeConditionFirstMet(new Date(System.currentTimeMillis()));
                        }
                        break;
                }
            }
        }
    }

    private boolean handleDuration(long difference, SmartTrigger trigger) {
        if (trigger.getDurationConstraint().contains("<=")) {
            return trigger.getTargetDuration().toMillis() <= difference;
        } else if (trigger.getDurationConstraint().contains(">=")) {
            return trigger.getTargetDuration().toMillis() >= difference;
        } else if (trigger.getDurationConstraint().contains(">")) {
            return trigger.getTargetDuration().toMillis() > difference;
        } else if (trigger.getDurationConstraint().contains("<")) {
            return trigger.getTargetDuration().toMillis() < difference;
        } else return trigger.getTargetDuration().toMillis() == difference;
    }

    public boolean evaluateTriggerConstraint(SmartTrigger trigger, Duration target) {
        try {
            Map<String, Object> scriptVars = new MBeanInfo().getSimplifiedMetrics();
            ScriptHost scriptHost = ScriptHost.newBuilder().build();
            Script script =
                    scriptHost
                            .buildScript(trigger.getExpression())
                            .withDeclarations(buildDeclarations(scriptVars))
                            .build();
            scriptVars.put("targetDuration", trigger.getTargetDuration());
            Boolean result = script.execute(Boolean.class, scriptVars);
            return result;
        } catch (Exception e) {
            log.error("Failed to create or execute script", e);
            return false;
        }
    }

    private List<Decl> buildDeclarations(Map<String, Object> scriptVars) {
        ArrayList<Decl> decls = new ArrayList<>();
        for (Map.Entry<String, Object> s : scriptVars.entrySet()) {
            decls.add(Decls.newVar(s.getKey(), parseType(s.getValue())));
        }
        decls.add(Decls.newVar("targetDuration", Decls.Duration));
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

    public void handleRecordingStart(String recordingTemplateName) {
        FlightRecorderMXBean bean = ManagementFactory.getPlatformMXBean(FlightRecorderMXBean.class);
        for (ConfigurationInfo info : bean.getConfigurations()) {
            if (info.getName().equals(recordingTemplateName)) {
                long recordingId = bean.getRecordings().size() + 1;
                bean.startRecording(recordingId);
                bean.setConfiguration(recordingId, info.getContents());
                return;
            }
        }
        log.error("Recording template not found: " + recordingTemplateName);
    }
}
