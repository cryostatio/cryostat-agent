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

import java.lang.management.ManagementFactory;
import java.util.Set;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class MBeanCacheTest {

    @Mock MBeanServer server;
    MockedStatic<ManagementFactory> factoryMock;
    MBeanCache cache;

    @BeforeEach
    public void setup() {
        factoryMock = Mockito.mockStatic(ManagementFactory.class);
        factoryMock.when(ManagementFactory::getPlatformMBeanServer).thenReturn(server);
        cache = new MBeanCache();
    }

    @Test
    public void testRegistration() throws Exception {
        MBeanInfo info = Mockito.mock(MBeanInfo.class);
        ObjectName name = Mockito.mock(ObjectName.class);
        MBeanAttributeInfo[] attrInfo = {Mockito.mock(MBeanAttributeInfo.class)};
        Mockito.when(server.getMBeanInfo(Mockito.any())).thenReturn(info);
        Mockito.when(server.queryNames(null, null)).thenReturn(Set.of(name));
        Mockito.when(info.getAttributes()).thenReturn(attrInfo);
        Mockito.when(attrInfo[0].getName()).thenReturn("ProcessCpuLoad");
        String in = "ProcessCpuLoad";
        cache.monitorAttribute(in);
        Mockito.verify(server)
                .registerMBean(Mockito.any(Object.class), Mockito.any(ObjectName.class));
    }

    @Test
    public void testDeregistration() throws Exception {
        MBeanInfo info = Mockito.mock(MBeanInfo.class);
        ObjectName name = Mockito.mock(ObjectName.class);
        MBeanAttributeInfo[] attrInfo = {Mockito.mock(MBeanAttributeInfo.class)};
        Mockito.when(server.getMBeanInfo(Mockito.any())).thenReturn(info);
        Mockito.when(server.queryNames(null, null)).thenReturn(Set.of(name));
        Mockito.when(info.getAttributes()).thenReturn(attrInfo);
        Mockito.when(attrInfo[0].getName()).thenReturn("ProcessCpuLoad");
        String in = "ProcessCpuLoad";
        cache.monitorAttribute(in);
        Mockito.verify(server)
                .registerMBean(Mockito.any(Object.class), Mockito.any(ObjectName.class));
        cache.deregister(in);
        Mockito.verify(server).unregisterMBean(Mockito.any(ObjectName.class));
    }
}
