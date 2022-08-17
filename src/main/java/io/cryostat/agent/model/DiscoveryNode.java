/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.agent.model;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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

        private URI connectUrl;
        private String alias;
        private Annotations annotations;

        Target() {}

        Target(Target o) {
            this.connectUrl = o.connectUrl;
            this.alias = o.alias;
            this.annotations = new Annotations(o.annotations);
        }

        public Target(
                String realm,
                URI connectUrl,
                String alias,
                UUID instanceId,
                long pid,
                String hostname,
                int port,
                String javaMain,
                long startTime) {
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

        public URI getConnectUrl() {
            return connectUrl;
        }

        public String getAlias() {
            return alias;
        }

        public Annotations getAnnotations() {
            return new Annotations(annotations);
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
