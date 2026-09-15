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

public class SmartTriggerReq {

    // TODO: For now the only supported operation is starting a recording,
    // if/when work proceeds on supporting e.g. thread/heap dumps this can
    // be extended to support an operation type.
    // { condition, duration, template }
    private String condition;
    private long duration;
    private String recordingTemplate;
    private long stopDuration;
    private String stopCondition;
    private long executionTarget;

    public SmartTriggerReq(
            String condition,
            long duration,
            String stopCondition,
            long stopDuration,
            long executionTarget,
            String recordingTemplate) {
        this.condition = condition;
        this.duration = duration;
        this.recordingTemplate = recordingTemplate;
        this.stopDuration = stopDuration;
        this.stopCondition = stopCondition;
        this.executionTarget = executionTarget;
    }

    // 0-arg constructor for serializer
    public SmartTriggerReq() {
        this.duration = 0;
        this.condition = "";
        this.recordingTemplate = "";
        this.stopCondition = "";
        this.stopDuration = 0;
        // Default to continuous monitoring
        this.executionTarget = Long.MAX_VALUE;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public String getCondition() {
        return condition;
    }

    public void setCondition(String condition) {
        this.condition = condition;
    }

    public String getRecordingTemplate() {
        return recordingTemplate;
    }

    public void setRecordingTemplate(String recordingTemplate) {
        this.recordingTemplate = recordingTemplate;
    }

    public long getStopDuration() {
        return stopDuration;
    }

    public void setStopDuration(long stopDuration) {
        this.stopDuration = stopDuration;
    }

    public String getStopCondition() {
        return stopCondition;
    }

    public void setStopCondition(String stopCondition) {
        this.stopCondition = stopCondition;
    }

    public long getExecutionTarget() {
        return executionTarget;
    }

    public void setExecutionTarget(long target) {
        this.executionTarget = target;
    }
}
