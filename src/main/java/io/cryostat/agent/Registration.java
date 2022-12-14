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

import java.net.URI;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.cryostat.agent.model.DiscoveryNode;
import io.cryostat.agent.model.PluginInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class Registration {

    private static final String NODE_TYPE = "JVM";

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final ScheduledExecutorService executor;
    private final CryostatClient cryostat;
    private final UUID instanceId;
    private final String appName;
    private final String realm;
    private final String hostname;
    private final int jmxPort;
    private final int registrationRetryMs;

    private final PluginInfo pluginInfo = new PluginInfo();

    Registration(
            ScheduledExecutorService executor,
            CryostatClient cryostat,
            UUID instanceId,
            String appName,
            String realm,
            String hostname,
            int jmxPort,
            int registrationRetryMs) {
        this.executor = executor;
        this.cryostat = cryostat;
        this.instanceId = instanceId;
        this.appName = appName;
        this.realm = realm;
        this.hostname = hostname;
        this.jmxPort = jmxPort;
        this.registrationRetryMs = registrationRetryMs;
    }

    void start() {
        executor.submit(this::tryRegister);
        log.info("{} started", getClass().getName());
    }

    void tryRegister() {
        Future<Void> f =
                cryostat.register(pluginInfo)
                        .handleAsync(
                                (plugin, t) -> {
                                    if (plugin != null) {
                                        this.pluginInfo.copyFrom(plugin);
                                        log.info("Registered as {}", this.pluginInfo.getId());
                                        tryUpdate();
                                    } else if (t != null) {
                                        throw new RegistrationException(t);
                                    }

                                    return (Void) null;
                                },
                                executor);
        try {
            f.get();
        } catch (ExecutionException | InterruptedException e) {
            log.error("Registration failure", e);
            log.info("Registration retry period: {}(ms)", registrationRetryMs);
            executor.schedule(this::tryRegister, registrationRetryMs, TimeUnit.MILLISECONDS);
        }
    }

    private void tryUpdate() {
        if (!this.pluginInfo.isInitialized()) {
            log.warn("update attempted before initialized");
            return;
        }
        DiscoveryNode selfNode;
        try {
            selfNode = defineSelf();
        } catch (UnknownHostException uhe) {
            log.error("Unable to define self", uhe);
            return;
        }
        log.info("publishing self as {}", selfNode.getTarget().getConnectUrl());
        Future<Void> f =
                cryostat.update(pluginInfo, Set.of(selfNode))
                        .handleAsync(
                                (n, t) -> {
                                    if (t != null) {
                                        log.error("Update failure", t);
                                        deregister()
                                                .thenRunAsync(
                                                        () -> {
                                                            executor.schedule(
                                                                    this::tryRegister,
                                                                    registrationRetryMs,
                                                                    TimeUnit.MILLISECONDS);
                                                        },
                                                        executor);
                                    } else {
                                        log.info("Publish success");
                                    }
                                    return (Void) null;
                                },
                                executor);
        try {
            f.get();
        } catch (ExecutionException | InterruptedException e) {
            log.warn("Failed to update", e);
        }
    }

    private DiscoveryNode defineSelf() throws UnknownHostException {
        long pid = ProcessHandle.current().pid();
        String javaMain = System.getProperty("sun.java.command", System.getenv("JAVA_MAIN_CLASS"));
        if (StringUtils.isBlank(javaMain)) {
            log.error("Unable to determine application mainclass");
            javaMain = null;
        }
        long startTime =
                ProcessHandle.current()
                        .info()
                        .startInstant()
                        .orElse(Instant.EPOCH)
                        .getEpochSecond();
        DiscoveryNode.Target target =
                new DiscoveryNode.Target(
                        realm,
                        URI.create(
                                String.format(
                                        "service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi",
                                        hostname, jmxPort)),
                        appName,
                        instanceId,
                        pid,
                        hostname,
                        jmxPort,
                        javaMain,
                        startTime);

        DiscoveryNode selfNode =
                new DiscoveryNode(appName + "-" + pluginInfo.getId(), NODE_TYPE, target);
        return selfNode;
    }

    void stop() {}

    CompletableFuture<Void> deregister() {
        if (!this.pluginInfo.isInitialized()) {
            log.info("Deregistration requested before registration complete!");
            return CompletableFuture.completedFuture(null);
        }
        return cryostat.deregister(pluginInfo)
                .handleAsync(
                        (n, t) -> {
                            if (t != null) {
                                log.warn(
                                        "Failed to deregister as Cryostat discovery plugin [{}]",
                                        this.pluginInfo.getId());
                            } else {
                                log.info(
                                        "Deregistered from Cryostat discovery plugin [{}]",
                                        this.pluginInfo.getId());
                            }
                            this.pluginInfo.clear();
                            return null;
                        },
                        executor);
    }
}
