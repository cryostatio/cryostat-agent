/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.agent.remote;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.inject.Inject;

import io.cryostat.core.net.MBeanMetrics;
import io.cryostat.core.net.MemoryMetrics;
import io.cryostat.core.net.OperatingSystemMetrics;
import io.cryostat.core.net.RuntimeMetrics;
import io.cryostat.core.net.ThreadMetrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.UnixOperatingSystemMXBean;
import com.sun.net.httpserver.HttpExchange;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class MBeanContext implements RemoteContext {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ObjectMapper mapper;

    @Inject
    MBeanContext(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String path() {
        return "/mbean-metrics";
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String mtd = exchange.getRequestMethod();
        switch (mtd) {
            case "GET":
                try {
                    MBeanMetrics metrics = getMBeanMetrics();
                    exchange.sendResponseHeaders(HttpStatus.SC_OK, 0);
                    try (OutputStream response = exchange.getResponseBody()) {
                        mapper.writeValue(response, metrics);
                    }
                } catch (Exception e) {
                    log.error("mbean serialization failure", e);
                } finally {
                    exchange.close();
                }
                break;
            default:
                exchange.sendResponseHeaders(HttpStatus.SC_NOT_FOUND, -1);
                exchange.close();
                break;
        }
    }

    private MBeanMetrics getMBeanMetrics() {
        // TODO refactor, extract into -core library?
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
        RuntimeMetrics runtime = new RuntimeMetrics(runtimeAttrs);

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
        MemoryMetrics memory = new MemoryMetrics(memoryAttrs);

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
        ThreadMetrics threads = new ThreadMetrics(threadAttrs);

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
        OperatingSystemMetrics os = new OperatingSystemMetrics(osAttrs);

        String jvmId = null;
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
            jvmId = new String(Base64.getUrlEncoder().encode(hash), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            log.error("Could not compute own jvmId!", e);
        }

        return new MBeanMetrics(runtime, memory, threads, os, jvmId);
    }

    private <T> void safeStore(String key, Map<String, Object> map, Callable<T> fn) {
        try {
            map.put(key, fn.call());
        } catch (Exception e) {
            log.warn("Call failed", e);
        }
    }
}
