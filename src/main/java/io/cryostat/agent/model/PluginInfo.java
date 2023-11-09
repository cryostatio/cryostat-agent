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

public class PluginInfo {

    private String id;
    private String token;
    private Map<String, String> env = new HashMap<>();

    public PluginInfo() {}

    public PluginInfo(String id, String token, Map<String, String> env) {
        this.id = id;
        this.token = token;
        this.env.putAll(env);
    }

    public void copyFrom(PluginInfo o) {
        setId(o.getId());
        setToken(o.getToken());
        setEnv(o.getEnv());
    }

    public void clear() {
        copyFrom(new PluginInfo());
    }

    public boolean isInitialized() {
        return id != null && token != null;
    }

    public String getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public Map<String, String> getEnv() {
        return new HashMap<>(env);
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public void setEnv(Map<String, String> env) {
        this.env.clear();
        this.env.putAll(env);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, token, env);
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
        PluginInfo other = (PluginInfo) obj;
        return Objects.equals(id, other.id)
                && Objects.equals(token, other.token)
                && Objects.equals(env, other.env);
    }
}
