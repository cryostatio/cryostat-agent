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

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public class DiscoveryNode {

    private String name;
    private String nodeType;
    private Target target;

    DiscoveryNode() {}

    public DiscoveryNode(String name, String nodeType, Target target) {
        this.name = name;
        this.nodeType = nodeType;
        this.target = new Target(target);
    }

    public String getName() {
        return name;
    }

    public String getNodeType() {
        return nodeType;
    }

    public Target getTarget() {
        return new Target(target);
    }

    void setName(String name) {
        this.name = name;
    }

    void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }

    void setTarget(Target target) {
        this.target = target;
    }

    public static class Target {

        private String jvmId;
        private URI connectUrl;
        private String alias;
        private Annotations annotations;

        Target() {}

        Target(Target o) {
            this.jvmId = o.jvmId;
            this.connectUrl = o.connectUrl;
            this.alias = o.alias;
            this.annotations = new Annotations(o.annotations);
        }

        public Target(
                String realm,
                URI connectUrl,
                String alias,
                String instanceId,
                String jvmId,
                long pid,
                String hostname,
                int port,
                String javaMain,
                long startTime) {
            this.jvmId = jvmId;
            this.connectUrl = connectUrl;
            this.alias = alias;
            this.annotations = new Annotations();
            annotations.setPlatform(Map.of("INSTANCE_ID", instanceId));
            annotations.setCryostat(
                    Map.of(
                            "REALM",
                            realm,
                            "PID",
                            pid,
                            "HOST",
                            hostname,
                            "PORT",
                            port,
                            "JAVA_MAIN",
                            javaMain,
                            "START_TIME",
                            startTime));
        }

        public String getJvmId() {
            return this.jvmId;
        }

        public URI getConnectUrl() {
            return connectUrl;
        }

        public String getAlias() {
            return alias;
        }

        public Annotations getAnnotations() {
            return new Annotations(annotations);
        }

        void setJvmId(String jvmId) {
            this.jvmId = jvmId;
        }

        void setConnectUrl(URI connectUrl) {
            this.connectUrl = connectUrl;
        }

        void setAlias(String alias) {
            this.alias = alias;
        }

        void setAnnotations(Annotations annotations) {
            this.annotations = annotations;
        }
    }

    public static class Annotations {

        private Map<String, Object> cryostat;
        private Map<String, Object> platform;

        Annotations() {
            this.cryostat = new HashMap<>();
            this.platform = new HashMap<>();
        }

        Annotations(Annotations o) {
            this.cryostat = new HashMap<>(o.cryostat);
            this.platform = new HashMap<>(o.platform);
        }

        public Map<String, Object> getCryostat() {
            return new HashMap<>(cryostat);
        }

        public Map<String, Object> getPlatform() {
            return new HashMap<>(platform);
        }

        void setCryostat(Map<String, Object> cryostat) {
            this.cryostat = new HashMap<>(cryostat);
        }

        void setPlatform(Map<String, Object> platform) {
            this.platform = new HashMap<>(platform);
        }
    }
}
