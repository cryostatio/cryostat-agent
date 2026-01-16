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
package io.cryostat.agent.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents metadata (labels and annotations) that should be applied to the Agent's DiscoveryNode.
 * This metadata is typically mounted from an external source (e.g., Cryostat Operator) and merged
 * with the Agent's self-generated metadata.
 */
public class DiscoveryMetadata {

    private final Map<String, String> labels;
    private final Map<String, String> annotations;

    @JsonCreator
    public DiscoveryMetadata(
            @JsonProperty("labels") Map<String, String> labels,
            @JsonProperty("annotations") Map<String, String> annotations) {
        this.labels = labels != null ? new HashMap<>(labels) : new HashMap<>();
        this.annotations = annotations != null ? new HashMap<>(annotations) : new HashMap<>();
    }

    public DiscoveryMetadata() {
        this(new HashMap<>(), new HashMap<>());
    }

    public Map<String, String> getLabels() {
        return new HashMap<>(labels);
    }

    public Map<String, String> getAnnotations() {
        return new HashMap<>(annotations);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DiscoveryMetadata that = (DiscoveryMetadata) o;
        return Objects.equals(labels, that.labels) && Objects.equals(annotations, that.annotations);
    }

    @Override
    public int hashCode() {
        return Objects.hash(labels, annotations);
    }

    @Override
    public String toString() {
        return "DiscoveryMetadata{" + "labels=" + labels + ", annotations=" + annotations + '}';
    }
}
