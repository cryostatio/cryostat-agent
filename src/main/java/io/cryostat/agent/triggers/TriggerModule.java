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
package io.cryostat.agent.triggers;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.inject.Named;
import javax.inject.Singleton;

import io.cryostat.agent.ConfigModule;
import io.cryostat.agent.FlightRecorderHelper;

import dagger.Module;
import dagger.Provides;

@Module
public abstract class TriggerModule {

    private static final String TRIGGER_SCHEDULER = "TRIGGER_SCHEDULER";

    @Provides
    @Singleton
    @Named(TRIGGER_SCHEDULER)
    public static ScheduledExecutorService provideTriggerScheduler() {
        return Executors.newScheduledThreadPool(0);
    }

    @Provides
    @Singleton
    public static TriggerParser provideTriggerParser(FlightRecorderHelper helper) {
        return new TriggerParser(helper);
    }

    @Provides
    @Singleton
    public static TriggerEvaluator provideTriggerEvaluatorFactory(
            @Named(TRIGGER_SCHEDULER) ScheduledExecutorService scheduler,
            TriggerParser parser,
            FlightRecorderHelper helper,
            @Named(ConfigModule.CRYOSTAT_AGENT_SMART_TRIGGER_EVALUATION_PERIOD_MS)
                    long evaluationPeriodMs) {
        return new TriggerEvaluator(scheduler, parser, helper, evaluationPeriodMs);
    }
}
