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

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import io.cryostat.agent.CryostatClient.DiscoveryPublication;
import io.cryostat.agent.WebServer.CredentialsSnapshot;
import io.cryostat.agent.model.DiscoveryNode;
import io.cryostat.agent.model.PluginInfo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryostatClientTest {

    @Mock Executor executor;
    @Mock HttpClient http;
    @Mock ClassicHttpResponse response;
    @Mock HttpEntity responseEntity;

    private final ObjectMapper mapper = new ObjectMapper();
    private CryostatClient client;
    private static final String INSTANCE_ID = "test-instance-123";
    private static final String JVM_ID = "test-jvm-456";
    private static final String APP_NAME = "test-app";
    private static final URI BASE_URI = URI.create("http://cryostat.example.com:8181");
    private static final String REALM = "test-realm";

    @BeforeEach
    void setup() {
        DiscoveryPublication discoveryPublication =
                new DiscoveryPublication("MERGE", Map.of("namespace", "test-namespace"));
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
    void testRegisterPostsAgentRegistration() throws Exception {
        URI callback = URI.create("http://agent.example.com:9977");
        AtomicReference<String> submittedRequestBody = new AtomicReference<>();

        when(http.execute(any(HttpHost.class), any(HttpPost.class)))
                .thenAnswer(
                        invocation -> {
                            HttpPost request = invocation.getArgument(1);
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            request.getEntity().writeTo(out);
                            submittedRequestBody.set(out.toString(StandardCharsets.UTF_8));
                            return response;
                        });
        when(response.getCode()).thenReturn(200);
        when(response.getEntity()).thenReturn(responseEntity);
        when(responseEntity.getContent())
                .thenReturn(
                        new StringEntity("{\"id\":\"plugin-id\",\"token\":\"token\",\"env\":[]}")
                                .getContent());

        PluginInfo pluginInfo =
                client.register(
                                callback,
                                new CredentialsSnapshot(
                                        "testuser", "testpass".getBytes(StandardCharsets.US_ASCII)),
                                List.of(discoveryNode(callback)))
                        .get();

        assertEquals("plugin-id", pluginInfo.getId());
        assertEquals("token", pluginInfo.getToken());

        ArgumentCaptor<HttpPost> requestCaptor = ArgumentCaptor.forClass(HttpPost.class);
        verify(http).execute(any(HttpHost.class), requestCaptor.capture());
        assertEquals("/api/v4/discovery/agents", requestCaptor.getValue().getUri().getPath());

        JsonNode requestBody = mapper.readTree(submittedRequestBody.get());
        assertEquals(REALM, requestBody.get("realm").asText());
        assertEquals(callback.toString(), requestBody.get("callback").asText());
        assertEquals("MERGE", requestBody.get("fillStrategy").asText());
        assertEquals("test-namespace", requestBody.get("context").get("namespace").asText());
        assertEquals("testuser", requestBody.get("credential").get("username").asText());
        assertEquals("testpass", requestBody.get("credential").get("password").asText());
        assertEquals("agent-node", requestBody.get("nodes").get(0).get("name").asText());
    }

    @Test
    void testRegisterIncludesRealmInCredentialMatchExpression() throws Exception {
        URI callback = URI.create("http://agent.example.com:9977");
        JsonNode requestBody = captureRegistrationRequest(client, callback);

        String matchExpression = requestBody.get("credential").get("matchExpression").asText();
        assertTrue(matchExpression.contains("target.connectUrl"));
        assertTrue(matchExpression.contains(callback.toString()));
        assertTrue(matchExpression.contains("target.annotations.platform[\"INSTANCE_ID\"]"));
        assertTrue(matchExpression.contains(INSTANCE_ID));
        assertTrue(matchExpression.contains("target.annotations.cryostat[\"REALM\"]"));
        assertTrue(matchExpression.contains(REALM));
    }

    @Test
    void testRegisterUsesConfiguredRealmInCredentialMatchExpression() throws Exception {
        String differentRealm = "production-realm";
        DiscoveryPublication discoveryPublication = new DiscoveryPublication("MERGE", Map.of());
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
        JsonNode requestBody = captureRegistrationRequest(clientWithDifferentRealm, callback);

        String matchExpression = requestBody.get("credential").get("matchExpression").asText();
        assertTrue(matchExpression.contains(differentRealm));
        assertFalse(matchExpression.contains(REALM));
    }

    private JsonNode captureRegistrationRequest(CryostatClient client, URI callback)
            throws Exception {
        AtomicReference<String> submittedRequestBody = new AtomicReference<>();

        when(http.execute(any(HttpHost.class), any(HttpPost.class)))
                .thenAnswer(
                        invocation -> {
                            HttpPost request = invocation.getArgument(1);
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            request.getEntity().writeTo(out);
                            submittedRequestBody.set(out.toString(StandardCharsets.UTF_8));
                            return response;
                        });
        when(response.getCode()).thenReturn(200);
        when(response.getEntity()).thenReturn(responseEntity);
        when(responseEntity.getContent())
                .thenReturn(
                        new StringEntity("{\"id\":\"plugin-id\",\"token\":\"token\",\"env\":[]}")
                                .getContent());

        client.register(
                        callback,
                        new CredentialsSnapshot(
                                "testuser", "testpass".getBytes(StandardCharsets.US_ASCII)),
                        List.of(discoveryNode(callback)))
                .get();

        return mapper.readTree(submittedRequestBody.get());
    }

    private DiscoveryNode discoveryNode(URI callback) {
        return new DiscoveryNode(
                "agent-node",
                "CryostatAgent",
                new DiscoveryNode.Target(
                        REALM,
                        callback,
                        APP_NAME,
                        INSTANCE_ID,
                        JVM_ID,
                        1234,
                        "agent.example.com",
                        9977,
                        "io.cryostat.agent.Agent",
                        1_700_000_000L));
    }
}
