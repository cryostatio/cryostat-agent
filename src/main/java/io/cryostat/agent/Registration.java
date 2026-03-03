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
package io.cryostat.agent;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import io.cryostat.agent.VersionInfo.Semver;
import io.cryostat.agent.model.DiscoveryNode;
import io.cryostat.agent.model.PluginInfo;

import org.apache.commons.lang3.StringUtils;
import org.apache.hc.core5.net.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Registration {

    private static final String AGENT_NODE_TYPE = "CryostatAgent";
    private static final String JMX_NODE_TYPE = "JVM";

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final ScheduledExecutorService executor;
    private final CryostatClient cryostat;
    private final CallbackResolver callbackResolver;
    private final WebServer webServer;
    private final String instanceId;
    private final String jvmId;
    private final String appName;
    private final String realm;
    private final String hostname;
    private final int jmxPort;
    private final int registrationRetryMs;
    private final int registrationCheckMs;
    private final boolean registrationJmxIgnore;
    private final boolean registrationJmxUseCallbackHost;

    private final PluginInfo pluginInfo = new PluginInfo();
    private final Set<Consumer<RegistrationEvent>> listeners = new HashSet<>();

    private volatile URI callback;
    private ScheduledFuture<?> registrationCheckTask;

    Registration(
            ScheduledExecutorService executor,
            CryostatClient cryostat,
            CallbackResolver callbackResolver,
            WebServer webServer,
            String instanceId,
            String jvmId,
            String appName,
            String realm,
            String hostname,
            int jmxPort,
            int registrationRetryMs,
            int registrationCheckMs,
            boolean registrationJmxIgnore,
            boolean registrationJmxUseCallbackHost) {
        this.executor = executor;
        this.cryostat = cryostat;
        this.callbackResolver = callbackResolver;
        this.webServer = webServer;
        this.instanceId = instanceId;
        this.jvmId = jvmId;
        this.appName = appName;
        this.realm = realm;
        this.hostname = hostname;
        this.jmxPort = jmxPort;
        this.registrationRetryMs = registrationRetryMs;
        this.registrationCheckMs = registrationCheckMs;
        this.registrationJmxIgnore = registrationJmxIgnore;
        this.registrationJmxUseCallbackHost = registrationJmxUseCallbackHost;
    }

    void start() {
        this.addRegistrationListener(
                evt -> {
                    switch (evt.state) {
                        case UNREGISTERED:
                            if (this.registrationCheckTask != null) {
                                this.registrationCheckTask.cancel(false);
                                this.registrationCheckTask = null;
                            }
                            try {
                                this.callback = callbackResolver.determineSelfCallback();
                            } catch (UnknownHostException e) {
                                executor.schedule(
                                        () -> notify(RegistrationEvent.State.UNREGISTERED),
                                        registrationRetryMs,
                                        TimeUnit.MILLISECONDS);
                            }
                            executor.submit(
                                    () -> {
                                        webServer
                                                .generateCredentials(callback)
                                                .handle(
                                                        (v, t) -> {
                                                            if (t != null) {
                                                                executor.schedule(
                                                                        () ->
                                                                                notify(
                                                                                        RegistrationEvent
                                                                                                .State
                                                                                                .UNREGISTERED),
                                                                        registrationRetryMs,
                                                                        TimeUnit.MILLISECONDS);
                                                                log.error(
                                                                        "Failed to generate"
                                                                                + " credentials",
                                                                        t);
                                                                throw new CompletionException(t);
                                                            }
                                                            notify(
                                                                    RegistrationEvent.State
                                                                            .REFRESHING);
                                                            return v;
                                                        });
                                    });
                            break;
                        case REGISTERED:
                            if (this.registrationCheckTask != null) {
                                log.warn("Re-registered without previous de-registration");
                                this.registrationCheckTask.cancel(false);
                            }
                            this.registrationCheckTask =
                                    executor.scheduleAtFixedRate(
                                            () -> {
                                                try {
                                                    cryostat.checkRegistration(pluginInfo)
                                                            .handle(
                                                                    (v, t) -> {
                                                                        if (t != null
                                                                                || !Boolean.TRUE
                                                                                        .equals(
                                                                                                v)) {
                                                                            this.pluginInfo.clear();
                                                                            notify(
                                                                                    RegistrationEvent
                                                                                            .State
                                                                                            .UNREGISTERED);
                                                                        }
                                                                        return null;
                                                                    })
                                                            .get();
                                                } catch (ExecutionException
                                                        | InterruptedException e) {
                                                    log.error(
                                                            "Could not check registration status",
                                                            e);
                                                }
                                            },
                                            registrationCheckMs,
                                            registrationCheckMs,
                                            TimeUnit.MILLISECONDS);
                            break;
                        case REFRESHING:
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
        notify(RegistrationEvent.State.UNREGISTERED);
        log.trace("{} started", getClass().getName());
    }

    public void addRegistrationListener(Consumer<RegistrationEvent> listener) {
        this.listeners.add(listener);
    }

    void tryRegister() {
        int credentialId = webServer.getCredentialId();
        if (credentialId < 0) {
            notify(RegistrationEvent.State.UNREGISTERED);
            return;
        }
        try {
            cryostat.serverHealth()
                    .thenAccept(
                            health -> {
                                Semver cryostatSemver = health.cryostatSemver();
                                log.debug(
                                        "Connected to Cryostat server: version {} , build {}",
                                        cryostatSemver,
                                        health.buildInfo().git().hash());
                                try {
                                    VersionInfo version = VersionInfo.load();
                                    if (!version.validateServerVersion(cryostatSemver)) {
                                        log.warn(
                                                "Cryostat server version {} is outside of expected"
                                                        + " range [{}, {})",
                                                cryostatSemver,
                                                version.getServerMin(),
                                                version.getServerMax());
                                    }
                                } catch (IOException ioe) {
                                    log.error("Unable to read versions.properties file", ioe);
                                }
                            })
                    .get();
            URI credentialedCallback =
                    new URIBuilder(callback)
                            .setUserInfo("storedcredentials", String.valueOf(credentialId))
                            .build();
            CompletableFuture<Void> f =
                    cryostat.register(credentialId, pluginInfo, credentialedCallback)
                            .handle(
                                    (plugin, t) -> {
                                        if (plugin != null) {
                                            boolean previouslyRegistered =
                                                    this.pluginInfo.isInitialized();
                                            this.pluginInfo.copyFrom(plugin);
                                            log.debug("Registered as {}", this.pluginInfo.getId());
                                            if (previouslyRegistered) {
                                                notify(RegistrationEvent.State.REFRESHED);
                                            } else {
                                                notify(RegistrationEvent.State.REGISTERED);
                                                tryUpdate();
                                            }
                                        } else if (t != null) {
                                            this.webServer.resetCredentialId();
                                            this.pluginInfo.clear();
                                            throw new RegistrationException(t);
                                        }

                                        return (Void) null;
                                    });
            f.get();
        } catch (URISyntaxException | ExecutionException | InterruptedException e) {
            log.error("Registration failure", e);
            log.trace("Registration retry period: {}", Duration.ofMillis(registrationRetryMs));
            executor.schedule(this::tryRegister, registrationRetryMs, TimeUnit.MILLISECONDS);
        }
    }

    private void tryUpdate() {
        if (!this.pluginInfo.isInitialized()) {
            log.warn("update attempted before initialized");
            return;
        }
        Collection<DiscoveryNode> selfNodes;
        try {
            selfNodes = defineSelf();
        } catch (UnknownHostException | URISyntaxException e) {
            log.error("Unable to define self", e);
            return;
        }
        log.trace(
                "publishing self as {}",
                selfNodes.stream()
                        .map(n -> n.getTarget().getConnectUrl())
                        .collect(Collectors.toList()));
        Future<Void> f =
                cryostat.update(pluginInfo, selfNodes)
                        .handle(
                                (n, t) -> {
                                    if (t != null) {
                                        log.error("Update failure", t);
                                        deregister();
                                    } else {
                                        log.trace("Publish success");
                                        notify(RegistrationEvent.State.PUBLISHED);
                                    }
                                    return (Void) null;
                                });
        try {
            f.get();
        } catch (ExecutionException | InterruptedException e) {
            log.error("Failed to update", e);
        }
    }

    private Set<DiscoveryNode> defineSelf() throws UnknownHostException, URISyntaxException {
        Set<DiscoveryNode> discoveryNodes = new HashSet<>();

        long pid = ProcessHandle.current().pid();
        String javaMain = System.getProperty("sun.java.command", System.getenv("JAVA_MAIN_CLASS"));
        if (StringUtils.isBlank(javaMain)) {
            log.warn("Unable to determine application mainclass");
            javaMain = null;
        }
        long startTime =
                ProcessHandle.current()
                        .info()
                        .startInstant()
                        .orElse(Instant.EPOCH)
                        .getEpochSecond();
        URI uri = callback;
        int port = uri.getPort();
        DiscoveryNode.Target httpSelf =
                new DiscoveryNode.Target(
                        realm,
                        uri,
                        appName,
                        instanceId,
                        jvmId,
                        pid,
                        hostname,
                        port,
                        javaMain,
                        startTime);
        discoveryNodes.add(
                new DiscoveryNode(
                        appName + "-agent-" + pluginInfo.getId(), AGENT_NODE_TYPE, httpSelf));

        if (!registrationJmxIgnore && jmxPort > 0) {
            uri =
                    URI.create(
                            String.format(
                                    "service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi",
                                    registrationJmxUseCallbackHost ? uri.getHost() : hostname,
                                    jmxPort));
            port = jmxPort;
            DiscoveryNode.Target jmxSelf =
                    new DiscoveryNode.Target(
                            realm,
                            uri,
                            appName,
                            instanceId,
                            jvmId,
                            pid,
                            hostname,
                            port,
                            javaMain,
                            startTime);
            discoveryNodes.add(
                    new DiscoveryNode(
                            appName + "-jmx-" + pluginInfo.getId(), JMX_NODE_TYPE, jmxSelf));
        }

        return discoveryNodes;
    }

    void stop() {}

    CompletableFuture<Void> deregister() {
        if (!this.pluginInfo.isInitialized()) {
            log.warn("Deregistration requested before registration complete!");
            return CompletableFuture.completedFuture(null);
        }
        return cryostat.deleteCredentials(webServer.getCredentialId())
                .handle((v, t) -> cryostat.deregister(pluginInfo))
                .handle(
                        (n, t) -> {
                            if (t != null) {
                                log.warn(
                                        "Failed to deregister as Cryostat discovery plugin [{}]",
                                        this.pluginInfo.getId());
                            } else {
                                log.debug(
                                        "Deregistered from Cryostat discovery plugin [{}]",
                                        this.pluginInfo.getId());
                            }
                            this.pluginInfo.clear();
                            notify(RegistrationEvent.State.UNREGISTERED);
                            return null;
                        });
    }

    public void notify(RegistrationEvent.State state) {
        RegistrationEvent evt = new RegistrationEvent(state);
        executor.submit(() -> this.listeners.forEach(listener -> listener.accept(evt)));
    }

    public static class RegistrationEvent {

        public enum State {
            UNREGISTERED,
            REGISTERED,
            PUBLISHED,
            REFRESHING,
            REFRESHED,
        }

        public final State state;

        RegistrationEvent(State state) {
            this.state = state;
        }
    }

    PluginInfo getPluginInfo() {
        return pluginInfo;
    }
}
