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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Lazy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class CredentialCleanupJob {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final ScheduledExecutorService executor;
    private final CredentialTracker tracker;
    private final Lazy<CryostatClient> cryostat;
    private final Duration cleanupInterval;
    private final int maxRetries;

    private final Map<Integer, Integer> retryCount = new ConcurrentHashMap<>();
    private ScheduledFuture<?> cleanupTask;

    CredentialCleanupJob(
            ScheduledExecutorService executor,
            CredentialTracker tracker,
            Lazy<CryostatClient> cryostat,
            @Named(ConfigModule.CRYOSTAT_AGENT_CREDENTIAL_CLEANUP_INTERVAL)
                    Duration cleanupInterval,
            @Named(ConfigModule.CRYOSTAT_AGENT_CREDENTIAL_CLEANUP_MAX_RETRIES) int maxRetries) {
        this.executor = executor;
        this.tracker = tracker;
        this.cryostat = cryostat;
        this.cleanupInterval = cleanupInterval;
        this.maxRetries = maxRetries;
    }

    void start() {
        if (cleanupTask != null) {
            log.warn("Cleanup job already started");
            return;
        }
        long intervalMs = cleanupInterval.toMillis();
        cleanupTask =
                executor.scheduleAtFixedRate(
                        this::cleanupOrphanedCredentials,
                        intervalMs,
                        intervalMs,
                        TimeUnit.MILLISECONDS);
        log.debug("Credential cleanup job started with interval: {}", cleanupInterval);
    }

    void stop() {
        if (cleanupTask != null) {
            cleanupTask.cancel(false);
            cleanupTask = null;
            log.debug("Credential cleanup job stopped");
        }
    }

    void cleanupOrphanedCredentials() {
        Queue<Integer> pending = tracker.getPendingDeletion();

        if (pending.isEmpty()) {
            return;
        }

        log.debug("Cleaning up {} orphaned credentials", pending.size());

        List<CompletableFuture<Void>> deletions = new ArrayList<>();

        while (!pending.isEmpty()) {
            Integer credentialId = pending.poll();
            int attempts = retryCount.getOrDefault(credentialId, 0);

            if (attempts >= maxRetries) {
                log.error(
                        "Failed to delete credential {} after {} attempts, giving up",
                        credentialId,
                        attempts);
                retryCount.remove(credentialId);
                tracker.trackDeleted(credentialId);
                continue;
            }

            CompletableFuture<Void> deletion =
                    cryostat.get()
                            .deleteCredentials(credentialId)
                            .handle(
                                    (v, t) -> {
                                        if (t != null) {
                                            log.warn(
                                                    "Failed to delete credential {} (attempt {})",
                                                    credentialId,
                                                    attempts + 1,
                                                    t);
                                            retryCount.put(credentialId, attempts + 1);
                                            tracker.markForDeletion(credentialId);
                                        } else {
                                            log.debug(
                                                    "Successfully deleted credential {}",
                                                    credentialId);
                                            retryCount.remove(credentialId);
                                            tracker.trackDeleted(credentialId);
                                        }
                                        return null;
                                    });

            deletions.add(deletion);
        }

        CompletableFuture.allOf(deletions.toArray(new CompletableFuture[0]))
                .exceptionally(
                        t -> {
                            log.error("Error during credential cleanup", t);
                            return null;
                        })
                .join();
    }
}
