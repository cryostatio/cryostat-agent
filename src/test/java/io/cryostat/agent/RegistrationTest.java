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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.cryostat.agent.Registration.RegistrationEvent.State;
import io.cryostat.agent.model.PluginInfo;
import io.cryostat.agent.model.ServerHealth;
import io.cryostat.agent.util.AppNameResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RegistrationTest {

    @Mock ScheduledExecutorService executor;
    @Mock CryostatClient cryostat;
    @Mock CallbackResolver callbackResolver;
    @Mock WebServer webServer;
    @Mock AppNameResolver appNameResolver;
    @Mock Random random;
    @Mock ScheduledFuture<Void> scheduledFuture;

    private Registration registration;
    private static final String INSTANCE_ID = "test-instance";
    private static final String JVM_ID = "test-jvm";
    private static final String APP_NAME = "test-app";
    private static final String REALM = "test-realm";
    private static final String HOSTNAME = "test-host";
    private static final int JMX_PORT = 9091;
    private static final int REGISTRATION_RETRY_MS = 1000;
    private static final int REGISTRATION_CHECK_MS = 5000;
    private static final int MAX_BACKOFF_MS = 300000;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final int CIRCUIT_BREAKER_THRESHOLD = 10;
    private static final Duration CIRCUIT_BREAKER_DURATION = Duration.ofMinutes(5);
    private static final Duration MIN_COOLDOWN_DURATION =
            Duration.ZERO; // Disable cooldown for existing tests
    private static final double COOLDOWN_JITTER_FACTOR = 0.2;
    private static final double RETRY_BACKOFF_JITTER_FACTOR = 0.1;
    private static final Duration MIN_REGISTRATION_INTERVAL =
            Duration.ZERO; // Disable min interval for existing tests

    @BeforeEach
    void setup() {
        registration =
                new Registration(
                        executor,
                        cryostat,
                        callbackResolver,
                        webServer,
                        appNameResolver,
                        INSTANCE_ID,
                        JVM_ID,
                        APP_NAME,
                        REALM,
                        HOSTNAME,
                        JMX_PORT,
                        REGISTRATION_RETRY_MS,
                        REGISTRATION_CHECK_MS,
                        false,
                        true,
                        MAX_BACKOFF_MS,
                        BACKOFF_MULTIPLIER,
                        CIRCUIT_BREAKER_THRESHOLD,
                        CIRCUIT_BREAKER_DURATION,
                        MIN_COOLDOWN_DURATION,
                        COOLDOWN_JITTER_FACTOR,
                        RETRY_BACKOFF_JITTER_FACTOR,
                        MIN_REGISTRATION_INTERVAL,
                        random);
        lenient()
                .when(webServer.generateCredentials(nullable(URI.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        lenient()
                .when(webServer.getCredentialsSnapshot())
                .thenReturn(
                        new WebServer.CredentialsSnapshot(
                                "user",
                                "pass".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }

    @Test
    void testExponentialBackoffCalculation() throws Exception {
        when(cryostat.serverHealth())
                .thenReturn(
                        CompletableFuture.failedFuture(new RuntimeException("Connection failed")));
        when(random.nextDouble()).thenReturn(0.5);
        when(executor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenReturn(null);

        ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);

        registration.tryRegister();
        registration.tryRegister();
        registration.tryRegister();

        verify(executor, times(3))
                .schedule(any(Runnable.class), delayCaptor.capture(), eq(TimeUnit.MILLISECONDS));

        List<Long> delays = delayCaptor.getAllValues();
        assertEquals(3, delays.size(), "Should have captured 3 delay values");

        long firstDelay = delays.get(0);
        assertTrue(
                firstDelay >= REGISTRATION_RETRY_MS * 0.9
                        && firstDelay <= REGISTRATION_RETRY_MS * 1.1,
                String.format(
                        "First delay should be close to base retry time with jitter. Expected ~%d,"
                                + " got %d",
                        REGISTRATION_RETRY_MS, firstDelay));

        long secondDelay = delays.get(1);
        long expectedSecondDelay = (long) (REGISTRATION_RETRY_MS * BACKOFF_MULTIPLIER);
        assertTrue(
                secondDelay >= expectedSecondDelay * 0.8
                        && secondDelay <= expectedSecondDelay * 1.2,
                String.format(
                        "Second delay should be approximately double the base with jitter. Expected"
                                + " ~%d, got %d",
                        expectedSecondDelay, secondDelay));

        long thirdDelay = delays.get(2);
        long expectedThirdDelay = (long) (REGISTRATION_RETRY_MS * Math.pow(BACKOFF_MULTIPLIER, 2));
        assertTrue(
                thirdDelay >= expectedThirdDelay * 0.9 && thirdDelay <= expectedThirdDelay * 1.1,
                String.format(
                        "Third delay should be approximately quadruple the base with jitter."
                                + " Expected ~%d, got %d",
                        expectedThirdDelay, thirdDelay));
    }

    @Test
    void testBackoffCappedAtMaximum() throws Exception {
        when(cryostat.serverHealth())
                .thenReturn(
                        CompletableFuture.failedFuture(new RuntimeException("Connection failed")));
        when(random.nextDouble()).thenReturn(0.5);
        when(executor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenReturn(null);

        ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);

        for (int i = 0; i < 15; i++) {
            registration.tryRegister();
        }

        verify(executor, times(15))
                .schedule(any(Runnable.class), delayCaptor.capture(), eq(TimeUnit.MILLISECONDS));

        long lastDelay = delayCaptor.getAllValues().get(14);
        assertTrue(
                lastDelay <= MAX_BACKOFF_MS * 1.1,
                "Delay should not exceed max backoff (with jitter tolerance)");
    }

    @Test
    void testCircuitBreakerOpensAfterThreshold() throws Exception {
        when(cryostat.serverHealth())
                .thenReturn(
                        CompletableFuture.failedFuture(new RuntimeException("Connection failed")));
        when(random.nextDouble()).thenReturn(0.5);
        when(executor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenReturn(null);

        for (int i = 0; i < CIRCUIT_BREAKER_THRESHOLD; i++) {
            registration.tryRegister();
        }

        registration.tryRegister();

        ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);
        verify(executor, atLeast(CIRCUIT_BREAKER_THRESHOLD + 1))
                .schedule(any(Runnable.class), delayCaptor.capture(), eq(TimeUnit.MILLISECONDS));

        long lastDelay = delayCaptor.getAllValues().get(CIRCUIT_BREAKER_THRESHOLD);
        long expectedCircuitCheckDelay = CIRCUIT_BREAKER_DURATION.toMillis() / 10;
        assertEquals(
                expectedCircuitCheckDelay,
                lastDelay,
                "When circuit is OPEN, should schedule with circuit check interval");
    }

    @Test
    void testSuccessfulRegistrationResetsFailureCount() throws Exception {
        when(random.nextDouble()).thenReturn(0.5);
        when(cryostat.serverHealth())
                .thenReturn(
                        CompletableFuture.failedFuture(new RuntimeException("Connection failed")));
        when(executor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenReturn(null);

        ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);

        for (int i = 0; i < 5; i++) {
            registration.tryRegister();
        }

        verify(executor, times(5))
                .schedule(any(Runnable.class), delayCaptor.capture(), eq(TimeUnit.MILLISECONDS));

        List<Long> delays = delayCaptor.getAllValues();

        long[] expectedDelays = {1000, 2000, 4000, 8000, 16000};
        for (int i = 0; i < 5; i++) {
            long delay = delays.get(i);
            long expected = expectedDelays[i];
            assertTrue(
                    delay >= expected * 0.9 && delay <= expected * 1.1,
                    String.format(
                            "Delay %d should be ~%d with jitter, got %d", i + 1, expected, delay));
        }
    }

    @Test
    void testJitterPreventsThunderingHerd() throws Exception {
        when(cryostat.serverHealth())
                .thenReturn(
                        CompletableFuture.failedFuture(new RuntimeException("Connection failed")));
        when(executor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenReturn(null);

        when(random.nextDouble()).thenReturn(0.0, 0.5, 1.0);

        ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);

        registration.tryRegister();
        registration.tryRegister();
        registration.tryRegister();

        verify(executor, times(3))
                .schedule(any(Runnable.class), delayCaptor.capture(), eq(TimeUnit.MILLISECONDS));

        long delay1 = delayCaptor.getAllValues().get(0);
        long delay2 = delayCaptor.getAllValues().get(1);
        long delay3 = delayCaptor.getAllValues().get(2);

        assertNotEquals(delay1, delay2, "Jitter should cause different delays");
        assertNotEquals(delay2, delay3, "Jitter should cause different delays");
    }

    @Test
    void testCircuitBreakerTransitionsToHalfOpen() throws Exception {
        when(cryostat.serverHealth())
                .thenReturn(
                        CompletableFuture.failedFuture(new RuntimeException("Connection failed")));
        when(random.nextDouble()).thenReturn(0.5);
        when(executor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenReturn(null);

        for (int i = 0; i < CIRCUIT_BREAKER_THRESHOLD; i++) {
            registration.tryRegister();
        }

        registration.tryRegister();
        verify(executor, atLeast(CIRCUIT_BREAKER_THRESHOLD + 1))
                .schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void testCalculateCooldownWithJitterAppliesVariation() {
        // Test that jitter is applied correctly
        Duration baseDuration = Duration.ofSeconds(30);

        // Test with minimum jitter (random = 0.0)
        when(random.nextDouble()).thenReturn(0.0);
        Duration minJittered = registration.calculateCooldownWithJitter(baseDuration);
        // With jitterFactor=0.2 and random=0.0: (1.0 - 0.2) + (0.0 * 0.4) = 0.8
        // Expected: 30000ms * 0.8 = 24000ms
        assertEquals(24000, minJittered.toMillis(), "Minimum jitter should be 80% of base");

        // Test with maximum jitter (random = 1.0)
        when(random.nextDouble()).thenReturn(1.0);
        Duration maxJittered = registration.calculateCooldownWithJitter(baseDuration);
        // With jitterFactor=0.2 and random=1.0: (1.0 - 0.2) + (1.0 * 0.4) = 1.2
        // Expected: 30000ms * 1.2 = 36000ms
        assertEquals(36000, maxJittered.toMillis(), "Maximum jitter should be 120% of base");

        // Test with middle jitter (random = 0.5)
        when(random.nextDouble()).thenReturn(0.5);
        Duration midJittered = registration.calculateCooldownWithJitter(baseDuration);
        // With jitterFactor=0.2 and random=0.5: (1.0 - 0.2) + (0.5 * 0.4) = 1.0
        // Expected: 30000ms * 1.0 = 30000ms
        assertEquals(30000, midJittered.toMillis(), "Middle jitter should be 100% of base");
    }

    @Test
    void testCalculateCooldownWithJitterDifferentDurations() {
        when(random.nextDouble()).thenReturn(0.5);

        // Test with 1 second
        Duration oneSecond = Duration.ofSeconds(1);
        Duration jittered1s = registration.calculateCooldownWithJitter(oneSecond);
        assertEquals(1000, jittered1s.toMillis(), "1 second with 0.5 random should remain 1000ms");

        // Test with 1 minute
        Duration oneMinute = Duration.ofMinutes(1);
        Duration jittered1m = registration.calculateCooldownWithJitter(oneMinute);
        assertEquals(
                60000, jittered1m.toMillis(), "1 minute with 0.5 random should remain 60000ms");

        // Test with 5 minutes
        Duration fiveMinutes = Duration.ofMinutes(5);
        Duration jittered5m = registration.calculateCooldownWithJitter(fiveMinutes);
        assertEquals(
                300000, jittered5m.toMillis(), "5 minutes with 0.5 random should remain 300000ms");
    }

    @Test
    void testCalculateCooldownWithJitterProducesVariation() {
        Duration baseDuration = Duration.ofSeconds(30);

        // Simulate multiple agents with different random values
        when(random.nextDouble()).thenReturn(0.1, 0.3, 0.5, 0.7, 0.9);

        Duration jitter1 = registration.calculateCooldownWithJitter(baseDuration);
        Duration jitter2 = registration.calculateCooldownWithJitter(baseDuration);
        Duration jitter3 = registration.calculateCooldownWithJitter(baseDuration);
        Duration jitter4 = registration.calculateCooldownWithJitter(baseDuration);
        Duration jitter5 = registration.calculateCooldownWithJitter(baseDuration);

        // All should be different
        assertNotEquals(
                jitter1.toMillis(),
                jitter2.toMillis(),
                "Different random values should produce different jittered durations");
        assertNotEquals(
                jitter2.toMillis(),
                jitter3.toMillis(),
                "Different random values should produce different jittered durations");
        assertNotEquals(
                jitter3.toMillis(),
                jitter4.toMillis(),
                "Different random values should produce different jittered durations");
        assertNotEquals(
                jitter4.toMillis(),
                jitter5.toMillis(),
                "Different random values should produce different jittered durations");

        // All should be within expected range (80% to 120% of base)
        long baseMs = baseDuration.toMillis();
        assertTrue(
                jitter1.toMillis() >= baseMs * 0.8 && jitter1.toMillis() <= baseMs * 1.2,
                "Jittered duration should be within range");
        assertTrue(
                jitter2.toMillis() >= baseMs * 0.8 && jitter2.toMillis() <= baseMs * 1.2,
                "Jittered duration should be within range");
        assertTrue(
                jitter3.toMillis() >= baseMs * 0.8 && jitter3.toMillis() <= baseMs * 1.2,
                "Jittered duration should be within range");
        assertTrue(
                jitter4.toMillis() >= baseMs * 0.8 && jitter4.toMillis() <= baseMs * 1.2,
                "Jittered duration should be within range");
        assertTrue(
                jitter5.toMillis() >= baseMs * 0.8 && jitter5.toMillis() <= baseMs * 1.2,
                "Jittered duration should be within range");
    }

    @Test
    void testMinimumRegistrationIntervalSchedulesRefreshingRetry() {
        Registration throttledRegistration =
                new Registration(
                        executor,
                        cryostat,
                        callbackResolver,
                        webServer,
                        appNameResolver,
                        INSTANCE_ID,
                        JVM_ID,
                        APP_NAME,
                        REALM,
                        HOSTNAME,
                        JMX_PORT,
                        REGISTRATION_RETRY_MS,
                        REGISTRATION_CHECK_MS,
                        false,
                        true,
                        MAX_BACKOFF_MS,
                        BACKOFF_MULTIPLIER,
                        CIRCUIT_BREAKER_THRESHOLD,
                        CIRCUIT_BREAKER_DURATION,
                        MIN_COOLDOWN_DURATION,
                        COOLDOWN_JITTER_FACTOR,
                        RETRY_BACKOFF_JITTER_FACTOR,
                        Duration.ofSeconds(30),
                        random);

        when(executor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenReturn(null);

        throttledRegistration.tryRegister();
        throttledRegistration.tryRegister();

        ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);
        verify(executor, atLeastOnce())
                .schedule(any(Runnable.class), delayCaptor.capture(), eq(TimeUnit.MILLISECONDS));
        long scheduledDelay =
                delayCaptor.getAllValues().stream()
                        .filter(delay -> delay >= 0 && delay <= Duration.ofSeconds(30).toMillis())
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "Expected a retry delay within the remaining"
                                                        + " minimum interval window"));
        assertTrue(
                scheduledDelay <= Duration.ofSeconds(30).toMillis(),
                "Retry delay should be scheduled within the remaining minimum interval window");
        verify(cryostat, times(1)).serverHealth();
    }

    @Test
    void testOverlappingRegistrationAttemptsAreSerialized() {
        CompletableFuture<ServerHealth> health = new CompletableFuture<>();
        when(cryostat.serverHealth()).thenReturn(health);

        registration.tryRegister();
        registration.tryRegister();

        verify(webServer, times(1)).generateCredentials(nullable(URI.class));
        verify(cryostat, times(1)).serverHealth();
    }

    @Test
    void testRegistrationFailureDoesNotCheckRemoteCredentialState() {
        when(cryostat.serverHealth())
                .thenReturn(
                        CompletableFuture.failedFuture(
                                new RuntimeException("Server health failed")));
        when(random.nextDouble()).thenReturn(0.5);
        when(executor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenReturn(null);

        registration.tryRegister();

        verify(cryostat, never()).register(any(URI.class), any(), anyCollection());
        verify(executor).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void testRegistrationFailureClearsPlaintextCredentials() {
        when(cryostat.serverHealth())
                .thenReturn(
                        CompletableFuture.failedFuture(
                                new RuntimeException("Server health failed")));
        when(random.nextDouble()).thenReturn(0.5);
        when(executor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenReturn(null);

        registration.tryRegister();

        verify(webServer).clearPlaintextCredentials();
        verify(webServer).discardPendingCredentials();
        verify(cryostat, never()).register(any(URI.class), any(), anyCollection());
        verify(executor).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void testSuccessfulRegistrationCommitsPendingCredentials() throws Exception {
        URI callback = URI.create("http://agent.example.com:9977");
        stubSuccessfulInitialRegistration(callback, "plugin-id", "plugin-token");
        runSubmittedTasksImmediately();

        registration.start();

        verify(webServer).commitPendingCredentials();
        verify(webServer, never()).discardPendingCredentials();
        verify(cryostat).activateRegistrationRefresh(callback);
        verify(cryostat, never()).refreshRegistration(any(), any());
        assertEquals("plugin-id", registration.getPluginInfo().getId());
        assertEquals("bootstrap-token", registration.getPluginInfo().getToken());
    }

    @Test
    void testInvalidPeriodicCheckRefreshesTokenWithoutReplacingCredentials() throws Exception {
        URI callback = URI.create("http://agent.example.com:9977");
        stubSuccessfulInitialRegistration(callback, "plugin-id", "expired-token");
        when(cryostat.checkRegistration(any(PluginInfo.class)))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(cryostat.refreshRegistration(eq(callback), any(PluginInfo.class)))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new PluginInfo("plugin-id", "new-token", List.of())));
        runSubmittedTasksImmediately();
        List<State> events = new ArrayList<>();
        registration.addRegistrationListener(evt -> events.add(evt.state));

        registration.start();
        ArgumentCaptor<Runnable> checkTask = ArgumentCaptor.forClass(Runnable.class);
        verify(executor)
                .scheduleAtFixedRate(
                        checkTask.capture(),
                        eq((long) REGISTRATION_CHECK_MS),
                        eq((long) REGISTRATION_CHECK_MS),
                        eq(TimeUnit.MILLISECONDS));

        checkTask.getValue().run();

        assertEquals("plugin-id", registration.getPluginInfo().getId());
        assertEquals("new-token", registration.getPluginInfo().getToken());
        ArgumentCaptor<PluginInfo> checkedPlugin = ArgumentCaptor.forClass(PluginInfo.class);
        verify(cryostat).checkRegistration(checkedPlugin.capture());
        verify(cryostat).activateRegistrationRefresh(callback);
        ArgumentCaptor<PluginInfo> refreshedPlugin = ArgumentCaptor.forClass(PluginInfo.class);
        verify(cryostat).refreshRegistration(eq(callback), refreshedPlugin.capture());
        assertEquals("bootstrap-token", checkedPlugin.getValue().getToken());
        assertEquals("bootstrap-token", refreshedPlugin.getValue().getToken());
        assertNotSame(checkedPlugin.getValue(), refreshedPlugin.getValue());
        verify(cryostat, times(1)).register(eq(callback), any(), anyCollection());
        verify(cryostat, times(1)).serverHealth();
        verify(webServer, times(1)).generateCredentials(callback);
        verify(webServer, times(1)).commitPendingCredentials();
        verify(webServer, never()).discardPendingCredentials();
        assertTrue(events.contains(State.REFRESHING));
        assertTrue(events.contains(State.REFRESHED));
        assertEquals(2, events.stream().filter(State.REFRESHED::equals).count());
    }

    @Test
    void testRetryableActivationFailurePreservesRegistrationAndRetriesActivationOnly()
            throws Exception {
        URI callback = URI.create("http://agent.example.com:9977");
        stubSuccessfulInitialRegistration(callback, "plugin-id", "initial-token");
        when(cryostat.activateRegistrationRefresh(callback))
                .thenReturn(
                        CompletableFuture.failedFuture(new HttpException(503, callback)),
                        CompletableFuture.completedFuture(
                                new PluginInfo("plugin-id", "retried-token", List.of())));
        when(random.nextDouble()).thenReturn(0.5);
        doReturn(scheduledFuture)
                .when(executor)
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        runSubmittedTasksImmediately();

        registration.start();

        assertEquals("plugin-id", registration.getPluginInfo().getId());
        assertEquals("initial-token", registration.getPluginInfo().getToken());
        verify(webServer, times(1)).generateCredentials(callback);
        verify(cryostat, times(1)).register(eq(callback), any(), anyCollection());
        verify(webServer, never()).discardPendingCredentials();

        ArgumentCaptor<Runnable> retryTask = ArgumentCaptor.forClass(Runnable.class);
        verify(executor)
                .schedule(
                        retryTask.capture(),
                        eq((long) REGISTRATION_RETRY_MS),
                        eq(TimeUnit.MILLISECONDS));
        retryTask.getValue().run();

        assertEquals("plugin-id", registration.getPluginInfo().getId());
        assertEquals("retried-token", registration.getPluginInfo().getToken());
        verify(cryostat, times(2)).activateRegistrationRefresh(callback);
        verify(cryostat, never()).refreshRegistration(any(), any());
        verify(webServer, times(1)).generateCredentials(callback);
        verify(cryostat, times(1)).register(eq(callback), any(), anyCollection());
    }

    @Test
    void testRetryableTokenRefreshFailurePreservesRegistrationAndRetriesRefreshOnly()
            throws Exception {
        URI callback = URI.create("http://agent.example.com:9977");
        stubSuccessfulInitialRegistration(callback, "plugin-id", "initial-token");
        when(cryostat.refreshRegistration(eq(callback), any(PluginInfo.class)))
                .thenReturn(
                        CompletableFuture.failedFuture(new HttpException(503, callback)),
                        CompletableFuture.completedFuture(
                                new PluginInfo("plugin-id", "retried-token", List.of())));
        when(random.nextDouble()).thenReturn(0.5);
        doReturn(scheduledFuture)
                .when(executor)
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        runSubmittedTasksImmediately();

        registration.start();
        registration.notify(State.REFRESHING);

        assertEquals("plugin-id", registration.getPluginInfo().getId());
        assertEquals("bootstrap-token", registration.getPluginInfo().getToken());
        verify(webServer, times(1)).generateCredentials(callback);
        verify(cryostat, times(1)).register(eq(callback), any(), anyCollection());
        verify(webServer, never()).discardPendingCredentials();

        ArgumentCaptor<Runnable> retryTask = ArgumentCaptor.forClass(Runnable.class);
        verify(executor)
                .schedule(
                        retryTask.capture(),
                        eq((long) REGISTRATION_RETRY_MS),
                        eq(TimeUnit.MILLISECONDS));
        retryTask.getValue().run();

        assertEquals("plugin-id", registration.getPluginInfo().getId());
        assertEquals("retried-token", registration.getPluginInfo().getToken());
        verify(cryostat).activateRegistrationRefresh(callback);
        verify(cryostat, times(2)).refreshRegistration(eq(callback), any(PluginInfo.class));
        verify(webServer, times(1)).generateCredentials(callback);
        verify(cryostat, times(1)).register(eq(callback), any(), anyCollection());
    }

    @Test
    void testRepeatedRetryableRefreshFailuresFallBackToFullRegistration() throws Exception {
        URI callback = URI.create("http://agent.example.com:9977");
        Registration recoveringRegistration =
                new Registration(
                        executor,
                        cryostat,
                        callbackResolver,
                        webServer,
                        appNameResolver,
                        INSTANCE_ID,
                        JVM_ID,
                        APP_NAME,
                        REALM,
                        HOSTNAME,
                        JMX_PORT,
                        REGISTRATION_RETRY_MS,
                        REGISTRATION_CHECK_MS,
                        false,
                        true,
                        MAX_BACKOFF_MS,
                        BACKOFF_MULTIPLIER,
                        2,
                        Duration.ZERO,
                        MIN_COOLDOWN_DURATION,
                        COOLDOWN_JITTER_FACTOR,
                        RETRY_BACKOFF_JITTER_FACTOR,
                        MIN_REGISTRATION_INTERVAL,
                        random);
        when(callbackResolver.determineSelfCallback()).thenReturn(callback);
        when(appNameResolver.extractFromJavaCommand()).thenReturn("test.Main");
        when(cryostat.serverHealth()).thenReturn(CompletableFuture.completedFuture(serverHealth()));
        when(cryostat.register(eq(callback), any(), anyCollection()))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new PluginInfo("plugin-id", "initial-token", List.of())),
                        CompletableFuture.completedFuture(
                                new PluginInfo("recovered-id", "recovered-token", List.of())));
        when(cryostat.activateRegistrationRefresh(callback))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new PluginInfo("plugin-id", "bootstrap-token", List.of())),
                        CompletableFuture.completedFuture(
                                new PluginInfo(
                                        "recovered-id", "recovered-bootstrap-token", List.of())));
        when(cryostat.refreshRegistration(eq(callback), any(PluginInfo.class)))
                .thenReturn(
                        CompletableFuture.failedFuture(new RuntimeException("connection reset")),
                        CompletableFuture.failedFuture(new RuntimeException("connection reset")));
        when(random.nextDouble()).thenReturn(0.5);
        List<Runnable> scheduledTasks = new ArrayList<>();
        doAnswer(
                        invocation -> {
                            scheduledTasks.add(invocation.getArgument(0, Runnable.class));
                            return scheduledFuture;
                        })
                .when(executor)
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        runSubmittedTasksImmediately();

        recoveringRegistration.start();
        recoveringRegistration.notify(State.REFRESHING);

        assertEquals("plugin-id", recoveringRegistration.getPluginInfo().getId());
        assertEquals(1, scheduledTasks.size());
        scheduledTasks.remove(0).run();

        assertFalse(recoveringRegistration.getPluginInfo().isInitialized());
        assertEquals(1, scheduledTasks.size());
        scheduledTasks.remove(0).run();

        assertEquals("recovered-id", recoveringRegistration.getPluginInfo().getId());
        assertEquals(
                "recovered-bootstrap-token", recoveringRegistration.getPluginInfo().getToken());
        verify(callbackResolver, times(2)).determineSelfCallback();
        verify(cryostat, times(2)).refreshRegistration(eq(callback), any(PluginInfo.class));
        verify(cryostat, times(2)).register(eq(callback), any(), anyCollection());
        verify(webServer, times(2)).generateCredentials(callback);
        verify(webServer, times(2)).commitPendingCredentials();
    }

    @Test
    void testTerminalRefreshFailureFallsBackToFullRecoveryRegistration() throws Exception {
        URI callback = URI.create("http://agent.example.com:9977");
        when(callbackResolver.determineSelfCallback()).thenReturn(callback);
        when(appNameResolver.extractFromJavaCommand()).thenReturn("test.Main");
        when(cryostat.serverHealth()).thenReturn(CompletableFuture.completedFuture(serverHealth()));
        when(cryostat.register(eq(callback), any(), anyCollection()))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new PluginInfo("plugin-id", "expired-token", List.of())),
                        CompletableFuture.completedFuture(
                                new PluginInfo("recovered-id", "recovered-token", List.of())));
        when(cryostat.checkRegistration(any(PluginInfo.class)))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(cryostat.activateRegistrationRefresh(callback))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new PluginInfo("plugin-id", "bootstrap-token", List.of())),
                        CompletableFuture.completedFuture(
                                new PluginInfo(
                                        "recovered-id", "recovered-bootstrap-token", List.of())));
        when(cryostat.refreshRegistration(eq(callback), any(PluginInfo.class)))
                .thenReturn(CompletableFuture.failedFuture(new HttpException(400, callback)));
        when(random.nextDouble()).thenReturn(0.5);
        runSubmittedTasksImmediately();

        registration.start();
        ArgumentCaptor<Runnable> checkTask = ArgumentCaptor.forClass(Runnable.class);
        verify(executor)
                .scheduleAtFixedRate(
                        checkTask.capture(),
                        eq((long) REGISTRATION_CHECK_MS),
                        eq((long) REGISTRATION_CHECK_MS),
                        eq(TimeUnit.MILLISECONDS));
        checkTask.getValue().run();

        assertFalse(registration.getPluginInfo().isInitialized());
        ArgumentCaptor<Runnable> recoveryTask = ArgumentCaptor.forClass(Runnable.class);
        verify(executor)
                .schedule(
                        recoveryTask.capture(),
                        eq((long) REGISTRATION_RETRY_MS),
                        eq(TimeUnit.MILLISECONDS));
        recoveryTask.getValue().run();

        assertEquals("recovered-id", registration.getPluginInfo().getId());
        assertEquals("recovered-bootstrap-token", registration.getPluginInfo().getToken());
        verify(cryostat, times(2)).activateRegistrationRefresh(callback);
        verify(cryostat).refreshRegistration(eq(callback), any(PluginInfo.class));
        verify(cryostat, times(2)).register(eq(callback), any(), anyCollection());
        verify(webServer, times(2)).generateCredentials(callback);
        verify(webServer, times(2)).commitPendingCredentials();
        verify(webServer).discardPendingCredentials();
    }

    @Test
    void testOverlappingRefreshAttemptsAreSerialized() throws Exception {
        URI callback = URI.create("http://agent.example.com:9977");
        stubSuccessfulInitialRegistration(callback, "plugin-id", "old-token");
        runSubmittedTasksImmediately();
        registration.start();

        CompletableFuture<PluginInfo> refresh = new CompletableFuture<>();
        when(cryostat.refreshRegistration(eq(callback), any(PluginInfo.class))).thenReturn(refresh);

        registration.notify(State.REFRESHING);
        registration.notify(State.REFRESHING);

        verify(cryostat, times(1)).refreshRegistration(eq(callback), any(PluginInfo.class));
        verify(webServer, times(1)).generateCredentials(callback);
        verify(cryostat, times(1)).register(eq(callback), any(), anyCollection());

        refresh.complete(new PluginInfo("plugin-id", "new-token", List.of()));
        assertEquals("new-token", registration.getPluginInfo().getToken());
    }

    @Test
    void testRegistrationFailureSchedulesRetryWhenServerHealthFails() {
        when(cryostat.serverHealth())
                .thenReturn(
                        CompletableFuture.failedFuture(
                                new RuntimeException("Server health failed")));
        when(random.nextDouble()).thenReturn(0.5);
        when(executor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenReturn(null);

        registration.tryRegister();

        verify(cryostat, never()).register(any(URI.class), any(), anyCollection());
        verify(executor).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void testCooldownExitDoesNotStartDuplicateRegistrationAttempt() {
        Registration cooldownRegistration =
                new Registration(
                        executor,
                        cryostat,
                        callbackResolver,
                        webServer,
                        appNameResolver,
                        INSTANCE_ID,
                        JVM_ID,
                        APP_NAME,
                        REALM,
                        HOSTNAME,
                        JMX_PORT,
                        REGISTRATION_RETRY_MS,
                        REGISTRATION_CHECK_MS,
                        false,
                        true,
                        MAX_BACKOFF_MS,
                        BACKOFF_MULTIPLIER,
                        CIRCUIT_BREAKER_THRESHOLD,
                        CIRCUIT_BREAKER_DURATION,
                        Duration.ofSeconds(1),
                        COOLDOWN_JITTER_FACTOR,
                        RETRY_BACKOFF_JITTER_FACTOR,
                        MIN_REGISTRATION_INTERVAL,
                        random);

        when(cryostat.serverHealth())
                .thenReturn(
                        CompletableFuture.failedFuture(
                                new RuntimeException("Server health failed")));
        when(random.nextDouble()).thenReturn(0.5, 0.5);
        when(webServer.performCleanup(cooldownRegistration))
                .thenReturn(CompletableFuture.completedFuture(null));

        cooldownRegistration.tryRegister();

        ArgumentCaptor<Runnable> cooldownExitCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor)
                .schedule(cooldownExitCaptor.capture(), anyLong(), eq(TimeUnit.MILLISECONDS));
        cooldownExitCaptor.getValue().run();

        verify(webServer).exitCooldownMode();
        verify(cryostat, times(1)).serverHealth();
    }

    private void stubSuccessfulInitialRegistration(
            URI callback, String pluginId, String pluginToken) throws Exception {
        when(callbackResolver.determineSelfCallback()).thenReturn(callback);
        when(appNameResolver.extractFromJavaCommand()).thenReturn("test.Main");
        when(cryostat.serverHealth()).thenReturn(CompletableFuture.completedFuture(serverHealth()));
        when(cryostat.register(eq(callback), any(), anyCollection()))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new PluginInfo(pluginId, pluginToken, List.of())));
        lenient()
                .when(cryostat.activateRegistrationRefresh(callback))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new PluginInfo(pluginId, "bootstrap-token", List.of())));
    }

    private ServerHealth serverHealth() {
        return new ServerHealth(
                "4.3.0", new ServerHealth.BuildInfo(new ServerHealth.GitInfo("test-hash")));
    }

    private void runSubmittedTasksImmediately() {
        doAnswer(
                        invocation -> {
                            invocation.getArgument(0, Runnable.class).run();
                            return scheduledFuture;
                        })
                .when(executor)
                .submit(any(Runnable.class));
    }
}
