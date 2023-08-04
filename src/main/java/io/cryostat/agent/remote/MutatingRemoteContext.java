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
package io.cryostat.agent.remote;

import io.cryostat.agent.ConfigModule;

import io.smallrye.config.SmallRyeConfig;

public abstract class MutatingRemoteContext implements RemoteContext {

    protected final SmallRyeConfig config;

    protected MutatingRemoteContext(SmallRyeConfig config) {
        this.config = config;
    }

    @Override
    public boolean available() {
        return apiWritesEnabled(config);
    }

    public static boolean apiWritesEnabled(SmallRyeConfig config) {
        return config.getValue(ConfigModule.CRYOSTAT_AGENT_API_WRITES_ENABLED, boolean.class);
    }
}
