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

import java.util.Objects;

public class TruststoreConfig {
    private final String alias;
    private final String path;
    private final String type;

    private TruststoreConfig(Builder builder) {
        this.alias =
                Objects.requireNonNull(
                        builder.alias,
                        "Truststore config properties must include a certificate alias");
        this.path =
                Objects.requireNonNull(
                        builder.path,
                        "Truststore config properties must include a certificate path");
        this.type =
                Objects.requireNonNull(
                        builder.type,
                        "Truststore config properties must include a certificate type");
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
            return new TruststoreConfig(this);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(alias, path, type);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        TruststoreConfig other = (TruststoreConfig) obj;
        return Objects.equals(alias, other.alias)
                && Objects.equals(path, other.path)
                && Objects.equals(type, other.type);
    }
}
