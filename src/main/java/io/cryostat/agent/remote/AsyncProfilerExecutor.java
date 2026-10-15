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

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Optional;

import javax.management.MBeanServerInvocationHandler;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared helper for locating and invoking the async-profiler native agent's {@code
 * one.profiler:type=AsyncProfiler} MXBean.
 */
class AsyncProfilerExecutor {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private volatile AsyncProfilerMXBean cachedProxy;

    Optional<AsyncProfilerMXBean> profilerInstance() {
        AsyncProfilerMXBean proxy = cachedProxy;
        if (proxy != null) {
            return Optional.of(proxy);
        }
        try {
            proxy =
                    MBeanServerInvocationHandler.newProxyInstance(
                            ManagementFactory.getPlatformMBeanServer(),
                            new ObjectName(AsyncProfilerMXBean.OBJECT_NAME),
                            AsyncProfilerMXBean.class,
                            false);
            cachedProxy = proxy;
            return Optional.of(proxy);
        } catch (MalformedObjectNameException e) {
            log.error("AsyncProfilerMXBean lookup failure", e);
            return Optional.empty();
        }
    }

    boolean isAvailable() {
        try {
            return profilerInstance()
                    .map(AsyncProfilerMXBean::getVersion)
                    .map(StringUtils::isNotBlank)
                    .orElse(false);
        } catch (Exception e) {
            log.error("async-profiler failure", e);
            return false;
        }
    }

    String execute(String cmd) throws IOException {
        log.debug("async-profiler execute {} ...", cmd);
        String out = profilerInstance().orElseThrow().execute(cmd).strip();
        log.debug("async-profiler execute {}: \"{}\"", cmd, out);
        return out;
    }

    // https://github.com/async-profiler/async-profiler/blob/2a4f329cbae8f849642d4cd74b766ced79ed3557/src/api/one/profiler/AsyncProfilerMXBean.java#L19
    public interface AsyncProfilerMXBean {
        public String OBJECT_NAME = "one.profiler:type=AsyncProfiler";

        String getVersion();

        String execute(String command)
                throws IllegalArgumentException, IllegalStateException, java.io.IOException;
    }
}
