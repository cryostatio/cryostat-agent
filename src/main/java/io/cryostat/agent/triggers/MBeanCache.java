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
import java.util.Objects;
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
    private final ConcurrentHashMap<String, Object> monitoredAttributes = new ConcurrentHashMap<>();
    // Read/Written by evaluation thread and HTTP thread
    // Read/Writes protected by registrationLock
    private final HashMap<String, GaugeMonitor> gauges = new HashMap<>();
    // Multiple triggers can monitor the same attribute, but we only need
    // one listener for that attribute. Track how many are using each one
    // to decide when to deregister.
    // Read/Written by evaluation and HTTP thread
    // Read/Writes protected by registrationLock
    private final HashMap<String, Integer> monitoredAttributeCount = new HashMap<>();
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final Object registrationLock = new Object();
    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private final long evaluationPeriodMs;

    public MBeanCache(long evaluationPeriodMs) {
        this.evaluationPeriodMs = evaluationPeriodMs;
    }

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
            if (Objects.isNull(objectName)) {
                log.warn(
                        "Failed to find objectName for attribute: {}, stopping registration", attr);
                throw new IllegalArgumentException();
            }
            monitor.addObservedObject(objectName);
            monitor.setObservedAttribute(attr);
            monitor.setGranularityPeriod(evaluationPeriodMs);
            // Initially fire on any change,
            var val = server.getAttribute(objectName, attr);
            var threshold = generateThreshold(getAttributeType(attr, objectName), val);
            monitor.setThresholds(threshold, threshold);
            NotificationListener listener =
                    (notification, handback) -> {
                        if (notification instanceof MonitorNotification) {
                            if (notification.getType().equals(THRESHOLD_HIGH_VALUE_EXCEEDED)
                                    || notification
                                            .getType()
                                            .equals(THRESHOLD_LOW_VALUE_EXCEEDED)) {
                                var value = monitor.getDerivedGauge(objectName);
                                // Update the cache
                                synchronized (registrationLock) {
                                    if (gauges.get(attr) == monitor) {
                                        monitoredAttributes.put(attr, value);
                                        // Listen for any change to the existing value
                                        monitor.stop();
                                        monitor.setThresholds(value, value);
                                        monitor.start();
                                    }
                                }
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
            server.registerMBean(monitor, monitorName);
            gauges.put(attr, monitor);
            monitoredAttributeCount.merge(attr, 1, Integer::sum);
            monitor.start();
            // Pre-populate cache with the current value
            monitoredAttributes.put(attr, val);
        }
    }

    public void deregister(String attr) throws Exception {
        synchronized (registrationLock) {
            if (!monitoredAttributeCount.containsKey(attr) || !gauges.containsKey(attr)) {
                log.warn("Attempt to deregister non-monitored attribute: {}", attr);
                return;
            }
            if (monitoredAttributeCount.merge(attr, -1, Integer::sum) == 0) {
                gauges.get(attr).stop();
                try {
                    server.unregisterMBean(generateObjectName(attr));
                    monitoredAttributes.remove(attr);
                    gauges.remove(attr);
                } catch (Exception e) {
                    // De-registration failed
                    log.warn("Failed to de-register monitor for attribute {}", attr);
                    if (server.isRegistered(generateObjectName(attr))) {
                        // Monitor is still registered, restart and restore count
                        gauges.get(attr).start();
                        monitoredAttributeCount.merge(attr, 1, Integer::sum);
                        throw e;
                    } else {
                        // Monitor was unregistered, cleanup
                        monitoredAttributes.remove(attr);
                        gauges.remove(attr);
                    }
                }
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
                return i;
            }
        }
        return null;
    }

    private ObjectName generateObjectName(String attr) throws MalformedObjectNameException {
        return new ObjectName(OBJECT_NAME_PREFIX + attr + "Monitor");
    }

    private String getAttributeType(String attr, ObjectName name)
            throws IntrospectionException, InstanceNotFoundException, ReflectionException {
        List<MBeanAttributeInfo> attrs = Arrays.asList(server.getMBeanInfo(name).getAttributes());
        for (MBeanAttributeInfo a : attrs) {
            if (a.getName().equals(attr)) {
                return a.getType();
            }
        }
        return "";
    }

    // GaugeMonitors support only these types
    private Number generateThreshold(String type, Object value) {
        switch (type) {
            case "int":
            case "java.lang.Integer":
            case "short":
            case "java.lang.Short":
            case "long":
            case "java.lang.Long":
            case "float":
            case "java.lang.Float":
            case "double":
            case "java.lang.Double":
            case "byte":
            case "java.lang.Byte":
                return (Number) value;
            default:
                throw new IllegalArgumentException(
                        "Specified type cannot be used with a GaugeMonitor");
        }
    }
}
