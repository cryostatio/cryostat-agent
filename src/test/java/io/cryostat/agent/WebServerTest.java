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

import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
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
                        false,
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
}
