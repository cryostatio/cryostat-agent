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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.lang.management.ManagementFactory;

import javax.management.MBeanServerInvocationHandler;
import javax.management.ObjectName;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AsyncProfilerExecutorTest {

    @Test
    void profilerInstanceCachesProxyAcrossCalls() throws Exception {
        AsyncProfilerExecutor.AsyncProfilerMXBean proxy =
                mock(AsyncProfilerExecutor.AsyncProfilerMXBean.class);
        try (MockedStatic<MBeanServerInvocationHandler> handler =
                mockStatic(MBeanServerInvocationHandler.class)) {
            handler.when(
                            () ->
                                    MBeanServerInvocationHandler.newProxyInstance(
                                            eq(ManagementFactory.getPlatformMBeanServer()),
                                            any(ObjectName.class),
                                            eq(AsyncProfilerExecutor.AsyncProfilerMXBean.class),
                                            eq(false)))
                    .thenReturn(proxy);

            AsyncProfilerExecutor executor = new AsyncProfilerExecutor();

            assertSame(proxy, executor.profilerInstance().get());
            assertSame(proxy, executor.profilerInstance().get());
            assertSame(proxy, executor.profilerInstance().get());

            handler.verify(
                    () ->
                            MBeanServerInvocationHandler.newProxyInstance(
                                    eq(ManagementFactory.getPlatformMBeanServer()),
                                    any(ObjectName.class),
                                    eq(AsyncProfilerExecutor.AsyncProfilerMXBean.class),
                                    eq(false)),
                    times(1));
        }
    }

    @Test
    void isAvailableAndExecuteReuseCachedProxy() throws Exception {
        AsyncProfilerExecutor.AsyncProfilerMXBean proxy =
                mock(AsyncProfilerExecutor.AsyncProfilerMXBean.class);
        when(proxy.getVersion()).thenReturn("1.0");
        when(proxy.execute("status")).thenReturn("profiler is not active");

        try (MockedStatic<MBeanServerInvocationHandler> handler =
                mockStatic(MBeanServerInvocationHandler.class)) {
            handler.when(
                            () ->
                                    MBeanServerInvocationHandler.newProxyInstance(
                                            eq(ManagementFactory.getPlatformMBeanServer()),
                                            any(ObjectName.class),
                                            eq(AsyncProfilerExecutor.AsyncProfilerMXBean.class),
                                            eq(false)))
                    .thenReturn(proxy);

            AsyncProfilerExecutor executor = new AsyncProfilerExecutor();

            assertTrue(executor.isAvailable());
            assertEquals("profiler is not active", executor.execute("status"));
            assertTrue(executor.isAvailable());

            handler.verify(
                    () ->
                            MBeanServerInvocationHandler.newProxyInstance(
                                    eq(ManagementFactory.getPlatformMBeanServer()),
                                    any(ObjectName.class),
                                    eq(AsyncProfilerExecutor.AsyncProfilerMXBean.class),
                                    eq(false)),
                    times(1));
        }
    }
}
