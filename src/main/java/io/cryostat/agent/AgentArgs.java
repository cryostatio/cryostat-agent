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

import java.lang.instrument.Instrumentation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

class AgentArgs {
    private static final String DELIMITER = "!";
    private final Instrumentation instrumentation;
    private final Map<String, String> properties;

    public AgentArgs(Instrumentation instrumentation, Map<String, String> properties) {
        this.instrumentation = instrumentation;
        this.properties = Optional.ofNullable(properties).orElse(Collections.emptyMap());
    }

    public AgentArgs(Map<String, String> properties) {
        this(null, properties);
    }

    public Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public static AgentArgs from(Instrumentation instrumentation, String agentmainArg) {
        Map<String, String> properties = new HashMap<>();
        if (StringUtils.isNotBlank(agentmainArg)) {
            Queue<String> parts = new ArrayDeque<>(Arrays.asList(agentmainArg.split(DELIMITER)));
            String props = parts.poll();
            // Check that the properties are well-formed before attempting to parse
            if (StringUtils.isNotBlank(props) && props.contains("=")) {
                properties =
                        Arrays.asList(props.split(",")).stream()
                                .map(
                                        e -> {
                                            int idx = e.indexOf('=');
                                            return Pair.of(
                                                    e.substring(0, idx), e.substring(idx + 1));
                                        })
                                .collect(
                                        Collectors.toMap(
                                                Pair<String, String>::getKey,
                                                Pair<String, String>::getValue));
            }
        }
        return new AgentArgs(instrumentation, properties);
    }

    public String toAgentMain() {
        List<String> parts = new ArrayList<>();
        if (!properties.isEmpty()) {
            parts.add(
                    String.join(
                            ",",
                            properties.entrySet().stream()
                                    .map(e -> String.format("%s=%s", e.getKey(), e.getValue()))
                                    .collect(Collectors.toList())));
        }
        return String.join(DELIMITER, parts);
    }
}
