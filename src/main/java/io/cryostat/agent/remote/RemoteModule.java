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

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoSet;

@Module
public abstract class RemoteModule {

    @Binds
    @IntoSet
    abstract RemoteContext bindInvokeContext(InvokeContext ctx);

    @Binds
    @IntoSet
    abstract RemoteContext bindMBeanContext(MBeanContext ctx);

    @Binds
    @IntoSet
    abstract RemoteContext bindEventTypesContext(EventTypesContext ctx);

    @Binds
    @IntoSet
    abstract RemoteContext bindEventTemplatesContext(EventTemplatesContext ctx);

    @Binds
    @IntoSet
    abstract RemoteContext bindRecordingsContext(RecordingsContext ctx);

    @Binds
    @IntoSet
    abstract RemoteContext bindSmartTriggersContext(SmartTriggersContext ctx);
}
