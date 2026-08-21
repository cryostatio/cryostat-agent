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
import java.util.HashSet;
import java.util.Objects;
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
    private final double cooldownJitterFactor;
    private final double retryBackoffJitterFactor;
    private final Duration minRegistrationInterval;
    private final Random random;

    private final PluginInfo pluginInfo = new PluginInfo();
    private final Object pluginInfoLock = new Object();
    private final Set<Consumer<RegistrationEvent>> listeners = new HashSet<>();
    private volatile boolean refreshCallbacksEnabled;

    private volatile URI callback;
    private ScheduledFuture<?> registrationCheckTask;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile CircuitState circuitState = CircuitState.CLOSED;
    private volatile Instant circuitOpenedAt = null;

    private volatile CompletableFuture<?> currentRegistration = null;

    private volatile Instant lastSuccessfulRegistration = Instant.EPOCH;
    private volatile Instant cooldownUntil = null;
    private volatile ScheduledFuture<?> cooldownExitTask = null;
    private final Object cooldownLock = new Object();

    private volatile Instant lastRegistrationAttempt = Instant.MIN;
    private final Object registrationLock = new Object();

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
            AppNameResolver appNameResolver,
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
            double cooldownJitterFactor,
            double retryBackoffJitterFactor,
            Duration minRegistrationInterval,
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
        this.cooldownJitterFactor = cooldownJitterFactor;
        this.retryBackoffJitterFactor = retryBackoffJitterFactor;
        this.minRegistrationInterval = minRegistrationInterval;
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
                                break;
                            }
                            notify(RegistrationEvent.State.REFRESHING);
                            break;
                        case REGISTERED:
                            if (this.registrationCheckTask != null) {
                                log.warn("Re-registered without previous de-registration");
                                this.registrationCheckTask.cancel(false);
                            }
                            this.registrationCheckTask =
                                    executor.scheduleAtFixedRate(
                                            () -> {
                                                PluginInfo registeredPlugin = pluginInfoSnapshot();
                                                cryostat.checkRegistration(registeredPlugin)
                                                        .handle(
                                                                (v, t) -> {
                                                                    if (t != null
                                                                            || !Boolean.TRUE.equals(
                                                                                    v)) {
                                                                        notify(
                                                                                RegistrationEvent
                                                                                        .State
                                                                                        .REFRESHING);
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

    /**
     * Determine when the next registration attempt is allowed. This prevents rapid-fire
     * registration attempts from external triggers.
     *
     * @return the instant when registration may next be attempted
     */
    private Instant shouldAttemptRegistrationAt() {
        synchronized (registrationLock) {
            Instant now = Instant.now();
            Instant nextAllowed = lastRegistrationAttempt.plus(minRegistrationInterval);

            if (now.isBefore(nextAllowed)) {
                Duration remaining = Duration.between(now, nextAllowed);
                log.debug(
                        "Skipping registration attempt - minimum interval not met. Last attempt:"
                                + " {}, next allowed: {} (in {})",
                        lastRegistrationAttempt,
                        nextAllowed,
                        remaining);
                return nextAllowed;
            }

            lastRegistrationAttempt = now;
            return now;
        }
    }

    synchronized void tryRegister() {
        if (currentRegistration != null && !currentRegistration.isDone()) {
            log.debug("Registration attempt already in progress");
            return;
        }

        Instant shouldAttemptRegistrationAt = shouldAttemptRegistrationAt();
        if (Instant.now().isBefore(shouldAttemptRegistrationAt)) {
            long delay = Duration.between(Instant.now(), shouldAttemptRegistrationAt).toMillis();
            executor.schedule(
                    () -> notify(RegistrationEvent.State.REFRESHING), delay, TimeUnit.MILLISECONDS);
            return;
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

        PluginInfo registeredPlugin = pluginInfoSnapshot();
        if (!registeredPlugin.isInitialized()) {
            currentRegistration = registerAgent();
        } else if (!refreshCallbacksEnabled) {
            currentRegistration = activateRegistrationRefresh();
        } else {
            currentRegistration = refreshRegistration(registeredPlugin);
        }
    }

    private CompletableFuture<Void> registerAgent() {
        return webServer
                .generateCredentials(callback)
                .thenCompose(v -> cryostat.serverHealth())
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
                                            "Cryostat server version {} is outside of expected"
                                                    + " range [{}, {})",
                                            cryostatSemver,
                                            version.getServerMin(),
                                            version.getServerMax());
                                }
                            } catch (IOException ioe) {
                                log.error("Unable to read versions.properties file", ioe);
                            }

                            try (var credentials = webServer.getCredentialsSnapshot()) {
                                Set<DiscoveryNode> selfNodes = defineSelf();
                                log.trace(
                                        "registering and publishing self as {}",
                                        selfNodes.stream()
                                                .map(n -> n.getTarget().getConnectUrl())
                                                .collect(Collectors.toList()));
                                return cryostat.register(callback, credentials, selfNodes);
                            } catch (UnknownHostException | URISyntaxException e) {
                                return CompletableFuture.failedFuture(e);
                            }
                        })
                .whenComplete((plugin, t) -> webServer.clearPlaintextCredentials())
                .handle(
                        (plugin, t) -> {
                            if (t != null) {
                                return completeRegistrationFailure(t);
                            }
                            if (!isValidRegistration(plugin)) {
                                return completeRegistrationFailure(
                                        new IllegalStateException(
                                                "agent registration returned incomplete plugin"
                                                        + " information"));
                            }

                            // Cryostat has acknowledged these credentials, so promote them before
                            // enabling its generic refresh callbacks. If that second request fails,
                            // the acknowledged credentials must remain valid.
                            webServer.commitPendingCredentials();
                            copyPluginInfo(plugin);
                            this.refreshCallbacksEnabled = false;
                            log.debug("Registered as {}", plugin.getId());
                            completeRegistrationSuccess();
                            notify(RegistrationEvent.State.REGISTERED);
                            notify(RegistrationEvent.State.PUBLISHED);

                            // The Agent endpoint initially schedules GET health pings. Registering
                            // the same callback through the generic endpoint reuses this plugin and
                            // changes its existing job to POST token-refresh prompts without
                            // replacing credentials or publishing discovery nodes.
                            return activateRegistrationRefresh();
                        })
                .thenCompose(f -> f);
    }

    private CompletableFuture<Void> activateRegistrationRefresh() {
        return cryostat.activateRegistrationRefresh(callback)
                .handle(
                        (plugin, t) -> {
                            if (t != null) {
                                if (isRetryableRefreshFailure(t)) {
                                    return completeRefreshFailure(t);
                                }
                                return completeRegistrationFailure(t);
                            }
                            if (!updatePluginInfoToken(plugin)) {
                                return completeRegistrationFailure(
                                        new IllegalStateException(
                                                "registration refresh activation returned"
                                                        + " unexpected plugin information"));
                            }

                            this.refreshCallbacksEnabled = true;
                            log.debug(
                                    "Enabled registration refresh callbacks for {}",
                                    plugin.getId());
                            completeRegistrationSuccess();
                            notify(RegistrationEvent.State.REFRESHED);
                            return CompletableFuture.<Void>completedFuture(null);
                        })
                .thenCompose(f -> f);
    }

    private CompletableFuture<Void> refreshRegistration(PluginInfo registeredPlugin) {
        return cryostat.refreshRegistration(callback, registeredPlugin)
                .handle(
                        (plugin, t) -> {
                            if (t != null) {
                                if (isRetryableRefreshFailure(t)) {
                                    return completeRefreshFailure(t);
                                }
                                return completeRegistrationFailure(t);
                            }
                            if (!updatePluginInfoToken(plugin)) {
                                return completeRegistrationFailure(
                                        new IllegalStateException(
                                                "registration refresh returned no token"));
                            }

                            log.debug("Refreshed registration token for {}", plugin.getId());
                            completeRegistrationSuccess();
                            notify(RegistrationEvent.State.REFRESHED);
                            return CompletableFuture.<Void>completedFuture(null);
                        })
                .thenCompose(f -> f);
    }

    private boolean isValidRegistration(PluginInfo plugin) {
        return plugin != null
                && StringUtils.isNotBlank(plugin.getId())
                && StringUtils.isNotBlank(plugin.getToken());
    }

    private boolean updatePluginInfoToken(PluginInfo plugin) {
        if (!isValidRegistration(plugin)) {
            return false;
        }
        synchronized (pluginInfoLock) {
            if (!Objects.equals(pluginInfo.getId(), plugin.getId())) {
                return false;
            }
            pluginInfo.setToken(plugin.getToken());
            return true;
        }
    }

    private void copyPluginInfo(PluginInfo plugin) {
        synchronized (pluginInfoLock) {
            pluginInfo.copyFrom(plugin);
        }
    }

    private void clearPluginInfo() {
        synchronized (pluginInfoLock) {
            pluginInfo.clear();
        }
    }

    private PluginInfo pluginInfoSnapshot() {
        synchronized (pluginInfoLock) {
            PluginInfo snapshot = new PluginInfo();
            snapshot.copyFrom(pluginInfo);
            return snapshot;
        }
    }

    private boolean isRetryableRefreshFailure(Throwable t) {
        Throwable cause = t;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (!(cause instanceof HttpException)) {
            return true;
        }
        HttpException httpException = (HttpException) cause;
        int statusCode = httpException.statusCode();
        return statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500;
    }

    private void completeRegistrationSuccess() {
        lastSuccessfulRegistration = Instant.now();
        consecutiveFailures.set(0);

        if (circuitState == CircuitState.HALF_OPEN) {
            log.debug("Circuit breaker transitioning to CLOSED");
        }
        circuitState = CircuitState.CLOSED;

        log.debug(
                "Registration successful at {}, next attempt allowed after {}",
                lastSuccessfulRegistration,
                lastSuccessfulRegistration.plus(minCooldownDuration));
    }

    private CompletableFuture<Void> completeRegistrationFailure(Throwable t) {
        return completeRegistrationFailure(t, consecutiveFailures.incrementAndGet());
    }

    private CompletableFuture<Void> completeRegistrationFailure(Throwable t, int failures) {
        webServer.discardPendingCredentials();
        this.refreshCallbacksEnabled = false;
        clearPluginInfo();

        long backoffMs = calculateBackoffMs(failures, true);
        Duration cooldown = Duration.ofMillis(backoffMs);

        updateCircuitAfterFailure(failures);

        log.error(
                "Registration failure (attempt {}, circuit state: {}, cooldown: {})",
                failures,
                circuitState,
                cooldown,
                t);

        if (minCooldownDuration.isZero()) {
            executor.schedule(
                    () -> notify(RegistrationEvent.State.UNREGISTERED),
                    backoffMs,
                    TimeUnit.MILLISECONDS);
        } else {
            enterCooldown(cooldown);
        }
        return CompletableFuture.completedFuture(null);
    }

    private void updateCircuitAfterFailure(int failures) {
        if (circuitState == CircuitState.CLOSED && failures >= circuitBreakerThreshold) {
            log.warn("Circuit breaker transitioning to OPEN after {} failures", failures);
            circuitState = CircuitState.OPEN;
            circuitOpenedAt = Instant.now();
        } else if (circuitState == CircuitState.HALF_OPEN) {
            log.warn("Circuit breaker transitioning back to OPEN");
            circuitState = CircuitState.OPEN;
            circuitOpenedAt = Instant.now();
        }
    }

    private CompletableFuture<Void> completeRefreshFailure(Throwable t) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= circuitBreakerThreshold) {
            log.warn(
                    "Registration refresh failed {} consecutive times, falling back to full"
                            + " registration",
                    failures);
            return completeRegistrationFailure(t, failures);
        }

        long backoffMs = calculateBackoffMs(failures, false);

        updateCircuitAfterFailure(failures);
        log.warn(
                "Registration refresh failure (attempt {}, circuit state: {}, retry in {} ms)",
                failures,
                circuitState,
                backoffMs,
                t);
        executor.schedule(
                () -> notify(RegistrationEvent.State.REFRESHING), backoffMs, TimeUnit.MILLISECONDS);
        return CompletableFuture.completedFuture(null);
    }

    private long calculateBackoffMs(int failures, boolean applyCooldownFloor) {
        if (failures == 0) {
            return registrationRetryMs;
        }

        double jitter = 1.0 + (random.nextDouble() * 2 - 1) * retryBackoffJitterFactor;
        long backoff =
                (long)
                        (registrationRetryMs
                                * Math.pow(backoffMultiplier, Math.min(failures - 1, 10)));
        backoff = Math.min(backoff, maxBackoffMs);
        backoff = (long) (backoff * jitter);
        if (applyCooldownFloor) {
            backoff = Math.max(backoff, minCooldownDuration.toMillis());
        }

        return backoff;
    }

    /**
     * Calculate cooldown duration with jitter to prevent thundering herd problem. Adds random
     * variation based on the configured jitter factor to the base duration so that multiple agents
     * don't all exit cooldown simultaneously.
     *
     * @param baseDuration Base cooldown duration (e.g., PT30S)
     * @return Duration with jitter applied
     */
    Duration calculateCooldownWithJitter(Duration baseDuration) {
        long baseMs = baseDuration.toMillis();
        // Add jitter: range is (1 - jitterFactor) to (1 + jitterFactor) times base duration
        // For jitterFactor=0.2, this gives 0.8x to 1.2x base duration
        double jitterRange = cooldownJitterFactor * 2;
        double jitterFactor = (1.0 - cooldownJitterFactor) + (random.nextDouble() * jitterRange);
        long jitteredMs = (long) (baseMs * jitterFactor);
        return Duration.ofMillis(jitteredMs);
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

            Duration jitteredDuration = calculateCooldownWithJitter(duration);
            cooldownUntil = Instant.now().plus(jitteredDuration);
            log.debug(
                    "Entering cooldown for {} (base: {}) after {} consecutive failures",
                    jitteredDuration,
                    duration,
                    consecutiveFailures.get());
            notify(RegistrationEvent.State.COOLDOWN);

            webServer
                    .performCleanup(this)
                    .thenRun(
                            () -> {
                                log.trace("Cleanup complete, WebServer entering cooldown mode");
                                webServer.enterCooldownMode();
                            })
                    .exceptionally(
                            t -> {
                                log.warn(
                                        "Cleanup failed, WebServer entering cooldown mode anyway",
                                        t);
                                webServer.enterCooldownMode();
                                return null;
                            });

            cooldownExitTask =
                    executor.schedule(
                            this::exitCooldown, jitteredDuration.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** Exit cooldown state and prepare for next registration attempt. */
    private void exitCooldown() {
        synchronized (cooldownLock) {
            log.trace("Exiting cooldown, ready for next registration attempt");
            cooldownUntil = null;

            webServer.exitCooldownMode();

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
                new DiscoveryNode(appName + "-agent-" + instanceId, AGENT_NODE_TYPE, httpSelf));

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
                    new DiscoveryNode(appName + "-jmx-" + instanceId, JMX_NODE_TYPE, jmxSelf));
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
        PluginInfo registeredPlugin = pluginInfoSnapshot();
        if (!registeredPlugin.isInitialized()) {
            log.warn("Deregistration requested before registration complete!");
            return CompletableFuture.completedFuture(null);
        }
        return cryostat.deregister(registeredPlugin)
                .handle(
                        (n, t) -> {
                            if (t != null) {
                                log.warn(
                                        "Failed to deregister as Cryostat discovery plugin [{}]",
                                        registeredPlugin.getId());
                            } else {
                                log.debug(
                                        "Deregistered from Cryostat discovery plugin [{}]",
                                        registeredPlugin.getId());
                            }
                            this.refreshCallbacksEnabled = false;
                            clearPluginInfo();
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
        return pluginInfoSnapshot();
    }
}
