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

import java.util.Queue;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CredentialTrackerTest {

    private CredentialTracker tracker;

    @BeforeEach
    void setup() {
        tracker = new CredentialTracker();
    }

    @Test
    void testTrackCreated() {
        tracker.trackCreated(1);
        tracker.trackCreated(2);

        Set<Integer> orphaned = tracker.getOrphanedCredentials();
        assertEquals(2, orphaned.size());
        assertTrue(orphaned.contains(1));
        assertTrue(orphaned.contains(2));
    }

    @Test
    void testTrackDeleted() {
        tracker.trackCreated(1);
        tracker.trackCreated(2);
        tracker.trackDeleted(1);

        Set<Integer> orphaned = tracker.getOrphanedCredentials();
        assertEquals(1, orphaned.size());
        assertTrue(orphaned.contains(2));
        assertFalse(orphaned.contains(1));
    }

    @Test
    void testMarkForDeletion() {
        tracker.trackCreated(1);
        tracker.markForDeletion(1);

        Queue<Integer> pending = tracker.getPendingDeletion();
        assertEquals(1, pending.size());
        assertTrue(pending.contains(1));
    }

    @Test
    void testMarkForDeletionIgnoresUntrackedCredentials() {
        tracker.markForDeletion(999);

        Queue<Integer> pending = tracker.getPendingDeletion();
        assertTrue(pending.isEmpty());
    }

    @Test
    void testClear() {
        tracker.trackCreated(1);
        tracker.trackCreated(2);
        tracker.markForDeletion(1);

        tracker.clear();

        Set<Integer> orphaned = tracker.getOrphanedCredentials();
        Queue<Integer> pending = tracker.getPendingDeletion();

        assertTrue(orphaned.isEmpty());
        assertTrue(pending.isEmpty());
    }

    @Test
    void testGetPendingDeletionReturnsSnapshot() {
        tracker.trackCreated(1);
        tracker.markForDeletion(1);

        Queue<Integer> pending1 = tracker.getPendingDeletion();
        Queue<Integer> pending2 = tracker.getPendingDeletion();

        assertNotSame(pending1, pending2);
        assertEquals(pending1.size(), pending2.size());
    }

    @Test
    void testGetOrphanedCredentialsReturnsSnapshot() {
        tracker.trackCreated(1);

        Set<Integer> orphaned1 = tracker.getOrphanedCredentials();
        Set<Integer> orphaned2 = tracker.getOrphanedCredentials();

        assertNotSame(orphaned1, orphaned2);
        assertEquals(orphaned1.size(), orphaned2.size());
    }
}
