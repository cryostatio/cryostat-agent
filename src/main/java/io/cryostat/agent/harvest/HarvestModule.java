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
package io.cryostat.agent.harvest;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.inject.Named;
import javax.inject.Singleton;

import io.cryostat.agent.ConfigModule;
import io.cryostat.agent.CryostatClient;
import io.cryostat.agent.FlightRecorderHelper;
import io.cryostat.agent.Registration;
import io.cryostat.agent.harvest.Harvester.RecordingSettings;

import dagger.Module;
import dagger.Provides;

@Module
public abstract class HarvestModule {
    @Provides
    @Singleton
    public static Harvester provideHarvester(
            ScheduledExecutorService workerPool,
            @Named(ConfigModule.CRYOSTAT_AGENT_HARVESTER_PERIOD_MS) long period,
            @Named(ConfigModule.CRYOSTAT_AGENT_HARVESTER_TEMPLATE) String template,
            @Named(ConfigModule.CRYOSTAT_AGENT_HARVESTER_MAX_FILES) int maxFiles,
            @Named(ConfigModule.CRYOSTAT_AGENT_HARVESTER_EXIT_MAX_AGE_MS) long exitMaxAge,
            @Named(ConfigModule.CRYOSTAT_AGENT_HARVESTER_EXIT_MAX_SIZE_B) long exitMaxSize,
            @Named(ConfigModule.CRYOSTAT_AGENT_HARVESTER_MAX_AGE_MS) long maxAge,
            @Named(ConfigModule.CRYOSTAT_AGENT_HARVESTER_MAX_SIZE_B) long maxSize,
            CryostatClient client,
            FlightRecorderHelper flightRecorderHelper,
            Registration registration) {
        RecordingSettings exitSettings = new RecordingSettings();
        exitSettings.maxAge = exitMaxAge;
        exitSettings.maxSize = exitMaxSize;
        RecordingSettings periodicSettings = new RecordingSettings();
        periodicSettings.maxAge = maxAge > 0 ? maxAge : (long) (period * 1.5);
        periodicSettings.maxSize = maxSize;
        return new Harvester(
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r);
                            t.setName("cryostat-agent-harvester");
                            t.setDaemon(true);
                            return t;
                        }),
                workerPool,
                period,
                template,
                maxFiles,
                exitSettings,
                periodicSettings,
                client,
                flightRecorderHelper,
                registration);
    }
}
