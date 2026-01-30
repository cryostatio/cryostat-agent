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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@SuppressFBWarnings("EI_EXPOSE_REP")
public class DiscoveryNode {

    private String name;
    private String nodeType;
    private Map<String, String> labels;
    private List<DiscoveryNode> children;
    private Target target;

    DiscoveryNode() {
        this.labels = new HashMap<>();
        this.children = new ArrayList<>();
    }

    public DiscoveryNode(String name, String nodeType, Target target) {
        this.name = name;
        this.nodeType = nodeType;
        this.labels = new HashMap<>();
        this.children = new ArrayList<>();
        this.target = new Target(target);
    }

    public String getName() {
        return name;
    }

    public String getNodeType() {
        return nodeType;
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    public List<DiscoveryNode> getChildren() {
        return children;
    }

    public Target getTarget() {
        return target;
    }

    void setName(String name) {
        this.name = name;
    }

    void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }

    public void setLabels(Map<String, String> labels) {
        this.labels = labels;
    }

    public void setChildren(List<DiscoveryNode> children) {
        this.children = children;
    }

    void setTarget(Target target) {
        this.target = target;
    }

    @Override
    public String toString() {
        return "DiscoveryNode{"
                + "name='"
                + name
                + '\''
                + ", nodeType='"
                + nodeType
                + '\''
                + ", labels="
                + labels
                + ", children="
                + children
                + ", target="
                + target
                + '}';
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public static class Target {

        private String jvmId;
        private URI connectUrl;
        private String alias;
        private Map<String, String> labels;
        private Annotations annotations;

        Target() {
            this.labels = new HashMap<>();
        }

        Target(Target o) {
            this.jvmId = o.jvmId;
            this.connectUrl = o.connectUrl;
            this.alias = o.alias;
            this.labels = o.labels != null ? new HashMap<>(o.labels) : new HashMap<>();
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
            this.labels = new HashMap<>();
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

        public Map<String, String> getLabels() {
            return labels;
        }

        public Annotations getAnnotations() {
            return annotations;
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

        public void setLabels(Map<String, String> labels) {
            this.labels = labels;
        }

        public void setAnnotations(Annotations annotations) {
            this.annotations = annotations;
        }

        @Override
        public String toString() {
            return "Target{"
                    + "jvmId='"
                    + jvmId
                    + '\''
                    + ", connectUrl="
                    + connectUrl
                    + ", alias='"
                    + alias
                    + '\''
                    + ", labels="
                    + labels
                    + ", annotations="
                    + annotations
                    + '}';
        }
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
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
            return cryostat;
        }

        public Map<String, Object> getPlatform() {
            return platform;
        }

        void setCryostat(Map<String, Object> cryostat) {
            this.cryostat = cryostat;
        }

        public void setPlatform(Map<String, Object> platform) {
            this.platform = platform;
        }

        @Override
        public String toString() {
            return "Annotations{" + "cryostat=" + cryostat + ", platform=" + platform + '}';
        }
    }
}
