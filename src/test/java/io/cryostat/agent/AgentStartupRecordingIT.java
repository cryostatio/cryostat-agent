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

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.cryostat.agent.util.FakeCryostatServer;
import io.cryostat.agent.util.ProcessTestHelper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Starts a JVM with a {@code -XX:StartFlightRecording} recording bound to a directory and the Agent
 * attached, then checks that the Agent still serves the recording after the JVM has stopped and
 * closed it. Newer JDKs close such a recording as soon as its duration elapses, after which it is
 * no longer visible to JFR or JMX. The Agent must therefore find the recording early and link it to
 * the dump file the JVM wrote.
 */
class AgentStartupRecordingIT {

    private static final String RECORDING_NAME = "startup";
    private static final int RECORDING_DURATION_SECONDS = 8;
    private static final long INITIAL_SCAN_DELAY_MS = 1500;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    FakeCryostatServer cryostat;
    Path dumpDirectory;
    Process dummyAppWithAgent;
    StringBuilder dummyStdout = new StringBuilder();
    StringBuilder dummyStderr = new StringBuilder();

    @BeforeEach
    void setup() throws IOException {
        cryostat = new FakeCryostatServer();
        dumpDirectory = Files.createTempDirectory("cryostat-agent-it-");
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dummyAppWithAgent != null) {
            dummyAppWithAgent.destroyForcibly();
            dummyAppWithAgent.waitFor(2, TimeUnit.SECONDS);
        }
        cryostat.close();
        try (Stream<Path> files = Files.list(dumpDirectory)) {
            files.forEach(f -> f.toFile().delete());
        }
        Files.deleteIfExists(dumpDirectory);
        ProcessTestHelper.printStreams(getClass(), dummyStdout, dummyStderr);
    }

    @Test
    void testStartupRecordingClosedByJvmIsServedAsStoppedRecording() throws Exception {
        int agentPort = freePort();
        Map<String, String> properties = new HashMap<>();
        properties.put("cryostat.agent.baseuri", "http://localhost:" + cryostat.port());
        properties.put("cryostat.agent.callback", "http://localhost:" + agentPort + "/");
        properties.put("cryostat.agent.webserver.host", "127.0.0.1");
        properties.put("cryostat.agent.webserver.port", Integer.toString(agentPort));
        properties.put("cryostat.agent.webclient.tls.required", "false");
        properties.put(
                "cryostat.agent.recordings.initial-scan.delay-ms",
                Long.toString(INITIAL_SCAN_DELAY_MS));

        long launched = System.nanoTime();
        dummyAppWithAgent =
                ProcessTestHelper.startDummyAppWithAgentAndJvmArguments(
                        ProcessTestHelper.getAgentShadedJarPath(),
                        List.of(
                                String.format(
                                        "-XX:StartFlightRecording=name=%s,duration=%ds,filename=%s/",
                                        RECORDING_NAME, RECORDING_DURATION_SECONDS, dumpDirectory)),
                        properties);
        Thread stdoutThread =
                ProcessTestHelper.captureStream(dummyAppWithAgent.getInputStream(), dummyStdout);
        Thread stderrThread =
                ProcessTestHelper.captureStream(dummyAppWithAgent.getErrorStream(), dummyStderr);
        Assertions.assertTrue(
                ProcessTestHelper.waitForOutput(dummyStdout, "Dummy app started", 50, 100),
                "Dummy app should start");

        String[] credentials = cryostat.awaitCredentials(30, TimeUnit.SECONDS);
        String authorization =
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        (credentials[0] + ":" + credentials[1])
                                                .getBytes(StandardCharsets.UTF_8));
        URI recordings = URI.create("http://127.0.0.1:" + agentPort + "/recordings/");

        long remainingMillis =
                TimeUnit.SECONDS.toMillis(RECORDING_DURATION_SECONDS + 3)
                        - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - launched);
        if (remainingMillis > 0) {
            Thread.sleep(remainingMillis);
        }

        JsonNode listed = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (listed == null && System.nanoTime() < deadline) {
            listed = findRecording(get(recordings, authorization), RECORDING_NAME);
            if (listed == null || !"STOPPED".equals(listed.get("state").asText())) {
                listed = null;
                Thread.sleep(250);
            }
        }
        Assertions.assertNotNull(
                listed,
                "Agent should list the recording as STOPPED after its duration elapsed, whether"
                        + " the JVM kept it or closed it");
        Assertions.assertEquals("STOPPED", listed.get("state").asText());

        HttpResponse<byte[]> download =
                http.send(
                        HttpRequest.newBuilder(recordings.resolve(listed.get("id").asText()))
                                .header("Authorization", authorization)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        Assertions.assertEquals(200, download.statusCode());
        Assertions.assertTrue(
                download.body().length > 4
                        && download.body()[0] == 'F'
                        && download.body()[1] == 'L'
                        && download.body()[2] == 'R',
                "downloaded content should be a JFR file");

        dummyAppWithAgent.destroy();
        dummyAppWithAgent.waitFor(2, TimeUnit.SECONDS);
        stderrThread.join(1000);
        stdoutThread.join(1000);
    }

    private JsonNode get(URI uri, String authorization) throws Exception {
        HttpResponse<String> res =
                http.send(
                        HttpRequest.newBuilder(uri)
                                .header("Authorization", authorization)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, res.statusCode(), res.body());
        return mapper.readTree(res.body());
    }

    private static JsonNode findRecording(JsonNode list, String name) {
        for (JsonNode node : list) {
            if (name.equals(node.get("name").asText())) {
                return node;
            }
        }
        return null;
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }
}
