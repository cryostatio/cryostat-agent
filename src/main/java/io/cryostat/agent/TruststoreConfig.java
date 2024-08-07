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

public class TruststoreConfig {
    private final String alias;
    private final String path;
    private final String type;

    public TruststoreConfig(Builder builder) {
        this.alias = builder.alias;
        this.path = builder.path;
        this.type = builder.type;
    }

    public String getAlias() {
        return this.alias;
    }

    public String getPath() {
        return this.path;
    }

    public String getType() {
        return this.type;
    }

    public static class Builder {
        private String alias;
        private String path;
        private String type;

        public Builder withAlias(String alias) {
            this.alias = alias;
            return this;
        }

        public Builder withPath(String path) {
            this.path = path;
            return this;
        }

        public Builder withType(String type) {
            this.type = type;
            return this;
        }

        public TruststoreConfig build() {
            if (this.alias == null || this.path == null || this.type == null) {
                throw new IllegalArgumentException(
                        "The truststore config properties must include a type, alias, and"
                                + " path for each certificate provided");
            }
            return new TruststoreConfig(this);
        }
    }
}
