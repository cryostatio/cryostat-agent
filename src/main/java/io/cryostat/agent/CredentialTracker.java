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

import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class CredentialTracker {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final Set<Integer> createdCredentials = ConcurrentHashMap.newKeySet();

    private final Queue<Integer> pendingDeletion = new ConcurrentLinkedQueue<>();

    public void trackCreated(int credentialId) {
        createdCredentials.add(credentialId);
        log.debug("Tracking credential: {}", credentialId);
    }

    public void trackDeleted(int credentialId) {
        createdCredentials.remove(credentialId);
        log.debug("Credential deleted: {}", credentialId);
    }

    public void markForDeletion(int credentialId) {
        if (createdCredentials.contains(credentialId)) {
            pendingDeletion.offer(credentialId);
            log.debug("Marked credential for deletion: {}", credentialId);
        }
    }

    public Set<Integer> getOrphanedCredentials() {
        return new HashSet<>(createdCredentials);
    }

    public Queue<Integer> getPendingDeletion() {
        return new ConcurrentLinkedQueue<>(pendingDeletion);
    }

    public void clear() {
        createdCredentials.clear();
        pendingDeletion.clear();
    }
}
