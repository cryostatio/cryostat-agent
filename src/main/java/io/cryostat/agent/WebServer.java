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
package io.cryostat.agent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;

import io.cryostat.core.net.MBeanMetrics;
import io.cryostat.core.net.MemoryMetrics;
import io.cryostat.core.net.OperatingSystemMetrics;
import io.cryostat.core.net.RuntimeMetrics;
import io.cryostat.core.net.ThreadMetrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.UnixOperatingSystemMXBean;
import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dagger.Lazy;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class WebServer {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final ObjectMapper mapper;
    private final Lazy<CryostatClient> cryostat;
    private final ScheduledExecutorService executor;
    private final String host;
    private final int port;
    private final Credentials credentials;
    private final Lazy<Registration> registration;
    private HttpServer http;

    WebServer(
            ObjectMapper mapper,
            Lazy<CryostatClient> cryostat,
            ScheduledExecutorService executor,
            String host,
            int port,
            Lazy<Registration> registration) {
        this.mapper = mapper;
        this.cryostat = cryostat;
        this.executor = executor;
        this.host = host;
        this.port = port;
        this.credentials = new Credentials();
        this.registration = registration;
    }

    void start() throws IOException, NoSuchAlgorithmException {
        if (this.http != null) {
            stop();
        }

        this.generateCredentials();

        this.http = HttpServer.create(new InetSocketAddress(host, port), 0);

        this.http.setExecutor(executor);

        List<HttpContext> ctxs = new ArrayList<>();

        ctxs.add(
                this.http.createContext(
                        "/",
                        new HttpHandler() {
                            @Override
                            public void handle(HttpExchange exchange) throws IOException {
                                String mtd = exchange.getRequestMethod();
                                switch (mtd) {
                                    case "POST":
                                        synchronized (WebServer.this.credentials) {
                                            executor.execute(registration.get()::tryRegister);
                                            exchange.sendResponseHeaders(
                                                    HttpStatus.SC_NO_CONTENT, -1);
                                            exchange.close();
                                        }
                                        break;
                                    case "GET":
                                        exchange.sendResponseHeaders(HttpStatus.SC_NO_CONTENT, -1);
                                        exchange.close();
                                        break;
                                    default:
                                        exchange.sendResponseHeaders(HttpStatus.SC_NOT_FOUND, -1);
                                        exchange.close();
                                        break;
                                }
                            }
                        }));

        ctxs.add(
                this.http.createContext(
                        "/mbean-metrics",
                        new HttpHandler() {
                            @Override
                            public void handle(HttpExchange exchange) throws IOException {
                                String mtd = exchange.getRequestMethod();
                                switch (mtd) {
                                    case "GET":
                                        try {
                                            MBeanMetrics metrics = getMbeanMetrics();
                                            exchange.sendResponseHeaders(HttpStatus.SC_OK, 0);
                                            try (OutputStream response =
                                                    exchange.getResponseBody()) {
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
                        }));

        ctxs.forEach(ctx -> ctx.setAuthenticator(new AgentAuthenticator()));
        ctxs.forEach(
                ctx ->
                        ctx.getFilters()
                                .add(
                                        new Filter() {
                                            @Override
                                            public void doFilter(HttpExchange exchange, Chain chain)
                                                    throws IOException {
                                                long start = System.nanoTime();
                                                String requestMethod = exchange.getRequestMethod();
                                                String path = exchange.getRequestURI().getPath();
                                                log.info("{} {}", requestMethod, path);
                                                chain.doFilter(exchange);
                                                long elapsed = System.nanoTime() - start;
                                                log.info(
                                                        "{} {} : {} {}ms",
                                                        requestMethod,
                                                        path,
                                                        exchange.getResponseCode(),
                                                        Duration.ofNanos(elapsed).toMillis());
                                            }

                                            @Override
                                            public String description() {
                                                return ctx.getPath() + "-requestLog";
                                            }
                                        }));

        this.http.start();
    }

    void stop() {
        if (this.http != null) {
            this.http.stop(0);
            this.http = null;
        }
    }

    Credentials getCredentials() {
        return credentials;
    }

    void generateCredentials() throws NoSuchAlgorithmException {
        synchronized (this.credentials) {
            this.credentials.regenerate();
            this.cryostat
                    .get()
                    .submitCredentials(this.credentials)
                    .thenAccept(i -> log.info("Defined credentials with id {}", i))
                    .thenRun(this.credentials::clear);
        }
    }

    private <T> void safeStore(String key, Map<String, Object> map, Callable<T> fn) {
        try {
            map.put(key, fn.call());
        } catch (Exception e) {
            log.warn("Call failed", e);
        }
    }

    private MBeanMetrics getMbeanMetrics() {
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

        try {
            log.info(mapper.writeValueAsString(runtimeAttrs));
            log.info(mapper.writeValueAsString(memory));
            log.info(mapper.writeValueAsString(threads));
            log.info(mapper.writeValueAsString(os));
            log.info(jvmId);
        } catch (JsonProcessingException jpe) {
            log.error("couldn't serialize", jpe);
        }

        return new MBeanMetrics(runtime, memory, threads, os, jvmId);
    }

    private class AgentAuthenticator extends BasicAuthenticator {

        private final Logger log = LoggerFactory.getLogger(getClass());

        public AgentAuthenticator() {
            super("cryostat-agent");
        }

        @Override
        public boolean checkCredentials(String username, String password) {
            try {
                return WebServer.this.credentials.checkUserInfo(username, password);
            } catch (NoSuchAlgorithmException e) {
                log.error("Could not check credentials", e);
                return false;
            }
        }
    }

    static class Credentials {

        private static final String user = "agent";
        private byte[] passHash = new byte[0];
        private byte[] pass = new byte[0];

        synchronized boolean checkUserInfo(String username, String password)
                throws NoSuchAlgorithmException {
            return Objects.equals(username, Credentials.user)
                    && Arrays.equals(hash(password), this.passHash);
        }

        synchronized void regenerate() throws NoSuchAlgorithmException {
            this.clear();
            final SecureRandom r = SecureRandom.getInstanceStrong();
            final int len = 24;

            this.pass = new byte[len];

            // guarantee at least one character from each class
            this.pass[0] = randomSymbol();
            this.pass[1] = randomNumeric();
            this.pass[2] = randomAlphabetical(r.nextBoolean());

            // fill remaining slots with randomly assigned characters across classes
            for (int i = 3; i < len; i++) {
                int s = r.nextInt(3);
                if (s == 0) {
                    this.pass[i] = randomSymbol();
                } else if (s == 1) {
                    this.pass[i] = randomNumeric();
                } else {
                    this.pass[i] = randomAlphabetical(r.nextBoolean());
                }
            }

            // randomly shuffle the characters
            // https://en.wikipedia.org/wiki/Fisher%E2%80%93Yates_shuffle
            for (int i = this.pass.length - 1; i > 1; i--) {
                int j = r.nextInt(i);
                byte b = this.pass[i];
                this.pass[i] = this.pass[j];
                this.pass[j] = b;
            }

            this.passHash = hash(this.pass);
        }

        String user() {
            return user;
        }

        synchronized byte[] pass() {
            return pass;
        }

        synchronized void clear() {
            Arrays.fill(this.pass, (byte) 0);
        }

        private static byte randomAlphabetical(boolean upperCase) throws NoSuchAlgorithmException {
            return randomChar(upperCase ? 'A' : 'a', 26);
        }

        private static byte randomNumeric() throws NoSuchAlgorithmException {
            return randomChar('0', 10);
        }

        private static byte randomSymbol() throws NoSuchAlgorithmException {
            return randomChar(33, 14);
        }

        private static byte randomChar(int offset, int range) throws NoSuchAlgorithmException {
            return (byte) (SecureRandom.getInstanceStrong().nextInt(range) + offset);
        }

        private static byte[] hash(String pass) throws NoSuchAlgorithmException {
            return hash(pass.getBytes(StandardCharsets.UTF_8));
        }

        private static byte[] hash(byte[] bytes) throws NoSuchAlgorithmException {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        }
    }
}
