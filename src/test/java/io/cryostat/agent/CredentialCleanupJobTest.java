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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import dagger.Lazy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CredentialCleanupJobTest {

    @Mock ScheduledExecutorService executor;
    @Mock CredentialTracker tracker;
    @Mock Lazy<CryostatClient> cryostat;
    @Mock CryostatClient cryostatClient;
    @Mock ScheduledFuture<Object> scheduledFuture;

    private CredentialCleanupJob cleanupJob;
    private static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(1);
    private static final int MAX_RETRIES = 5;

    @BeforeEach
    void setup() {
        lenient().when(cryostat.get()).thenReturn(cryostatClient);
        cleanupJob =
                new CredentialCleanupJob(
                        executor, tracker, cryostat, CLEANUP_INTERVAL, MAX_RETRIES);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testStartSchedulesCleanupJob() {
        when(executor.scheduleAtFixedRate(
                        any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn((ScheduledFuture) scheduledFuture);

        cleanupJob.start();

        verify(executor)
                .scheduleAtFixedRate(
                        any(Runnable.class),
                        eq(CLEANUP_INTERVAL.toMillis()),
                        eq(CLEANUP_INTERVAL.toMillis()),
                        eq(TimeUnit.MILLISECONDS));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testStopCancelsScheduledTask() {
        when(executor.scheduleAtFixedRate(
                        any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn((ScheduledFuture) scheduledFuture);

        cleanupJob.start();
        cleanupJob.stop();

        verify(scheduledFuture).cancel(false);
    }

    @Test
    void testCleanupOrphanedCredentialsWithNoPendingDeletions() {
        when(tracker.getPendingDeletion())
                .thenReturn(new java.util.concurrent.ConcurrentLinkedQueue<>());

        cleanupJob.cleanupOrphanedCredentials();

        verify(cryostatClient, never()).deleteCredentials(anyInt());
    }

    @Test
    void testCleanupOrphanedCredentialsSuccessfulDeletion() {
        java.util.Queue<Integer> pending = new java.util.concurrent.ConcurrentLinkedQueue<>();
        pending.offer(1);
        pending.offer(2);
        when(tracker.getPendingDeletion()).thenReturn(pending);
        when(cryostatClient.deleteCredentials(anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));

        cleanupJob.cleanupOrphanedCredentials();

        verify(cryostatClient).deleteCredentials(1);
        verify(cryostatClient).deleteCredentials(2);
        verify(tracker, times(2)).trackDeleted(anyInt());
    }

    @Test
    void testCleanupOrphanedCredentialsFailedDeletion() {
        java.util.Queue<Integer> pending = new java.util.concurrent.ConcurrentLinkedQueue<>();
        pending.offer(1);
        when(tracker.getPendingDeletion()).thenReturn(pending);
        when(cryostatClient.deleteCredentials(1))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Delete failed")));

        cleanupJob.cleanupOrphanedCredentials();

        verify(cryostatClient).deleteCredentials(1);
        verify(tracker).markForDeletion(1);
        verify(tracker, never()).trackDeleted(1);
    }

    @Test
    void testCleanupOrphanedCredentialsGivesUpAfterMaxRetries() {
        java.util.Queue<Integer> pending = new java.util.concurrent.ConcurrentLinkedQueue<>();
        for (int i = 0; i < MAX_RETRIES + 1; i++) {
            pending.offer(1);
        }
        when(tracker.getPendingDeletion()).thenReturn(pending);
        when(cryostatClient.deleteCredentials(1))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Delete failed")));

        for (int i = 0; i <= MAX_RETRIES; i++) {
            cleanupJob.cleanupOrphanedCredentials();
        }

        verify(cryostatClient, times(MAX_RETRIES)).deleteCredentials(1);
        verify(tracker, times(1)).trackDeleted(1);
    }
}
