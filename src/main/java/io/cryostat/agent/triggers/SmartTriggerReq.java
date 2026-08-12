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

import org.apache.commons.lang3.StringUtils;

public class SmartTriggerReq {

    // TODO: For now the only supported operation is starting a recording,
    // if/when work proceeds on supporting e.g. thread/heap dumps this can
    // be extended to support an operation type.
    // { condition, durationExpr, template }
    private String condition;
    private String durationExpr;
    private String recordingTemplate;

    public SmartTriggerReq(String condition, String durationExpr, String recordingTemplate) {
        this.condition = condition;
        this.durationExpr = durationExpr;
        this.recordingTemplate = recordingTemplate;
    }

    // 0-arg constructor for serializer
    public SmartTriggerReq() {
        this.durationExpr = "";
        this.condition = "";
        this.recordingTemplate = "";
    }

    public String getDurationExpr() {
        return durationExpr;
    }

    public void setDurationExpr(String durationExpr) {
        this.durationExpr = durationExpr;
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

    // The CEL internal representation doesn't need to be exposed
    // to users, we can construct the expression to evaulate
    // from a simple set of properties.
    public String constructExprFromParams() {
        return this.condition + constructDurationExprFromRequest();
    }

    public String constructDefinitionFromParams() {
        return "[" + constructExprFromParams() + "]~" + recordingTemplate;
    }

    // Blank Duration indicates the trigger should fire immediately
    // when the condition is met.
    private String constructDurationExprFromRequest() {
        return StringUtils.isBlank(this.durationExpr)
                ? ""
                : ";TargetDuration>duration(\"" + this.durationExpr + "\")";
    }
}
