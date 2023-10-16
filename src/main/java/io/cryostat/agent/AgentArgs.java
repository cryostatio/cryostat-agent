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
package io.cryostat.agent;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;

import io.cryostat.agent.util.StringUtils;

class AgentArgs {
    private final String pid;
    // TODO refactor this and perform parsing earlier in execution
    private final String smartTriggers;

    public AgentArgs(String pid, String smartTriggers) {
        this.pid = pid;
        this.smartTriggers = smartTriggers;
    }

    public String getPid() {
        return pid;
    }

    public String getSmartTriggers() {
        return smartTriggers;
    }

    public static AgentArgs from(String[] args) {
        Queue<String> q = new ArrayDeque<>(Arrays.asList(args));
        // FIXME this should not be specified by ordering but instead by key-value pairing
        String pid = q.poll();
        String smartTriggers = q.poll();
        if (StringUtils.isBlank(pid)) {
            pid = "0";
        }
        return new AgentArgs(pid, smartTriggers);
    }

    @Override
    public String toString() {
        return "AgentArgs [pid=" + pid + ", smartTriggers=" + smartTriggers + "]";
    }
}
