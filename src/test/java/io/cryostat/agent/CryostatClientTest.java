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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.cryostat.agent.CryostatClient.DiscoveryPublication;
import io.cryostat.agent.WebServer.Credentials;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryostatClientTest {

    @Mock Executor executor;
    @Mock ObjectMapper mapper;
    @Mock HttpClient http;
    @Mock ClassicHttpResponse checkResponse;
    @Mock ClassicHttpResponse submitResponse;
    @Mock HttpEntity checkEntity;
    @Mock HttpEntity submitEntity;
    @Mock Credentials credentials;

    private CryostatClient client;
    private static final String INSTANCE_ID = "test-instance-123";
    private static final String JVM_ID = "test-jvm-456";
    private static final String APP_NAME = "test-app";
    private static final URI BASE_URI = URI.create("http://cryostat.example.com:8181");
    private static final String REALM = "test-realm";

    @BeforeEach
    void setup() {
        DiscoveryPublication discoveryPublication =
                new DiscoveryPublication("MERGE", java.util.Map.of());
        client =
                new CryostatClient(
                        executor,
                        mapper,
                        http,
                        INSTANCE_ID,
                        JVM_ID,
                        APP_NAME,
                        BASE_URI,
                        REALM,
                        discoveryPublication);

        doAnswer(
                        invocation -> {
                            Runnable r = invocation.getArgument(0);
                            r.run();
                            return CompletableFuture.completedFuture(null);
                        })
                .when(executor)
                .execute(any(Runnable.class));
    }

    @Test
    void testSubmitCredentialsIncludesRealmInMatchExpression() throws Exception {
        URI callback = URI.create("http://agent.example.com:9977");

        when(http.execute(any(HttpHost.class), any(HttpPost.class)))
                .thenReturn(checkResponse, submitResponse);
        lenient().when(checkResponse.getCode()).thenReturn(404);
        lenient().when(checkResponse.getEntity()).thenReturn(checkEntity);
        lenient()
                .when(checkEntity.getContent())
                .thenReturn(new ByteArrayInputStream("{}".getBytes()));

        lenient().when(submitResponse.getCode()).thenReturn(201);
        lenient().when(submitResponse.getEntity()).thenReturn(submitEntity);
        lenient()
                .when(submitResponse.getFirstHeader("Location"))
                .thenReturn(new BasicHeader("Location", "/api/v4/credentials/42"));
        lenient()
                .when(submitEntity.getContent())
                .thenReturn(new ByteArrayInputStream("".getBytes()));

        lenient().when(credentials.user()).thenReturn("testuser");
        lenient().when(credentials.pass()).thenReturn("testpass".getBytes());

        client.submitCredentialsIfRequired(-1, credentials, callback).get();

        ArgumentCaptor<HttpPost> requestCaptor = ArgumentCaptor.forClass(HttpPost.class);
        verify(http, atLeastOnce()).execute(any(HttpHost.class), requestCaptor.capture());

        boolean foundCredentialSubmission = false;
        for (HttpPost capturedRequest : requestCaptor.getAllValues()) {
            if (capturedRequest.getUri().getPath().contains("/api/v4/credentials")
                    && !capturedRequest.getUri().getPath().contains("credential_exists")) {
                HttpEntity requestEntity = capturedRequest.getEntity();
                assertNotNull(requestEntity, "Request entity should not be null");

                String entityContent = new String(requestEntity.getContent().readAllBytes());

                assertTrue(
                        entityContent.contains("target.connectUrl"),
                        "Match expression should contain connectUrl clause");
                assertTrue(
                        entityContent.contains(callback.toString()),
                        "Match expression should contain callback URL");
                assertTrue(
                        entityContent.contains("target.annotations.platform[\"INSTANCE_ID\"]"),
                        "Match expression should contain INSTANCE_ID clause");
                assertTrue(
                        entityContent.contains(INSTANCE_ID),
                        "Match expression should contain instance ID value");
                assertTrue(
                        entityContent.contains("target.annotations.cryostat[\"REALM\"]"),
                        "Match expression should contain REALM clause");
                assertTrue(
                        entityContent.contains(REALM),
                        "Match expression should contain realm value");
                foundCredentialSubmission = true;
                break;
            }
        }

        assertTrue(foundCredentialSubmission, "Should have found credential submission request");
    }

    @Test
    void testSubmitCredentialsWithDifferentRealm() throws Exception {
        String differentRealm = "production-realm";
        DiscoveryPublication discoveryPublication =
                new DiscoveryPublication("MERGE", java.util.Map.of());
        CryostatClient clientWithDifferentRealm =
                new CryostatClient(
                        executor,
                        mapper,
                        http,
                        INSTANCE_ID,
                        JVM_ID,
                        APP_NAME,
                        BASE_URI,
                        differentRealm,
                        discoveryPublication);

        URI callback = URI.create("http://agent.example.com:9977");

        when(http.execute(any(HttpHost.class), any(HttpPost.class)))
                .thenReturn(checkResponse, submitResponse);
        lenient().when(checkResponse.getCode()).thenReturn(404);
        lenient().when(checkResponse.getEntity()).thenReturn(checkEntity);
        lenient()
                .when(checkEntity.getContent())
                .thenReturn(new ByteArrayInputStream("{}".getBytes()));

        lenient().when(submitResponse.getCode()).thenReturn(201);
        lenient().when(submitResponse.getEntity()).thenReturn(submitEntity);
        lenient()
                .when(submitResponse.getFirstHeader("Location"))
                .thenReturn(new BasicHeader("Location", "/api/v4/credentials/99"));
        lenient()
                .when(submitEntity.getContent())
                .thenReturn(new ByteArrayInputStream("".getBytes()));

        lenient().when(credentials.user()).thenReturn("testuser");
        lenient().when(credentials.pass()).thenReturn("testpass".getBytes());

        clientWithDifferentRealm.submitCredentialsIfRequired(-1, credentials, callback).get();

        ArgumentCaptor<HttpPost> requestCaptor = ArgumentCaptor.forClass(HttpPost.class);
        verify(http, atLeastOnce()).execute(any(HttpHost.class), requestCaptor.capture());

        boolean foundCredentialSubmission = false;
        for (HttpPost capturedRequest : requestCaptor.getAllValues()) {
            if (capturedRequest.getUri().getPath().contains("/api/v4/credentials")
                    && !capturedRequest.getUri().getPath().contains("credential_exists")) {
                HttpEntity requestEntity = capturedRequest.getEntity();
                String entityContent = new String(requestEntity.getContent().readAllBytes());

                assertTrue(
                        entityContent.contains(differentRealm),
                        "Match expression should contain the different realm value");
                assertFalse(
                        entityContent.contains(REALM),
                        "Match expression should not contain the original realm value");
                foundCredentialSubmission = true;
                break;
            }
        }

        assertTrue(foundCredentialSubmission, "Should have found credential submission request");
    }

    @Test
    void testRealmIsolationInMatchExpression() throws Exception {
        URI callback = URI.create("http://agent.example.com:9977");

        when(http.execute(any(HttpHost.class), any(HttpPost.class))).thenReturn(checkResponse);
        when(checkResponse.getCode()).thenReturn(200);
        when(checkResponse.getEntity()).thenReturn(checkEntity);
        when(checkEntity.getContent())
                .thenReturn(new ByteArrayInputStream("{\"id\": 42}".getBytes()));
        when(mapper.readValue(any(InputStream.class), eq(CryostatClient.StoredCredential.class)))
                .thenReturn(createStoredCredential(42));

        client.submitCredentialsIfRequired(-1, credentials, callback).get();

        ArgumentCaptor<HttpPost> requestCaptor = ArgumentCaptor.forClass(HttpPost.class);
        verify(http, atLeastOnce()).execute(any(HttpHost.class), requestCaptor.capture());

        boolean foundCredentialCheck = false;
        for (HttpPost capturedRequest : requestCaptor.getAllValues()) {
            if (capturedRequest
                    .getUri()
                    .getPath()
                    .contains("/api/beta/discovery/credential_exists")) {
                HttpEntity requestEntity = capturedRequest.getEntity();
                String entityContent = new String(requestEntity.getContent().readAllBytes());

                assertTrue(
                        entityContent.contains("target.annotations.cryostat[\"REALM\"]"),
                        "Match expression should include realm clause to prevent cross-realm"
                                + " credential reuse");
                foundCredentialCheck = true;
                break;
            }
        }

        assertTrue(foundCredentialCheck, "Should have found credential check request");
    }

    private CryostatClient.StoredCredential createStoredCredential(int id) {
        CryostatClient.StoredCredential credential = new CryostatClient.StoredCredential();
        credential.id = id;
        credential.matchExpression = "test-expression";
        return credential;
    }
}
