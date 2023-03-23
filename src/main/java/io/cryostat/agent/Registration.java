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
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.cryostat.agent.model.DiscoveryNode;
import io.cryostat.agent.model.PluginInfo;

import org.apache.http.client.utils.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class Registration {

    private static final String NODE_TYPE = "JVM";

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final ScheduledExecutorService executor;
    private final CryostatClient cryostat;
    private final URI callback;
    private final WebServer webServer;
    private final String jvmId;
    private final String appName;
    private final String realm;
    private final String hostname;
    private final boolean preferJmx;
    private final int jmxPort;
    private final int registrationRetryMs;
    private final int registrationCheckMs;

    private final PluginInfo pluginInfo = new PluginInfo();
    private final Set<Consumer<RegistrationEvent>> listeners = new HashSet<>();

    Registration(
            ScheduledExecutorService executor,
            CryostatClient cryostat,
            URI callback,
            WebServer webServer,
            String jvmId,
            String appName,
            String realm,
            String hostname,
            boolean preferJmx,
            int jmxPort,
            int registrationRetryMs,
            int registrationCheckMs) {
        this.executor = executor;
        this.cryostat = cryostat;
        this.callback = callback;
        this.webServer = webServer;
        this.jvmId = jvmId;
        this.appName = appName;
        this.realm = realm;
        this.hostname = hostname;
        this.preferJmx = preferJmx;
        this.jmxPort = jmxPort;
        this.registrationRetryMs = registrationRetryMs;
        this.registrationCheckMs = registrationCheckMs;
    }

    void start() {
        this.addRegistrationListener(
                evt -> {
                    switch (evt.state) {
                        case REGISTERED:
                            break;
                        case UNREGISTERED:
                            executor.submit(this::tryRegister);
                            break;
                        case REFRESHED:
                            break;
                        case PUBLISHED:
                            break;
                        default:
                            break;
                    }
                });
        executor.scheduleAtFixedRate(
                () -> {
                    try {
                        cryostat.checkRegistration(pluginInfo)
                                .handle(
                                        (v, t) -> {
                                            if (t != null || !Boolean.TRUE.equals(v)) {
                                                this.pluginInfo.clear();
                                                notify(RegistrationEvent.State.UNREGISTERED);
                                            }
                                            return null;
                                        })
                                .get();
                    } catch (ExecutionException | InterruptedException e) {
                        log.warn("Could not check registration status", e);
                    }
                },
                0,
                registrationCheckMs,
                TimeUnit.MILLISECONDS);
        log.info("{} started", getClass().getName());
    }

    void addRegistrationListener(Consumer<RegistrationEvent> listener) {
        this.listeners.add(listener);
    }

    void tryRegister() {
        try {
            URI credentialedCallback =
                    new URIBuilder(callback)
                            .setUserInfo(
                                    "storedcredentials",
                                    String.valueOf(webServer.getCredentialId()))
                            .build();
            CompletableFuture<Void> f =
                    cryostat.register(pluginInfo, credentialedCallback)
                            .handle(
                                    (plugin, t) -> {
                                        if (plugin != null) {
                                            boolean previouslyRegistered =
                                                    this.pluginInfo.isInitialized();
                                            this.pluginInfo.copyFrom(plugin);
                                            log.info("Registered as {}", this.pluginInfo.getId());
                                            notify(
                                                    previouslyRegistered
                                                            ? RegistrationEvent.State.REFRESHED
                                                            : RegistrationEvent.State.REGISTERED);
                                            tryUpdate();
                                        } else if (t != null) {
                                            this.pluginInfo.clear();
                                            notify(RegistrationEvent.State.UNREGISTERED);
                                            throw new RegistrationException(t);
                                        }

                                        return (Void) null;
                                    });
            f.get();
        } catch (URISyntaxException | ExecutionException | InterruptedException e) {
            log.error("Registration failure", e);
            log.info("Registration retry period: {}", Duration.ofMillis(registrationRetryMs));
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
        } catch (UnknownHostException | URISyntaxException e) {
            log.error("Unable to define self", e);
            return;
        }
        log.info("publishing self as {}", selfNode.getTarget().getConnectUrl());
        Future<Void> f =
                cryostat.update(pluginInfo, Set.of(selfNode))
                        .handle(
                                (n, t) -> {
                                    if (t != null) {
                                        log.error("Update failure", t);
                                        deregister();
                                    } else {
                                        log.info("Publish success");
                                        notify(RegistrationEvent.State.PUBLISHED);
                                    }
                                    return (Void) null;
                                });
        try {
            f.get();
        } catch (ExecutionException | InterruptedException e) {
            log.warn("Failed to update", e);
        }
    }

    private DiscoveryNode defineSelf() throws UnknownHostException, URISyntaxException {
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
        URI uri = callback;
        if (preferJmx && jmxPort > 0) {
            uri =
                    URI.create(
                            String.format(
                                    "service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi",
                                    hostname, jmxPort));
        }
        DiscoveryNode.Target target =
                new DiscoveryNode.Target(
                        realm,
                        uri,
                        appName,
                        jvmId,
                        pid,
                        hostname,
                        uri.getPort(),
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
        return cryostat.deleteCredentials(webServer.getCredentialId())
                .thenCompose(v -> cryostat.deregister(pluginInfo))
                .handle(
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
                            notify(RegistrationEvent.State.UNREGISTERED);
                            return null;
                        });
    }

    private void notify(RegistrationEvent.State state) {
        RegistrationEvent evt = new RegistrationEvent(state);
        this.listeners.forEach(listener -> listener.accept(evt));
    }

    static class RegistrationEvent {

        enum State {
            REGISTERED,
            PUBLISHED,
            UNREGISTERED,
            REFRESHED,
        }

        final State state;

        RegistrationEvent(State state) {
            this.state = state;
        }
    }
}
