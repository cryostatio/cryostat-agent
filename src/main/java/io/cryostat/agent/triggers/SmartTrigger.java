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
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SmartTrigger {

    private static final String DURATION_PATTERN =
            "(.*)(targetDuration[<>=]duration\\(\"(\\d+[sS]|[mM])\"\\))";
    private static final Pattern durationPattern = Pattern.compile(DURATION_PATTERN);

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

    private final String expression;
    private final String durationConstraint;
    private final String triggerCondition;
    private final String recordingTemplate;
    private final Duration targetDuration;
    /* Keep track of the time the condition was first met for
     * sustained durations
     */
    private volatile Date firstMetTime;
    private volatile TriggerState state;

    public SmartTrigger(String expression, String templateName) {
        this.expression = expression;
        this.recordingTemplate = templateName;
        this.state = TriggerState.NEW;
        // this.firstMetTime = new Date(System.currentTimeMillis());
        Matcher m = durationPattern.matcher(expression);
        if (m.matches()) {
            String durationString = m.group(m.groupCount());
            durationConstraint = m.group(2);
            String rawCondition = m.group(1);
            triggerCondition = rawCondition.substring(0, rawCondition.length() - 2);
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

    @Override
    public int hashCode() {
        return Objects.hash(
                expression,
                durationConstraint,
                triggerCondition,
                recordingTemplate,
                targetDuration);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        SmartTrigger other = (SmartTrigger) obj;
        return Objects.equals(expression, other.expression)
                && Objects.equals(durationConstraint, other.durationConstraint)
                && Objects.equals(triggerCondition, other.triggerCondition)
                && Objects.equals(recordingTemplate, other.recordingTemplate)
                && Objects.equals(targetDuration, other.targetDuration);
    }

    @Override
    public String toString() {
        return "SmartTrigger [durationConstraint="
                + durationConstraint
                + ", expression="
                + expression
                + ", recordingTemplate="
                + recordingTemplate
                + ", targetDuration="
                + targetDuration
                + ", triggerCondition="
                + triggerCondition
                + "]";
    }
}
