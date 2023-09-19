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
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SmartTrigger {

    private static final String DURATION_PATTERN =
            "(.*)(targetDuration[<>=]duration\\(\"(\\d+[sS]|[mM])\"\\))";
    private Pattern durationPattern = Pattern.compile(DURATION_PATTERN);

    public enum TriggerState {
        /* Newly Created or Condition not met. */
        NEW,
        /* Condition has been met but target Duration has not */
        WAITING_HIGH,
        /* Condition has not been met and target Duration has not been met */
        WAITING_LOW,
        /* Conditions have been met and recording has started */
        COMPLETE
    };

    private TriggerState state;
    private String expression;
    private String durationConstraint;
    private String triggerCondition;
    private String recordingTemplate;
    private Duration targetDuration;

    /* Keep track of the time the condition was first met for
     * sustained durations
     */
    private Date firstMetTime;

    public SmartTrigger(String expression, String templateName) {
        this.expression = expression;
        this.recordingTemplate = templateName;
        this.state = TriggerState.NEW;
        // this.firstMetTime = new Date(System.currentTimeMillis());
        Matcher m = durationPattern.matcher(expression);
        if (m.matches()) {
            String durationString = m.group(m.groupCount());
            durationConstraint = m.group(2);
            triggerCondition = m.group(1);
            triggerCondition = triggerCondition.substring(0, triggerCondition.length() - 2);
            /* Duration.parse requires timestamps in ISO8601 Duration format */
            targetDuration = Duration.parse("PT" + durationString);
        } else {
            targetDuration = Duration.ZERO;
            durationConstraint = "";
            triggerCondition = expression;
        }
    }

    public String getExpression() {
        return expression;
    }

    public TriggerState getState() {
        return state;
    }

    public void setState(TriggerState targetState) {
        this.state = targetState;
    }

    public String getRecordingTemplateName() {
        return recordingTemplate;
    }

    public Duration getTargetDuration() {
        return targetDuration;
    }

    public void setTimeConditionFirstMet(Date date) {
        this.firstMetTime = new Date(date.getTime());
    }

    public Date getTimeConditionFirstMet() {
        return new Date(firstMetTime.getTime());
    }

    public String getTriggerCondition() {
        return triggerCondition;
    }

    public String getDurationConstraint() {
        return durationConstraint;
    }
}
