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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

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
    @Mock Lazy<Registration> registrationLazy;
    @Mock HttpServer httpServer;
    @Mock Registration registration;

    private WebServer webServer;

    @BeforeEach
    void setup() throws Exception {
        webServer =
                new WebServer(
                        new SecureRandom(),
                        remoteContexts,
                        httpServer,
                        MessageDigest.getInstance("SHA-256"),
                        "testuser",
                        16,
                        registrationLazy);
    }

    @Test
    void testGenerateCredentialsCreatesSnapshot() throws Exception {
        URI callback = URI.create("http://agent.example.com:9977");

        webServer.generateCredentials(callback).get();

        WebServer.CredentialsSnapshot snapshot = webServer.getCredentialsSnapshot();

        assertEquals("testuser", snapshot.user());
        assertEquals(16, snapshot.pass().length);
        assertFalse(Arrays.equals(new byte[16], snapshot.pass()));
    }

    @Test
    void testSnapshotAfterClearPlaintextCredentialsThrows() throws Exception {
        URI callback = URI.create("http://agent.example.com:9977");

        webServer.generateCredentials(callback).get();
        webServer.clearPlaintextCredentials();

        assertThrows(IllegalStateException.class, webServer::getCredentialsSnapshot);
    }

    @Test
    void testSnapshotClearsPlaintextAndKeepsItsOwnCopy() throws Exception {
        URI callback = URI.create("http://agent.example.com:9977");

        webServer.generateCredentials(callback).get();

        WebServer.CredentialsSnapshot snapshot = webServer.getCredentialsSnapshot();
        assertFalse(Arrays.equals(new byte[16], snapshot.pass()));
        assertThrows(IllegalStateException.class, webServer::getCredentialsSnapshot);

        snapshot.close();
        assertArrayEquals(new byte[16], snapshot.pass());
    }

    @Test
    void testStoredCredentialsRemainValidWhileReplacementRegistrationIsPending() throws Exception {
        URI callback = URI.create("http://agent.example.com:9977");
        HttpServer httpServer =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        SecureRandom random = mock(SecureRandom.class);
        when(random.nextInt(anyInt())).thenReturn(0, 1);
        WebServer liveWebServer =
                new WebServer(
                        random,
                        Set::of,
                        httpServer,
                        MessageDigest.getInstance("SHA-256"),
                        "testuser",
                        16,
                        () -> registration);

        try {
            liveWebServer.generateCredentials(callback).get();
            try (WebServer.CredentialsSnapshot storedCredentials =
                    liveWebServer.getCredentialsSnapshot()) {
                liveWebServer.clearPlaintextCredentials();
                liveWebServer.commitPendingCredentials();
                liveWebServer.start();

                HttpClient client = HttpClient.newHttpClient();
                URI pingUri =
                        new URI(
                                "http",
                                null,
                                httpServer.getAddress().getHostString(),
                                httpServer.getAddress().getPort(),
                                "/",
                                null,
                                null);
                String authorization =
                        "Basic "
                                + Base64.getEncoder()
                                        .encodeToString(
                                                (storedCredentials.user()
                                                                + ":"
                                                                + new String(
                                                                        storedCredentials.pass(),
                                                                        StandardCharsets.US_ASCII))
                                                        .getBytes(StandardCharsets.US_ASCII));
                HttpRequest ping =
                        HttpRequest.newBuilder(pingUri)
                                .header("Authorization", authorization)
                                .GET()
                                .build();

                assertEquals(
                        204,
                        client.send(ping, HttpResponse.BodyHandlers.discarding()).statusCode());

                liveWebServer.generateCredentials(callback).get();
                try (WebServer.CredentialsSnapshot ignored =
                        liveWebServer.getCredentialsSnapshot()) {
                    assertEquals(
                            204,
                            client.send(ping, HttpResponse.BodyHandlers.discarding()).statusCode(),
                            "Cryostat's stored credential must remain valid until its replacement"
                                    + " is committed");
                }
            }
        } finally {
            liveWebServer.stop();
        }
    }

    @Test
    void testPendingCredentialsAreCommittedOrDiscardedAtomically() throws Exception {
        SecureRandom random = mock(SecureRandom.class);
        when(random.nextInt(anyInt())).thenReturn(0, 1);
        WebServer.Credentials credentials =
                new WebServer.Credentials(
                        random, MessageDigest.getInstance("SHA-256"), "testuser", 16);

        credentials.regenerate();
        try (WebServer.CredentialsSnapshot stored = credentials.snapshot()) {
            String storedPassword = new String(stored.pass(), StandardCharsets.US_ASCII);
            credentials.commitPending();

            credentials.regenerate();
            try (WebServer.CredentialsSnapshot replacement = credentials.snapshot()) {
                String replacementPassword =
                        new String(replacement.pass(), StandardCharsets.US_ASCII);

                assertTrue(credentials.checkUserInfo("testuser", storedPassword));
                assertTrue(credentials.checkUserInfo("testuser", replacementPassword));

                credentials.discardPending();

                assertTrue(credentials.checkUserInfo("testuser", storedPassword));
                assertFalse(credentials.checkUserInfo("testuser", replacementPassword));
            }

            credentials.regenerate();
            try (WebServer.CredentialsSnapshot replacement = credentials.snapshot()) {
                String replacementPassword =
                        new String(replacement.pass(), StandardCharsets.US_ASCII);
                credentials.commitPending();

                assertFalse(credentials.checkUserInfo("testuser", storedPassword));
                assertTrue(credentials.checkUserInfo("testuser", replacementPassword));
            }
        }
    }
}
