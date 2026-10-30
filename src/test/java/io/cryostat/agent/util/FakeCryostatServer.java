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
package io.cryostat.agent.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Minimal stand-in for a Cryostat server, just enough for an Agent to complete registration so that
 * the credentials it generated for its own HTTP API can be captured by a test.
 */
public class FakeCryostatServer implements AutoCloseable {

    private static final String PLUGIN_INFO =
            "{\"id\":\"it-plugin\",\"token\":\"it-token\",\"env\":[]}";
    private static final String HEALTH =
            "{\"cryostatVersion\":\"4.3.0\",\"build\":{\"git\":{\"hash\":\"it\"}}}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final CompletableFuture<String[]> credentials = new CompletableFuture<>();

    public FakeCryostatServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/health", ex -> respond(ex, 200, HEALTH));
        server.createContext("/api/v4.3/discovery/agents", this::register);
        server.createContext("/api/v4/discovery", ex -> respond(ex, 200, PLUGIN_INFO));
        server.createContext("/", ex -> respond(ex, 200, "{}"));
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Blocks until an Agent registers, then returns its {username, password} credentials. */
    public String[] awaitCredentials(long timeout, TimeUnit unit) throws Exception {
        return credentials.get(timeout, unit);
    }

    private void register(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            JsonNode credential = mapper.readTree(is).get("credential");
            credentials.complete(
                    new String[] {
                        credential.get("username").asText(), credential.get("password").asText()
                    });
        }
        respond(ex, 200, PLUGIN_INFO);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
