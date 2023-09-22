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
package io.cryostat.agent.model;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import io.cryostat.core.net.MBeanMetrics;
import io.cryostat.core.net.MemoryMetrics;
import io.cryostat.core.net.OperatingSystemMetrics;
import io.cryostat.core.net.RuntimeMetrics;
import io.cryostat.core.net.ThreadMetrics;

import com.sun.management.UnixOperatingSystemMXBean;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MBeanInfo {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private MBeanMetrics mBeanMetrics;
    private Map<String, Object> simplifiedMetrics;

    public MBeanInfo() {
        this.simplifiedMetrics = new HashMap<>();
        mBeanMetrics =
                new MBeanMetrics(
                        getRuntimeMetrics(),
                        getMemoryMetrics(),
                        getThreadMetrics(),
                        getOperatingSystemMetrics(),
                        getJvmID(getRuntimeMetrics()));
    }

    public MBeanMetrics getMBeanMetrics() {
        return mBeanMetrics;
    }

    public Map<String, Object> getSimplifiedMetrics() {
        // Map<String, Object> simplifiedMetrics = new HashMap<>();
        return Collections.unmodifiableMap(simplifiedMetrics);
    }

    private OperatingSystemMetrics getOperatingSystemMetrics() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        Map<String, Object> osAttrs = new HashMap<>();
        safeStore("Arch", osAttrs, osBean::getArch);
        safeStore("AvailableProcessors", osAttrs, osBean::getAvailableProcessors);
        safeStore("Name", osAttrs, osBean::getName);
        safeStore("SystemLoadAverage", osAttrs, osBean::getSystemLoadAverage);
        safeStore("Version", osAttrs, osBean::getVersion);
        if (osBean instanceof UnixOperatingSystemMXBean) {
            UnixOperatingSystemMXBean unix = (UnixOperatingSystemMXBean) osBean;
            safeStore("CommittedVirtualMemorySize", osAttrs, unix::getCommittedVirtualMemorySize);
            safeStore("FreePhysicalMemorySize", osAttrs, unix::getFreePhysicalMemorySize);
            safeStore("FreeSwapSpaceSize", osAttrs, unix::getFreeSwapSpaceSize);
            safeStore("ProcessCpuLoad", osAttrs, unix::getProcessCpuLoad);
            safeStore("ProcessCpuTime", osAttrs, unix::getProcessCpuTime);
            safeStore("SystemCpuLoad", osAttrs, unix::getSystemCpuLoad);
            safeStore("TotalPhysicalMemorySize", osAttrs, unix::getTotalPhysicalMemorySize);
            safeStore("TotalSwapSpaceSize", osAttrs, unix::getTotalSwapSpaceSize);
        }
        return new OperatingSystemMetrics(osAttrs);
    }

    private ThreadMetrics getThreadMetrics() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        Map<String, Object> threadAttrs = new HashMap<>();
        safeStore("AllThreadIds", threadAttrs, threadBean::getAllThreadIds);
        safeStore("CurrentThreadCpuTime", threadAttrs, threadBean::getCurrentThreadCpuTime);
        safeStore("CurrentThreadUserTime", threadAttrs, threadBean::getCurrentThreadUserTime);
        safeStore("DaemonThreadCount", threadAttrs, threadBean::getDaemonThreadCount);
        safeStore("PeakThreadCount", threadAttrs, threadBean::getPeakThreadCount);
        safeStore("ThreadCount", threadAttrs, threadBean::getThreadCount);
        safeStore("TotalStartedThreadCount", threadAttrs, threadBean::getTotalStartedThreadCount);
        safeStore(
                "CurrentThreadCpuTimeSupported",
                threadAttrs,
                threadBean::isCurrentThreadCpuTimeSupported);
        safeStore(
                "ObjectMonitorUsageSupported",
                threadAttrs,
                threadBean::isObjectMonitorUsageSupported);
        safeStore(
                "SynchronizerUsageSupported",
                threadAttrs,
                threadBean::isSynchronizerUsageSupported);
        safeStore(
                "ThreadContentionMonitoringEnabled",
                threadAttrs,
                threadBean::isThreadContentionMonitoringEnabled);
        safeStore(
                "ThreadContentionMonitoringSupported",
                threadAttrs,
                threadBean::isThreadContentionMonitoringSupported);
        safeStore("ThreadCpuTimeEnabled", threadAttrs, threadBean::isThreadCpuTimeEnabled);
        safeStore("ThreadCpuTimeSupported", threadAttrs, threadBean::isThreadCpuTimeSupported);
        return new ThreadMetrics(threadAttrs);
    }

    private RuntimeMetrics getRuntimeMetrics() {
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        Map<String, Object> runtimeAttrs = new HashMap<>();
        safeStore("BootClassPath", runtimeAttrs, runtimeBean::getBootClassPath);
        safeStore("ClassPath", runtimeAttrs, runtimeBean::getClassPath);
        safeStore(
                "InputArguments",
                runtimeAttrs,
                () -> runtimeBean.getInputArguments().toArray(new String[0]));
        safeStore("LibraryPath", runtimeAttrs, runtimeBean::getLibraryPath);
        safeStore("ManagementSpecVersion", runtimeAttrs, runtimeBean::getManagementSpecVersion);
        safeStore("Name", runtimeAttrs, runtimeBean::getName);
        safeStore("SpecName", runtimeAttrs, runtimeBean::getSpecName);
        safeStore("SpecVersion", runtimeAttrs, runtimeBean::getSpecVersion);
        safeStore("SystemProperties", runtimeAttrs, runtimeBean::getSystemProperties);
        safeStore("StartTime", runtimeAttrs, runtimeBean::getStartTime);
        safeStore("Uptime", runtimeAttrs, runtimeBean::getUptime);
        safeStore("VmName", runtimeAttrs, runtimeBean::getVmName);
        safeStore("VmVendor", runtimeAttrs, runtimeBean::getVmVendor);
        safeStore("VmVersion", runtimeAttrs, runtimeBean::getVmVersion);
        safeStore("BootClassPathSupported", runtimeAttrs, runtimeBean::isBootClassPathSupported);
        return new RuntimeMetrics(runtimeAttrs);
    }

    private MemoryMetrics getMemoryMetrics() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        Map<String, Object> memoryAttrs = new HashMap<>();
        safeStore("HeapMemoryUsage", memoryAttrs, memoryBean::getHeapMemoryUsage);
        safeStore("NonHeapMemoryUsage", memoryAttrs, memoryBean::getNonHeapMemoryUsage);
        safeStore(
                "ObjectPendingFinalizationCount",
                memoryAttrs,
                memoryBean::getObjectPendingFinalizationCount);
        // TODO are these inferred calculations correct (ie do they match what the bean attributes
        // say)?
        safeStore(
                "FreeHeapMemory",
                memoryAttrs,
                () ->
                        memoryBean.getHeapMemoryUsage().getCommitted()
                                - memoryBean.getHeapMemoryUsage().getUsed());
        safeStore(
                "FreeNonHeapMemory",
                memoryAttrs,
                () ->
                        memoryBean.getNonHeapMemoryUsage().getCommitted()
                                - memoryBean.getNonHeapMemoryUsage().getUsed());
        safeStore(
                "HeapMemoryUsagePercent",
                memoryAttrs,
                () ->
                        ((double) memoryBean.getHeapMemoryUsage().getUsed()
                                / (double) memoryBean.getHeapMemoryUsage().getCommitted()));
        safeStore("Verbose", memoryAttrs, memoryBean::isVerbose);
        return new MemoryMetrics(memoryAttrs);
    }

    private String getJvmID(RuntimeMetrics runtime) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
                DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeUTF(runtime.getClassPath());
            dos.writeUTF(runtime.getName());
            dos.writeUTF(Arrays.toString(runtime.getInputArguments()));
            dos.writeUTF(runtime.getLibraryPath());
            dos.writeUTF(runtime.getVmVendor());
            dos.writeUTF(runtime.getVmVersion());
            dos.writeLong(runtime.getStartTime());
            byte[] hash = DigestUtils.sha256(baos.toByteArray());
            return new String(Base64.getUrlEncoder().encode(hash), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            log.error("Could not compute own jvmId!", e);
            return "";
        }
    }

    private <T> void safeStore(String key, Map<String, Object> map, Callable<T> fn) {
        try {
            map.put(key, fn.call());
            simplifiedMetrics.put(key, fn.call());
        } catch (Exception e) {
            log.warn("Call failed", e);
        }
    }
}
