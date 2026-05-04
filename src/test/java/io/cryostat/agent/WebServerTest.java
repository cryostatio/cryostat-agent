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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.cryostat.agent.remote.RemoteContext;

import com.sun.net.httpserver.HttpServer;
import dagger.Lazy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WebServerTest {

    @Mock Lazy<Set<RemoteContext>> remoteContexts;
    @Mock Lazy<CryostatClient> cryostatClientLazy;
    @Mock Lazy<Registration> registrationLazy;
    @Mock HttpServer httpServer;
    @Mock CryostatClient cryostatClient;
    @Mock Registration registration;

    private WebServer webServer;

    @BeforeEach
    void setup() throws Exception {
        when(cryostatClientLazy.get()).thenReturn(cryostatClient);

        webServer =
                new WebServer(
                        new SecureRandom(),
                        remoteContexts,
                        cryostatClientLazy,
                        httpServer,
                        MessageDigest.getInstance("SHA-256"),
                        "testuser",
                        16,
                        registrationLazy);
    }

    @Test
    void testGenerateCredentialsCoalescesConcurrentRequests() throws Exception {
        URI callback = URI.create("http://agent.example.com:9977");
        CompletableFuture<Integer> submission = new CompletableFuture<>();

        when(cryostatClient.submitCredentialsIfRequired(anyInt(), any(), eq(callback)))
                .thenReturn(submission);

        CompletableFuture<Void> first = webServer.generateCredentials(callback);
        CompletableFuture<Void> second = webServer.generateCredentials(callback);

        assertSame(first, second, "Concurrent credential generation should share the same future");
        verify(cryostatClient, times(1)).submitCredentialsIfRequired(anyInt(), any(), eq(callback));

        submission.complete(42);

        first.get();
        second.get();

        assertEquals(
                42,
                webServer.getCredentialId(),
                "Credential ID should be updated from the shared result");
    }

    @Test
    void testGenerateCredentialsAllowsNewAttemptAfterCompletion() throws Exception {
        URI callback = URI.create("http://agent.example.com:9977");

        when(cryostatClient.submitCredentialsIfRequired(anyInt(), any(), eq(callback)))
                .thenReturn(
                        CompletableFuture.completedFuture(41),
                        CompletableFuture.completedFuture(42));

        CompletableFuture<Void> first = webServer.generateCredentials(callback);
        first.get();

        CompletableFuture<Void> second = webServer.generateCredentials(callback);
        second.get();

        assertNotSame(
                first, second, "A completed generation should not block a later fresh attempt");
        verify(cryostatClient, times(2)).submitCredentialsIfRequired(anyInt(), any(), eq(callback));
        assertEquals(
                42,
                webServer.getCredentialId(),
                "Latest completed generation should update credential ID");
    }
}
