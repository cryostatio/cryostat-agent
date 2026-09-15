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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanServer;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.monitor.GaugeMonitor;
import javax.management.monitor.MonitorNotification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MBeanCache {

    private static final String OBJECT_NAME_PREFIX = "io.cryostat:type=GaugeMonitor,name=";
    private ConcurrentHashMap<String, Object> monitoredAttributes = new ConcurrentHashMap<>();
    private Map<String, GaugeMonitor> gauges = new HashMap<>();
    private final Logger log = LoggerFactory.getLogger(getClass());
    private MBeanServer server = ManagementFactory.getPlatformMBeanServer();

    public MBeanCache() {}

    public Map<String, Object> snapshot() {
        return new HashMap<>(monitoredAttributes);
    }

    public void monitorAttribute(String attr) throws Exception {
        GaugeMonitor monitor = new GaugeMonitor();
        ObjectName objectName = getObjectName(attr);
        monitor.addObservedObject(objectName);
        monitor.setObservedAttribute(attr);
        // Initially fire on any change
        monitor.setThresholds(0, 0);
        NotificationListener listener =
                (notification, handback) -> {
                    if (notification instanceof MonitorNotification) {
                        var value = monitor.getDerivedGauge(objectName);
                        // Update the cache
                        log.trace("Updating cached value {} : {}", attr, value);
                        monitoredAttributes.put(attr, value);
                        // Listen for any change to the existing value
                        monitor.setThresholds(value, value);
                    }
                };
        monitor.addNotificationListener(listener, null, monitor);

        ObjectName monitorName = new ObjectName(OBJECT_NAME_PREFIX + attr + "Monitor");
        log.warn("Registering monitor");
        log.warn(server.toString());
        server.registerMBean(monitor, monitorName);
        gauges.put(attr, monitor);
        monitor.start();
    }

    public void deregister(String attr) throws Exception {
        if (!gauges.containsKey(attr)) {
            log.warn("Attempt to deregister non-monitored attribute: {}", attr);
            return;
        }
        gauges.get(attr).stop();
        server.unregisterMBean(new ObjectName(OBJECT_NAME_PREFIX + attr + "Monitor"));
        monitoredAttributes.remove(attr);
        gauges.remove(attr);
    }

    private ObjectName getObjectName(String attr)
            throws IntrospectionException, InstanceNotFoundException, ReflectionException {
        for (ObjectName i : server.queryNames(null, null)) {
            List<String> attrs =
                    Arrays.asList(server.getMBeanInfo(i).getAttributes()).stream()
                            .map(MBeanAttributeInfo::getName)
                            .collect(Collectors.toList());
            if (attrs.contains(attr)) {
                log.warn(attr);
                return i;
            }
        }
        return null;
    }
}
