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
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import io.cryostat.agent.VersionInfo.Semver;
import io.cryostat.agent.model.DiscoveryNode;
import io.cryostat.agent.model.PluginInfo;
import io.cryostat.agent.util.AppNameResolver;

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
    private final AppNameResolver appNameResolver;
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
    private final int maxBackoffMs;
    private final double backoffMultiplier;
    private final int circuitBreakerThreshold;
    private final Duration circuitBreakerOpenDuration;
    private final Duration minCooldownDuration;
    private final Random random;

    private final PluginInfo pluginInfo = new PluginInfo();
    private final Set<Consumer<RegistrationEvent>> listeners = new HashSet<>();

    private volatile URI callback;
    private ScheduledFuture<?> registrationCheckTask;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private static final double JITTER_FACTOR = 0.1;
    private volatile CircuitState circuitState = CircuitState.CLOSED;
    private volatile Instant circuitOpenedAt = null;

    private volatile CompletableFuture<?> currentRegistration = null;

    private volatile Instant lastSuccessfulRegistration = Instant.EPOCH;
    private volatile Instant cooldownUntil = null;
    private volatile ScheduledFuture<?> cooldownExitTask = null;
    private final Object cooldownLock = new Object();

    private enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    Registration(
            ScheduledExecutorService executor,
            CryostatClient cryostat,
            CallbackResolver callbackResolver,
            WebServer webServer,
            io.cryostat.agent.util.AppNameResolver appNameResolver,
            String instanceId,
            String jvmId,
            String appName,
            String realm,
            String hostname,
            int jmxPort,
            int registrationRetryMs,
            int registrationCheckMs,
            boolean registrationJmxIgnore,
            boolean registrationJmxUseCallbackHost,
            int maxBackoffMs,
            double backoffMultiplier,
            int circuitBreakerThreshold,
            Duration circuitBreakerOpenDuration,
            Duration minCooldownDuration,
            Random random) {
        this.executor = executor;
        this.cryostat = cryostat;
        this.callbackResolver = callbackResolver;
        this.webServer = webServer;
        this.appNameResolver = appNameResolver;
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
        this.maxBackoffMs = maxBackoffMs;
        this.backoffMultiplier = backoffMultiplier;
        this.circuitBreakerThreshold = circuitBreakerThreshold;
        this.circuitBreakerOpenDuration = circuitBreakerOpenDuration;
        this.minCooldownDuration = minCooldownDuration;
        this.random = random;
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
                                                cryostat.checkRegistration(pluginInfo)
                                                        .handle(
                                                                (v, t) -> {
                                                                    if (t != null
                                                                            || !Boolean.TRUE.equals(
                                                                                    v)) {
                                                                        this.pluginInfo.clear();
                                                                        notify(
                                                                                RegistrationEvent
                                                                                        .State
                                                                                        .UNREGISTERED);
                                                                    }
                                                                    return null;
                                                                })
                                                        .exceptionally(
                                                                e -> {
                                                                    log.error(
                                                                            "Could not check"
                                                                                + " registration"
                                                                                + " status",
                                                                            e);
                                                                    return null;
                                                                });
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
        if (currentRegistration != null && !currentRegistration.isDone()) {
            log.warn("Cancelling previous registration attempt");
            currentRegistration.cancel(true);
        }

        if (isInCooldown()) {
            Duration remaining = getCooldownRemaining();
            log.debug("In cooldown, retry in {}", remaining);
            executor.schedule(this::tryRegister, remaining.toMillis(), TimeUnit.MILLISECONDS);
            return;
        }

        if (circuitState == CircuitState.OPEN) {
            if (Duration.between(circuitOpenedAt, Instant.now())
                            .compareTo(circuitBreakerOpenDuration)
                    > 0) {
                log.debug("Circuit breaker transitioning to HALF_OPEN");
                circuitState = CircuitState.HALF_OPEN;
            } else {
                log.debug("Circuit breaker OPEN, skipping registration attempt");
                executor.schedule(
                        this::tryRegister,
                        circuitBreakerOpenDuration.toMillis() / 10,
                        TimeUnit.MILLISECONDS);
                return;
            }
        }

        int credentialId = webServer.getCredentialId();
        if (credentialId < 0) {
            notify(RegistrationEvent.State.UNREGISTERED);
            return;
        }

        currentRegistration =
                cryostat.serverHealth()
                        .thenCompose(
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
                                                    "Cryostat server version {} is outside of"
                                                            + " expected range [{}, {})",
                                                    cryostatSemver,
                                                    version.getServerMin(),
                                                    version.getServerMax());
                                        }
                                    } catch (IOException ioe) {
                                        log.error("Unable to read versions.properties file", ioe);
                                    }

                                    try {
                                        URI credentialedCallback =
                                                new URIBuilder(callback)
                                                        .setUserInfo(
                                                                "storedcredentials",
                                                                String.valueOf(credentialId))
                                                        .build();
                                        return cryostat.register(
                                                credentialId, pluginInfo, credentialedCallback);
                                    } catch (URISyntaxException e) {
                                        return CompletableFuture.failedFuture(e);
                                    }
                                })
                        .handle(
                                (plugin, t) -> {
                                    if (plugin != null) {
                                        boolean previouslyRegistered =
                                                this.pluginInfo.isInitialized();
                                        this.pluginInfo.copyFrom(plugin);
                                        log.debug("Registered as {}", this.pluginInfo.getId());

                                        lastSuccessfulRegistration = Instant.now();
                                        consecutiveFailures.set(0);

                                        if (circuitState == CircuitState.HALF_OPEN) {
                                            log.debug("Circuit breaker transitioning to CLOSED");
                                        }
                                        circuitState = CircuitState.CLOSED;

                                        log.debug(
                                                "Registration successful at {}, next attempt"
                                                        + " allowed after {}",
                                                lastSuccessfulRegistration,
                                                lastSuccessfulRegistration.plus(
                                                        minCooldownDuration));

                                        if (previouslyRegistered) {
                                            notify(RegistrationEvent.State.REFRESHED);
                                        } else {
                                            notify(RegistrationEvent.State.REGISTERED);
                                            tryUpdate();
                                        }
                                    } else if (t != null) {
                                        this.webServer.resetCredentialId();
                                        this.pluginInfo.clear();

                                        int failures = consecutiveFailures.incrementAndGet();
                                        long backoffMs = calculateBackoffMs();
                                        Duration cooldown = Duration.ofMillis(backoffMs);

                                        if (circuitState == CircuitState.CLOSED
                                                && failures >= circuitBreakerThreshold) {
                                            log.warn(
                                                    "Circuit breaker transitioning to OPEN after {}"
                                                            + " failures",
                                                    failures);
                                            circuitState = CircuitState.OPEN;
                                            circuitOpenedAt = Instant.now();
                                        } else if (circuitState == CircuitState.HALF_OPEN) {
                                            log.warn("Circuit breaker transitioning back to OPEN");
                                            circuitState = CircuitState.OPEN;
                                            circuitOpenedAt = Instant.now();
                                        }

                                        log.error(
                                                "Registration failure (attempt {}, circuit state:"
                                                        + " {}, cooldown: {})",
                                                failures,
                                                circuitState,
                                                cooldown,
                                                t);

                                        if (minCooldownDuration.isZero()) {
                                            executor.schedule(
                                                    this::tryRegister,
                                                    backoffMs,
                                                    TimeUnit.MILLISECONDS);
                                        } else {
                                            enterCooldown(cooldown);
                                        }
                                    }
                                    return (Void) null;
                                });
    }

    private long calculateBackoffMs() {
        int failures = consecutiveFailures.get();
        if (failures == 0) {
            return registrationRetryMs;
        }

        double jitter = 1.0 + (random.nextDouble() * 2 - 1) * JITTER_FACTOR;
        long backoff =
                (long)
                        (registrationRetryMs
                                * Math.pow(backoffMultiplier, Math.min(failures - 1, 10)));
        backoff = Math.min(backoff, maxBackoffMs);
        backoff = (long) (backoff * jitter);
        backoff = Math.max(backoff, minCooldownDuration.toMillis());

        return backoff;
    }

    /**
     * Check if the Agent is currently in cooldown period.
     *
     * @return true if in cooldown, false otherwise
     */
    boolean isInCooldown() {
        synchronized (cooldownLock) {
            return cooldownUntil != null && Instant.now().isBefore(cooldownUntil);
        }
    }

    /**
     * Enter cooldown state for the specified duration.
     *
     * @param duration the cooldown duration
     */
    private void enterCooldown(Duration duration) {
        synchronized (cooldownLock) {
            if (cooldownExitTask != null) {
                cooldownExitTask.cancel(false);
            }

            cooldownUntil = Instant.now().plus(duration);
            log.debug(
                    "Entering cooldown for {} after {} consecutive failures",
                    duration,
                    consecutiveFailures.get());
            notify(RegistrationEvent.State.COOLDOWN);

            cooldownExitTask =
                    executor.schedule(
                            this::exitCooldown, duration.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** Exit cooldown state and prepare for next registration attempt. */
    private void exitCooldown() {
        synchronized (cooldownLock) {
            log.trace("Exiting cooldown, ready for next registration attempt");
            cooldownUntil = null;
            notify(RegistrationEvent.State.UNREGISTERED);
        }
    }

    /**
     * Get time remaining in cooldown period.
     *
     * @return Duration remaining, or Duration.ZERO if not in cooldown
     */
    Duration getCooldownRemaining() {
        synchronized (cooldownLock) {
            if (!isInCooldown()) {
                return Duration.ZERO;
            }
            return Duration.between(Instant.now(), cooldownUntil);
        }
    }

    /**
     * Get time since last successful registration.
     *
     * @return Duration since last success, or null if never registered
     */
    Duration getTimeSinceLastSuccess() {
        if (lastSuccessfulRegistration.equals(Instant.EPOCH)) {
            return null;
        }
        return Duration.between(lastSuccessfulRegistration, Instant.now());
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
                        })
                .exceptionally(
                        e -> {
                            log.error("Failed to update", e);
                            return null;
                        });
    }

    private Set<DiscoveryNode> defineSelf() throws UnknownHostException, URISyntaxException {
        Set<DiscoveryNode> discoveryNodes = new HashSet<>();

        long pid = ProcessHandle.current().pid();
        String javaMain = appNameResolver.extractFromJavaCommand();
        if (StringUtils.isBlank(javaMain)) {
            javaMain = System.getenv("JAVA_MAIN_CLASS");
        }
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

    void stop() {
        if (currentRegistration != null && !currentRegistration.isDone()) {
            log.trace("Cancelling in-flight registration");
            currentRegistration.cancel(true);
        }
    }

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
            COOLDOWN,
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
