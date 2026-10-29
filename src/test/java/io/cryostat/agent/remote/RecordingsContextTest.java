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
package io.cryostat.agent.remote;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.cryostat.agent.FlightRecorderHelper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import io.smallrye.config.SmallRyeConfig;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecordingsContextTest {

    @Mock HttpExchange exchange;
    @Mock SmallRyeConfig config;
    @Mock AsyncProfilerExecutor asyncProfilerExecutor;

    private final ObjectMapper mapper = new ObjectMapper();
    private final FlightRecorderHelper flightRecorder = new FlightRecorderHelper();
    private RecordingsContext ctx;

    // recordings created directly by test-simulated "jfrsync" starts, so they can be cleaned up
    private final List<Recording> recordingsToClose = new ArrayList<>();

    @BeforeEach
    void setup() {
        ctx = new RecordingsContext(config, mapper, flightRecorder, asyncProfilerExecutor);
    }

    @AfterEach
    void cleanup() {
        recordingsToClose.forEach(Recording::close);
        recordingsToClose.clear();
    }

    private ByteArrayOutputStream mockPost(RecordingsContext.StartRecordingRequest req)
            throws Exception {
        byte[] body = mapper.writeValueAsBytes(req);
        when(exchange.getRequestMethod()).thenReturn("POST");
        when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(body));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        lenient().when(exchange.getResponseBody()).thenReturn(baos);
        return baos;
    }

    @Test
    void testPathIsRecordings() {
        assertEquals("/recordings/", ctx.path());
    }

    @Test
    void testStartJfrSyncRecordingWithCustomTemplateLocatesAsprofCreatedRecording()
            throws Exception {
        when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        when(asyncProfilerExecutor.isAvailable()).thenReturn(true);
        doAnswer(
                        invocation -> {
                            // simulate async-profiler's native JfrSync.start(): a new
                            // jdk.jfr.Recording is created and started, registering itself with
                            // the real FlightRecorder, without any direct involvement from
                            // RecordingsContext
                            String cmd = (String) invocation.getArgument(0);
                            String outputPath =
                                    cmd.substring(cmd.indexOf("file=") + "file=".length());
                            Recording simulated = new Recording();
                            simulated.setDestination(Path.of(outputPath));
                            simulated.start();
                            recordingsToClose.add(simulated);
                            return "profiler is running";
                        })
                .when(asyncProfilerExecutor)
                .execute(anyString());

        RecordingsContext.StartRecordingRequest req = new RecordingsContext.StartRecordingRequest();
        req.name = "jfrsync-test";
        req.template =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><configuration"
                        + " version=\"2.0\"></configuration>";
        req.asprofEvents = List.of("cpu");

        ByteArrayOutputStream baos = mockPost(req);

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(201), anyLong());
        JsonNode node = mapper.readTree(baos.toByteArray());
        assertEquals("jfrsync-test", node.get("name").asText());
        verify(asyncProfilerExecutor).execute(contains("jfrsync="));
        verify(asyncProfilerExecutor).execute(contains("event=cpu"));
    }

    @Test
    void testStartJfrSyncRecordingWithPredefinedProfileNameSkipsTempFile() throws Exception {
        when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        when(asyncProfilerExecutor.isAvailable()).thenReturn(true);
        doAnswer(
                        invocation -> {
                            String cmd = (String) invocation.getArgument(0);
                            String outputPath =
                                    cmd.substring(cmd.indexOf("file=") + "file=".length());
                            Recording simulated = new Recording();
                            simulated.setDestination(Path.of(outputPath));
                            simulated.start();
                            recordingsToClose.add(simulated);
                            return "profiler is running";
                        })
                .when(asyncProfilerExecutor)
                .execute(anyString());

        RecordingsContext.StartRecordingRequest req = new RecordingsContext.StartRecordingRequest();
        req.name = "jfrsync-default";
        req.localTemplateName = "default";
        req.asprofEvents = List.of("cpu", "alloc");

        ByteArrayOutputStream baos = mockPost(req);

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(201), anyLong());
        verify(asyncProfilerExecutor).execute(contains("jfrsync=default"));
        verify(asyncProfilerExecutor).execute(contains("event=cpu,event=alloc"));
    }

    @Test
    void testStartJfrSyncRecordingWithDynamicallyRegisteredProfileNameSkipsTempFile()
            throws Exception {
        when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        when(asyncProfilerExecutor.isAvailable()).thenReturn(true);
        doAnswer(
                        invocation -> {
                            String cmd = (String) invocation.getArgument(0);
                            String outputPath =
                                    cmd.substring(cmd.indexOf("file=") + "file=".length());
                            Recording simulated = new Recording();
                            simulated.setDestination(Path.of(outputPath));
                            simulated.start();
                            recordingsToClose.add(simulated);
                            return "profiler is running";
                        })
                .when(asyncProfilerExecutor)
                .execute(anyString());

        String dynamicProfileName = "totally-not-a-hardcoded-profile-name";
        Configuration fakeConfiguration = mock(Configuration.class);
        when(fakeConfiguration.getName()).thenReturn(dynamicProfileName);

        RecordingsContext.StartRecordingRequest req = new RecordingsContext.StartRecordingRequest();
        req.name = "jfrsync-dynamic";
        req.localTemplateName = dynamicProfileName;
        req.asprofEvents = List.of("cpu");

        ByteArrayOutputStream baos = mockPost(req);

        try (MockedStatic<Configuration> configurationStatic =
                Mockito.mockStatic(Configuration.class)) {
            configurationStatic
                    .when(Configuration::getConfigurations)
                    .thenReturn(List.of(fakeConfiguration));

            ctx.handle(exchange);
        }

        verify(exchange).sendResponseHeaders(eq(201), anyLong());
        JsonNode node = mapper.readTree(baos.toByteArray());
        assertEquals("jfrsync-dynamic", node.get("name").asText());
        verify(asyncProfilerExecutor).execute(contains("jfrsync=" + dynamicProfileName));
    }

    @Test
    void testStartJfrSyncRecordingTreatsUnregisteredDefaultNameAsNonPredefined() throws Exception {
        when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        when(asyncProfilerExecutor.isAvailable()).thenReturn(true);

        RecordingsContext.StartRecordingRequest req = new RecordingsContext.StartRecordingRequest();
        req.name = "jfrsync-not-registered";
        req.localTemplateName = "default";
        req.asprofEvents = List.of("cpu");

        mockPost(req);

        // simulate a JVM on which "default" is not a registered Configuration: the dynamic
        // lookup must not fall back to treating it as predefined, unlike a hardcoded
        // Set.of("default", "profile") check would.
        try (MockedStatic<Configuration> configurationStatic =
                Mockito.mockStatic(Configuration.class)) {
            configurationStatic.when(Configuration::getConfigurations).thenReturn(List.of());

            ctx.handle(exchange);
        }

        verify(exchange).sendResponseHeaders(400, RemoteContext.BODY_LENGTH_NONE);
        verify(asyncProfilerExecutor, never()).execute(anyString());
    }

    @Test
    void testStartJfrSyncRecordingFailsFastWhenProfilerUnavailable() throws Exception {
        when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        when(asyncProfilerExecutor.isAvailable()).thenReturn(false);

        RecordingsContext.StartRecordingRequest req = new RecordingsContext.StartRecordingRequest();
        req.name = "jfrsync-unavailable";
        req.localTemplateName = "default";
        req.asprofEvents = List.of("cpu");

        mockPost(req);

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(400, RemoteContext.BODY_LENGTH_NONE);
        verify(asyncProfilerExecutor, never()).execute(anyString());
    }

    @Test
    void testGetRecordingServesJfrSyncOutputFileAfterRecordingClosesOnStop() throws Exception {
        when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        when(asyncProfilerExecutor.isAvailable()).thenReturn(true);
        Recording[] simulatedHolder = new Recording[1];
        doAnswer(
                        invocation -> {
                            // simulate async-profiler's native JfrSync.start(): it creates a real
                            // jdk.jfr.Recording, calls setDestination(file=...) on it (this is
                            // the call that makes the JDK dump-and-close the recording
                            // synchronously the instant it stops, for any stop reason), and
                            // starts it
                            String cmd = invocation.getArgument(0);
                            String outputPath =
                                    cmd.substring(cmd.indexOf("file=") + "file=".length());
                            Recording simulated = new Recording();
                            simulated.setDestination(Path.of(outputPath));
                            simulated.start();
                            simulatedHolder[0] = simulated;
                            recordingsToClose.add(simulated);
                            return "profiler is running";
                        })
                .when(asyncProfilerExecutor)
                .execute(anyString());

        RecordingsContext.StartRecordingRequest req = new RecordingsContext.StartRecordingRequest();
        req.name = "jfrsync-expired";
        req.template =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><configuration"
                        + " version=\"2.0\"></configuration>";
        req.asprofEvents = List.of("cpu");

        ByteArrayOutputStream startBody = mockPost(req);
        ctx.handle(exchange);
        JsonNode started = mapper.readTree(startBody.toByteArray());
        long id = started.get("id").asLong();

        // simulate the recording's duration timer elapsing asynchronously, independent of any
        // request this RecordingsContext instance is handling: the backing Recording dumps its
        // content to its destination file and closes itself, atomically disappearing from
        // FlightRecorder's list, exactly as jdk.jfr.internal.PlatformRecording does for any
        // destination-bound recording
        simulatedHolder[0].stop();
        assertTrue(flightRecorder.getRecording(id).isEmpty(), "recording should no longer be live");

        reset(exchange);
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/recordings/" + id));
        ByteArrayOutputStream getBody = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(getBody);

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(200), anyLong());
        assertTrue(getBody.size() > 0, "expected non-empty jfrsync recording content");
    }

    @Test
    void testGetListReportsStoppedJfrSyncRecordingInsteadOfOmittingIt() throws Exception {
        when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        when(asyncProfilerExecutor.isAvailable()).thenReturn(true);
        Recording[] simulatedHolder = new Recording[1];
        doAnswer(
                        invocation -> {
                            String cmd = invocation.getArgument(0);
                            String outputPath =
                                    cmd.substring(cmd.indexOf("file=") + "file=".length());
                            Recording simulated = new Recording();
                            simulated.setDestination(Path.of(outputPath));
                            simulated.start();
                            simulatedHolder[0] = simulated;
                            recordingsToClose.add(simulated);
                            return "profiler is running";
                        })
                .when(asyncProfilerExecutor)
                .execute(anyString());

        RecordingsContext.StartRecordingRequest req = new RecordingsContext.StartRecordingRequest();
        req.name = "jfrsync-listed-after-stop";
        req.template =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><configuration"
                        + " version=\"2.0\"></configuration>";
        req.asprofEvents = List.of("cpu");

        ByteArrayOutputStream startBody = mockPost(req);
        ctx.handle(exchange);
        JsonNode started = mapper.readTree(startBody.toByteArray());
        long id = started.get("id").asLong();

        simulatedHolder[0].stop();
        assertTrue(flightRecorder.getRecording(id).isEmpty(), "recording should no longer be live");

        reset(exchange);
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/recordings/"));
        ByteArrayOutputStream listBody = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(listBody);

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(200), anyLong());
        JsonNode list = mapper.readTree(listBody.toByteArray());
        boolean found = false;
        for (JsonNode node : list) {
            if (node.get("id").asLong() == id) {
                found = true;
                assertEquals("STOPPED", node.get("state").asText());
            }
        }
        assertTrue(found, "expected stopped jfrsync recording to still appear in the list");
    }

    @Test
    void testDeleteCleansUpJfrSyncOutputFileAfterRecordingClosesOnStop() throws Exception {
        when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        when(asyncProfilerExecutor.isAvailable()).thenReturn(true);
        Recording[] simulatedHolder = new Recording[1];
        doAnswer(
                        invocation -> {
                            String cmd = invocation.getArgument(0);
                            String outputPath =
                                    cmd.substring(cmd.indexOf("file=") + "file=".length());
                            Recording simulated = new Recording();
                            simulated.setDestination(Path.of(outputPath));
                            simulated.start();
                            simulatedHolder[0] = simulated;
                            recordingsToClose.add(simulated);
                            return "profiler is running";
                        })
                .when(asyncProfilerExecutor)
                .execute(anyString());

        RecordingsContext.StartRecordingRequest req = new RecordingsContext.StartRecordingRequest();
        req.name = "jfrsync-delete-after-stop";
        req.template =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><configuration"
                        + " version=\"2.0\"></configuration>";
        req.asprofEvents = List.of("cpu");

        ByteArrayOutputStream startBody = mockPost(req);
        ctx.handle(exchange);
        JsonNode started = mapper.readTree(startBody.toByteArray());
        long id = started.get("id").asLong();

        org.mockito.ArgumentCaptor<String> cmdCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(asyncProfilerExecutor).execute(cmdCaptor.capture());
        String cmd = cmdCaptor.getValue();
        Path outputFile = Path.of(cmd.substring(cmd.indexOf("file=") + "file=".length()));
        assertTrue(Files.exists(outputFile), "expected jfrsync output file to exist after start");

        simulatedHolder[0].stop();
        assertTrue(flightRecorder.getRecording(id).isEmpty(), "recording should no longer be live");
        assertTrue(
                Files.exists(outputFile),
                "expected jfrsync output file to still exist after the recording stops");

        reset(exchange);
        when(exchange.getRequestMethod()).thenReturn("DELETE");
        when(exchange.getRequestURI()).thenReturn(URI.create("/recordings/" + id));

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(204, RemoteContext.BODY_LENGTH_NONE);
        assertFalse(
                Files.exists(outputFile),
                "expected jfrsync output file to be deleted on recording delete");
    }

    @Test
    void testDeleteCleansUpJfrSyncTempTemplateFile() throws Exception {
        when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        when(asyncProfilerExecutor.isAvailable()).thenReturn(true);
        doAnswer(
                        invocation -> {
                            String cmd = (String) invocation.getArgument(0);
                            String outputPath =
                                    cmd.substring(cmd.indexOf("file=") + "file=".length());
                            Recording simulated = new Recording();
                            simulated.setDestination(Path.of(outputPath));
                            simulated.start();
                            recordingsToClose.add(simulated);
                            return "profiler is running";
                        })
                .when(asyncProfilerExecutor)
                .execute(anyString());

        RecordingsContext.StartRecordingRequest req = new RecordingsContext.StartRecordingRequest();
        req.name = "jfrsync-delete-test";
        req.template =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><configuration"
                        + " version=\"2.0\"></configuration>";
        req.asprofEvents = List.of("cpu");

        ByteArrayOutputStream startBody = mockPost(req);
        ctx.handle(exchange);
        JsonNode started = mapper.readTree(startBody.toByteArray());
        long id = started.get("id").asLong();

        // find the temp .jfc file path that was embedded in the executed command
        org.mockito.ArgumentCaptor<String> cmdCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(asyncProfilerExecutor).execute(cmdCaptor.capture());
        String cmd = cmdCaptor.getValue();
        String jfcPathStr =
                cmd.substring(
                        cmd.indexOf("jfrsync=") + "jfrsync=".length(), cmd.indexOf(",event="));
        Path jfcPath = Path.of(jfcPathStr);
        assertTrue(Files.exists(jfcPath), "expected temp .jfc file to exist after start");

        reset(exchange);
        when(exchange.getRequestMethod()).thenReturn("DELETE");
        when(exchange.getRequestURI()).thenReturn(URI.create("/recordings/" + id));

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(204, RemoteContext.BODY_LENGTH_NONE);
        assertFalse(
                Files.exists(jfcPath), "expected temp .jfc file to be deleted on recording delete");
    }

    @Test
    void testStartJfrSyncRecordingWithInvalidEventNameRespondsBadRequest() throws Exception {
        when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        when(asyncProfilerExecutor.isAvailable()).thenReturn(true);
        // simulate native async-profiler rejecting an unrecognized asprofEvents name
        doAnswer(
                        invocation -> {
                            String cmd = (String) invocation.getArgument(0);
                            if (cmd.contains("not-a-real-event")) {
                                throw new IllegalArgumentException(
                                        "Unknown event: not-a-real-event");
                            }
                            String outputPath =
                                    cmd.substring(cmd.indexOf("file=") + "file=".length());
                            Recording simulated = new Recording();
                            simulated.setDestination(Path.of(outputPath));
                            simulated.start();
                            recordingsToClose.add(simulated);
                            return "profiler is running";
                        })
                .when(asyncProfilerExecutor)
                .execute(anyString());

        RecordingsContext.StartRecordingRequest req = new RecordingsContext.StartRecordingRequest();
        req.name = "jfrsync-invalid-event";
        req.localTemplateName = "default";
        req.asprofEvents = List.of("not-a-real-event");

        mockPost(req);

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(400, RemoteContext.BODY_LENGTH_NONE);
    }

    @Test
    void testStartJfrSyncRecordingWithMalformedEventNamesRespondsBadRequestWithoutStarting()
            throws Exception {
        when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);

        RecordingsContext.StartRecordingRequest req = new RecordingsContext.StartRecordingRequest();
        req.name = "jfrsync-malformed-event";
        req.localTemplateName = "default";
        req.asprofEvents = Arrays.asList("cpu", null);

        mockPost(req);
        ctx.handle(exchange);
        verify(exchange).sendResponseHeaders(400, RemoteContext.BODY_LENGTH_NONE);

        req.asprofEvents = List.of("cpu,file=/tmp/evil");
        mockPost(req);
        ctx.handle(exchange);

        req.asprofEvents = List.of("cpu=1");
        mockPost(req);
        ctx.handle(exchange);

        req.asprofEvents = List.of("a,b");
        mockPost(req);
        ctx.handle(exchange);

        verify(exchange, times(4)).sendResponseHeaders(400, RemoteContext.BODY_LENGTH_NONE);
        verify(asyncProfilerExecutor, never()).execute(anyString());
    }

    @Test
    void testStartJfrSyncRecordingWhileStandaloneProfilerActiveRespondsBadRequest()
            throws Exception {
        when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        when(asyncProfilerExecutor.isAvailable()).thenReturn(true);
        // simulate native async-profiler refusing a second concurrent session
        doThrow(new IllegalStateException("Profiler already started"))
                .when(asyncProfilerExecutor)
                .execute(anyString());

        RecordingsContext.StartRecordingRequest req = new RecordingsContext.StartRecordingRequest();
        req.name = "jfrsync-profiler-busy";
        req.localTemplateName = "default";
        req.asprofEvents = List.of("cpu");

        mockPost(req);

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(400, RemoteContext.BODY_LENGTH_NONE);
    }
}
