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
import javax.management.MalformedObjectNameException;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.monitor.GaugeMonitor;
import javax.management.monitor.MonitorNotification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MBeanCache {

    private static final String OBJECT_NAME_PREFIX = "io.cryostat:type=GaugeMonitor,name=";
    private static final String THRESHOLD_HIGH_VALUE_EXCEEDED = "jmx.monitor.gauge.high";
    private static final String THRESHOLD_LOW_VALUE_EXCEEDED = "jmx.monitor.gauge.low";
    // Read/Written by JMX Threads
    private ConcurrentHashMap<String, Object> monitoredAttributes = new ConcurrentHashMap<>();
    // Read/Written by evaluation thread and HTTP thread
    // Read/Writes protected by registrationLock
    private HashMap<String, GaugeMonitor> gauges = new HashMap<>();
    // Multiple triggers can monitor the same attribute, but we only need
    // one listener for that attribute. Track how many are using each one
    // to decide when to deregister.
    // Read/Written by evaluation and HTTP thread
    // Read/Writes protected by registrationLock
    private final HashMap<String, Integer> monitoredAttributeCount = new HashMap<>();
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final Object registrationLock = new Object();
    private MBeanServer server = ManagementFactory.getPlatformMBeanServer();

    public MBeanCache() {}

    public Map<String, Object> snapshot() {
        return new HashMap<>(monitoredAttributes);
    }

    public void monitorAttribute(String attr) throws Exception {
        synchronized (registrationLock) {
            if (gauges.containsKey(attr)) {
                monitoredAttributeCount.merge(attr, 1, Integer::sum);
                log.trace("Attribute {} is already being monitored.", attr);
                return;
            }
            GaugeMonitor monitor = new GaugeMonitor();
            ObjectName objectName = getObjectName(attr);
            monitor.addObservedObject(objectName);
            monitor.setObservedAttribute(attr);
            // Initially fire on any change
            monitor.setThresholds(0, 0);
            NotificationListener listener =
                    (notification, handback) -> {
                        if (notification instanceof MonitorNotification) {
                            if (notification.getType().equals(THRESHOLD_HIGH_VALUE_EXCEEDED)
                                    || notification
                                            .getType()
                                            .equals(THRESHOLD_LOW_VALUE_EXCEEDED)) {
                                var value = monitor.getDerivedGauge(objectName);
                                // Update the cache
                                log.trace("Updating cached value {} : {}", attr, value);
                                monitoredAttributes.put(attr, value);
                                // Listen for any change to the existing value
                                monitor.setThresholds(value, value);
                            } else {
                                log.warn(
                                        "Monitor error {}: {}",
                                        notification.getType(),
                                        notification.getMessage());
                            }
                        }
                    };
            monitor.addNotificationListener(listener, null, monitor);
            monitor.setNotifyHigh(true);
            monitor.setNotifyLow(true);

            ObjectName monitorName = generateObjectName(attr);
            log.trace("Registering monitor: {}", monitorName.toString());
            // Pre-populate cache with the current value
            monitoredAttributes.put(attr, server.getAttribute(objectName, attr));
            server.registerMBean(monitor, monitorName);
            gauges.put(attr, monitor);
            monitoredAttributeCount.merge(attr, 1, Integer::sum);
            monitor.start();
        }
    }

    public void deregister(String attr) throws Exception {
        synchronized (registrationLock) {
            monitoredAttributeCount.merge(attr, -1, Integer::sum);
            if (monitoredAttributeCount.get(attr) == 0) {
                if (!gauges.containsKey(attr)) {
                    log.warn("Attempt to deregister non-monitored attribute: {}", attr);
                    return;
                }
                gauges.get(attr).stop();
                server.unregisterMBean(generateObjectName(attr));
                monitoredAttributes.remove(attr);
                gauges.remove(attr);
            }
        }
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

    private ObjectName generateObjectName(String attr) throws MalformedObjectNameException {
        return new ObjectName(OBJECT_NAME_PREFIX + attr + "Monitor");
    }
}
