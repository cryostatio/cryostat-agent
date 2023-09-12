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

public class TriggerOperation {

    /* Desired MBean */
    private String mBeanName;

    /* Desired Metric */
    private String metric;

    /* Operator */
    private String operator;

    /* Value */
    private String value;

    /* Duration */
    private String duration;

    public TriggerOperation(String mBeanName, String metric, String operator, String value, String duration) {
        this.mBeanName = mBeanName;
        this.metric = metric;
        this.operator = operator;
        this.value = value;
        this.duration = duration;
    }

    public TriggerOperation() {}

    public void setMBeanName(String mBeanName) {
        this.mBeanName = mBeanName;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public void setDuration(String duration) {
        this.duration = duration;
    }

    public String getMBeanName() {
        return this.mBeanName;
    }

    public String getMetric() {
        return this.metric;
    }

    public String getOperator() {
        return this.operator;
    }

    public String getValue() {
        return this.value;
    }

    public String setDuration() {
        return this.duration;
    }
}