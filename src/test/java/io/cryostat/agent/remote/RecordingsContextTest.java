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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecordingsContextTest {

    @Mock HttpExchange exchange;
    @Mock SmallRyeConfig config;
    @Mock AsyncProfilerExecutor asyncProfilerExecutor;
    @Mock ScheduledExecutorService scheduler;

    private final ObjectMapper mapper = new ObjectMapper();
    private final FlightRecorderHelper flightRecorder = new FlightRecorderHelper();
    private RecordingsContext ctx;

    // recordings created directly by test-simulated "jfrsync" starts, so they can be cleaned up
    private final List<Recording> recordingsToClose = new ArrayList<>();

    @BeforeEach
    void setup() {
        ctx =
                new RecordingsContext(
                        config, mapper, flightRecorder, asyncProfilerExecutor, scheduler);
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

    private long startJfrSyncRecording(
            String name, long durationMillis, Recording[] simulatedHolder, Path[] outputHolder)
            throws Exception {
        when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        when(asyncProfilerExecutor.isAvailable()).thenReturn(true);
        doAnswer(
                        invocation -> {
                            // simulate async-profiler's native JfrSync.start(): it creates a real
                            // jdk.jfr.Recording, calls setDestination(file=...) on it, and starts
                            // it
                            String cmd = invocation.getArgument(0);
                            String outputPath =
                                    cmd.substring(cmd.indexOf("file=") + "file=".length());
                            Recording simulated = new Recording();
                            simulated.setDestination(Path.of(outputPath));
                            simulated.start();
                            simulatedHolder[0] = simulated;
                            outputHolder[0] = Path.of(outputPath);
                            recordingsToClose.add(simulated);
                            return "profiler is running";
                        })
                .when(asyncProfilerExecutor)
                .execute(anyString());

        RecordingsContext.StartRecordingRequest req = new RecordingsContext.StartRecordingRequest();
        req.name = name;
        req.duration = durationMillis;
        req.template =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><configuration"
                        + " version=\"2.0\"></configuration>";
        req.asprofEvents = List.of("cpu");

        ByteArrayOutputStream startBody = mockPost(req);
        ctx.handle(exchange);
        return mapper.readTree(startBody.toByteArray()).get("id").asLong();
    }

    private void mockRequest(String method, String path) {
        reset(exchange);
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create(path));
        lenient().when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());
    }

    private JsonNode findInList(long id) throws Exception {
        mockRequest("GET", "/recordings/");
        ByteArrayOutputStream listBody = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(listBody);
        ctx.handle(exchange);
        for (JsonNode node : mapper.readTree(listBody.toByteArray())) {
            if (node.get("id").asLong() == id) {
                return node;
            }
        }
        return null;
    }

    @Test
    void testJfrSyncRecordingClosedByJdkIsServedAsReadOnlyStoppedGhost() throws Exception {
        Recording[] simulatedHolder = new Recording[1];
        Path[] outputHolder = new Path[1];
        long id = startJfrSyncRecording("jfrsync-closed", 0, simulatedHolder, outputHolder);

        // newer JDKs dump a destination-bound recording and close it the instant it stops, so it
        // disappears from FlightRecorder and JMX
        simulatedHolder[0].stop();
        assertTrue(flightRecorder.getRecording(id).isEmpty(), "recording should no longer be live");

        JsonNode listed = findInList(id);
        assertNotNull(listed, "expected the ghost recording to be listed");
        assertEquals("jfrsync-closed", listed.get("name").asText());
        assertEquals("STOPPED", listed.get("state").asText());
        assertTrue(Files.exists(outputHolder[0]), "expected the dump file to back the ghost");

        mockRequest("GET", "/recordings/" + id);
        ByteArrayOutputStream getBody = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(getBody);
        ctx.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(200), anyLong());
        assertTrue(getBody.size() > 0, "expected the ghost to serve the dumped recording");
    }

    @Test
    void testGhostRecordingRejectsEveryMutationExceptRedundantStopAndDelete() throws Exception {
        Recording[] simulatedHolder = new Recording[1];
        Path[] outputHolder = new Path[1];
        long id = startJfrSyncRecording("jfrsync-ghost-ro", 0, simulatedHolder, outputHolder);
        simulatedHolder[0].stop();

        for (String body :
                List.of(
                        "{\"name\":\"renamed\"}",
                        "{\"duration\":5000}",
                        "{\"maxSize\":1}",
                        "{\"maxAge\":1}",
                        "{\"toDisk\":false}",
                        "{\"state\":\"stopped\",\"name\":\"renamed\"}")) {
            mockRequest("PATCH", "/recordings/" + id);
            when(exchange.getRequestBody())
                    .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            ctx.handle(exchange);
            verify(exchange).sendResponseHeaders(409, RemoteContext.BODY_LENGTH_NONE);
        }
        assertEquals("jfrsync-ghost-ro", findInList(id).get("name").asText());

        mockRequest("PATCH", "/recordings/" + id);
        when(exchange.getRequestBody())
                .thenReturn(
                        new ByteArrayInputStream(
                                "{\"state\":\"stopped\"}".getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream stopBody = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(stopBody);
        ctx.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(200), anyLong());
        assertEquals("STOPPED", mapper.readTree(stopBody.toByteArray()).get("state").asText());
    }

    @Test
    void testDeleteOfGhostRecordingCleansUpItsFiles() throws Exception {
        Recording[] simulatedHolder = new Recording[1];
        Path[] outputHolder = new Path[1];
        long id = startJfrSyncRecording("jfrsync-ghost-del", 0, simulatedHolder, outputHolder);
        simulatedHolder[0].stop();
        assertNotNull(findInList(id));

        mockRequest("DELETE", "/recordings/" + id);
        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(204, RemoteContext.BODY_LENGTH_NONE);
        assertFalse(Files.exists(outputHolder[0].getParent()));
        assertNull(findInList(id));
    }

    @Test
    void testGhostRecordingWhoseDumpFileIsGoneIsForgotten() throws Exception {
        Recording[] simulatedHolder = new Recording[1];
        Path[] outputHolder = new Path[1];
        long id = startJfrSyncRecording("jfrsync-ghost-gone", 0, simulatedHolder, outputHolder);
        simulatedHolder[0].stop();
        assertNotNull(findInList(id));

        Files.delete(outputHolder[0]);

        assertNull(findInList(id), "expected a ghost without a backing file to be forgotten");
        assertFalse(Files.exists(outputHolder[0].getParent()));
        mockRequest("DELETE", "/recordings/" + id);
        ctx.handle(exchange);
        verify(exchange).sendResponseHeaders(404, RemoteContext.BODY_LENGTH_NONE);
    }

    @Test
    void testJfrSyncRecordingStillVisibleAfterStopRemainsTracked() throws Exception {
        Recording[] simulatedHolder = new Recording[1];
        Path[] outputHolder = new Path[1];
        long id = startJfrSyncRecording("jfrsync-visible", 0, simulatedHolder, outputHolder);

        // older JDKs leave the recording visible in the STOPPED state; clearing the destination
        // reproduces that behaviour
        simulatedHolder[0].setDestination(null);
        simulatedHolder[0].stop();
        assertTrue(flightRecorder.getRecording(id).isPresent(), "recording should remain visible");

        mockRequest("GET", "/recordings/");
        ByteArrayOutputStream listBody = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(listBody);
        ctx.handle(exchange);

        boolean found = false;
        for (JsonNode node : mapper.readTree(listBody.toByteArray())) {
            if (node.get("id").asLong() == id) {
                found = true;
                assertEquals("STOPPED", node.get("state").asText());
            }
        }
        assertTrue(found, "expected stopped but visible recording to be listed");
        assertTrue(
                Files.exists(outputHolder[0].getParent()),
                "expected bookkeeping files to be kept while the recording is visible");

        mockRequest("DELETE", "/recordings/" + id);
        ctx.handle(exchange);
        verify(exchange).sendResponseHeaders(204, RemoteContext.BODY_LENGTH_NONE);
        assertFalse(Files.exists(outputHolder[0].getParent()));
    }

    @Test
    void testJfrSyncRecordingIsCheckedWhenItsDurationElapses() throws Exception {
        Recording[] simulatedHolder = new Recording[1];
        Path[] outputHolder = new Path[1];
        long id = startJfrSyncRecording("jfrsync-timed", 60_000, simulatedHolder, outputHolder);

        ArgumentCaptor<Runnable> check = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler)
                .schedule(
                        check.capture(),
                        longThat(delay -> delay > 0 && delay <= 61_000),
                        eq(TimeUnit.MILLISECONDS));

        simulatedHolder[0].stop();
        check.getValue().run();

        // the check froze the ghost without any request being made; delete the dump file and the
        // next reconcile has nothing left to serve
        Files.delete(outputHolder[0]);
        check.getValue().run();
        assertFalse(
                Files.exists(outputHolder[0].getParent()),
                "expected the scheduled check to have reconciled the recording without a request");
        assertNull(findInList(id));
    }

    @Test
    void testPatchedDurationReschedulesJfrSyncCheck() throws Exception {
        Recording[] simulatedHolder = new Recording[1];
        Path[] outputHolder = new Path[1];
        long id = startJfrSyncRecording("jfrsync-patch-duration", 0, simulatedHolder, outputHolder);
        verify(scheduler, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

        mockRequest("PATCH", "/recordings/" + id);
        when(exchange.getRequestBody())
                .thenReturn(
                        new ByteArrayInputStream(
                                "{\"duration\":5000}".getBytes(StandardCharsets.UTF_8)));
        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(200), anyLong());
        verify(scheduler)
                .schedule(
                        any(Runnable.class),
                        longThat(delay -> delay > 0 && delay <= 6_000),
                        eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void testPatchStopOfJfrSyncRecordingRespondsWithStoppedDescriptorAndLeavesGhost()
            throws Exception {
        Recording[] simulatedHolder = new Recording[1];
        Path[] outputHolder = new Path[1];
        long id = startJfrSyncRecording("jfrsync-stop", 0, simulatedHolder, outputHolder);

        mockRequest("PATCH", "/recordings/" + id);
        when(exchange.getRequestBody())
                .thenReturn(
                        new ByteArrayInputStream(
                                "{\"state\":\"stopped\",\"name\":\"renamed\"}"
                                        .getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream patchBody = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(patchBody);
        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(200), anyLong());
        JsonNode node = mapper.readTree(patchBody.toByteArray());
        assertEquals("renamed", node.get("name").asText());
        assertEquals("STOPPED", node.get("state").asText());
        assertTrue(flightRecorder.getRecording(id).isEmpty(), "recording should have closed");

        JsonNode listed = findInList(id);
        assertNotNull(listed, "expected the stopped recording to remain as a ghost");
        assertEquals("renamed", listed.get("name").asText());
    }

    private Recording startExternalRecording(String name, Path destination) throws Exception {
        Recording recording = new Recording();
        recording.setName(name);
        recording.setToDisk(true);
        if (destination != null) {
            recording.setDestination(destination);
        }
        recording.start();
        recordingsToClose.add(recording);
        return recording;
    }

    @Test
    void testExternallyStartedRecordingIsDiscoveredAndListed() throws Exception {
        Recording recording = startExternalRecording("external-live", null);

        JsonNode listed = findInList(recording.getId());

        assertNotNull(listed, "expected an untracked live recording to be listed");
        assertEquals("RUNNING", listed.get("state").asText());
    }

    @Test
    void testDestinationBoundRecordingClosedByJdkIsServedAsGhostAndItsFileIsNotDeleted()
            throws Exception {
        lenient().when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        Path destination = Files.createTempFile("cryostat-external-", ".jfr");
        try {
            Recording recording = startExternalRecording("external-ghost", destination);
            long id = recording.getId();
            assertNotNull(findInList(id));

            recording.stop();
            assertTrue(flightRecorder.getRecording(id).isEmpty(), "recording should have closed");

            JsonNode listed = findInList(id);
            assertNotNull(listed, "expected the closed recording to remain as a ghost");
            assertEquals("external-ghost", listed.get("name").asText());
            assertEquals("STOPPED", listed.get("state").asText());

            mockRequest("PATCH", "/recordings/" + id);
            when(exchange.getRequestBody())
                    .thenReturn(
                            new ByteArrayInputStream(
                                    "{\"name\":\"renamed\"}".getBytes(StandardCharsets.UTF_8)));
            ctx.handle(exchange);
            verify(exchange).sendResponseHeaders(409, RemoteContext.BODY_LENGTH_NONE);

            mockRequest("DELETE", "/recordings/" + id);
            ctx.handle(exchange);
            verify(exchange).sendResponseHeaders(204, RemoteContext.BODY_LENGTH_NONE);
            assertTrue(
                    Files.exists(destination),
                    "expected a destination not created by the agent to be left in place");
            assertNull(findInList(id));
        } finally {
            Files.deleteIfExists(destination);
        }
    }

    @Test
    void testRecordingClosedBeforeAnyRequestIsStillTrackedWhenContextWasStarted() throws Exception {
        lenient().when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        when(config.getValue(anyString(), eq(long.class))).thenReturn(0L);
        ctx.start();
        Path destination = Files.createTempFile("cryostat-early-", ".jfr");
        try {
            Recording recording = startExternalRecording("external-early", destination);
            long id = recording.getId();

            recording.stop();
            assertTrue(flightRecorder.getRecording(id).isEmpty(), "recording should have closed");

            JsonNode listed = findInList(id);
            assertNotNull(listed, "expected the recording to be tracked without any prior request");
            assertEquals("STOPPED", listed.get("state").asText());
        } finally {
            Files.deleteIfExists(destination);
        }
    }

    @Test
    void testRecordingAlreadyRunningWhenContextStartsIsTracked() throws Exception {
        lenient().when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        Path destination = Files.createTempFile("cryostat-preexisting-", ".jfr");
        try {
            Recording recording = startExternalRecording("external-preexisting", destination);
            long id = recording.getId();

            when(config.getValue(anyString(), eq(long.class))).thenReturn(0L);
            ctx.start();
            recording.stop();

            assertNotNull(findInList(id), "expected a pre-existing recording to be tracked");
        } finally {
            Files.deleteIfExists(destination);
        }
    }

    @Test
    void testPositiveInitialScanDelayDefersTheSweepToTheScheduler() throws Exception {
        lenient().when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        Path destination = Files.createTempFile("cryostat-delayed-", ".jfr");
        try {
            when(config.getValue(anyString(), eq(long.class))).thenReturn(5_000L);
            ctx.start();
            ArgumentCaptor<Runnable> sweep = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).schedule(sweep.capture(), eq(5_000L), eq(TimeUnit.MILLISECONDS));

            Recording recording = startExternalRecording("external-delayed", destination);
            long id = recording.getId();
            sweep.getValue().run();
            recording.stop();

            assertNotNull(findInList(id), "expected the delayed sweep to track the recording");
        } finally {
            Files.deleteIfExists(destination);
        }
    }

    @Test
    void testZeroInitialScanDelaySweepsSynchronouslyWithoutScheduling() throws Exception {
        when(config.getValue(anyString(), eq(long.class))).thenReturn(0L);

        ctx.start();

        verify(scheduler, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void testRecordingWithoutDestinationIsLinkedToDumpFileFoundByGeneratedName() throws Exception {
        lenient().when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        Path dir = Files.createTempDirectory("cryostat-startup-dump-");
        FlightRecorderHelper spyHelper = spy(flightRecorder);
        RecordingsContext spyCtx =
                new RecordingsContext(config, mapper, spyHelper, asyncProfilerExecutor, scheduler);
        try {
            Recording recording = startExternalRecording("startup-like", null);
            long id = recording.getId();
            Path dump =
                    Files.createFile(
                            dir.resolve("hotspot-pid-1-id-" + id + "-2026_01_01_00_00_00.jfr"));
            doReturn(Optional.of(dump)).when(spyHelper).findStartupDumpFile(id);

            mockRequest("GET", "/recordings/");
            spyCtx.handle(exchange);
            recording.close();

            mockRequest("GET", "/recordings/");
            ByteArrayOutputStream listBody = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(listBody);
            spyCtx.handle(exchange);
            JsonNode listed = null;
            for (JsonNode node : mapper.readTree(listBody.toByteArray())) {
                if (node.get("id").asLong() == id) {
                    listed = node;
                }
            }
            assertNotNull(listed, "expected the dump file found by name to back a ghost");
            assertEquals("STOPPED", listed.get("state").asText());
            assertEquals("startup-like", listed.get("name").asText());

            mockRequest("DELETE", "/recordings/" + id);
            spyCtx.handle(exchange);
            verify(exchange).sendResponseHeaders(204, RemoteContext.BODY_LENGTH_NONE);
            assertTrue(Files.exists(dump), "expected a file the agent did not create to be kept");
        } finally {
            try (var files = Files.list(dir)) {
                files.forEach(f -> f.toFile().delete());
            }
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void testRecordingWithoutDestinationThatDisappearsIsForgotten() throws Exception {
        lenient().when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        Recording recording = startExternalRecording("external-lost", null);
        long id = recording.getId();
        assertNotNull(findInList(id));

        recording.close();

        assertNull(findInList(id), "expected a lost recording without a dump file to be forgotten");
        mockRequest("DELETE", "/recordings/" + id);
        ctx.handle(exchange);
        verify(exchange).sendResponseHeaders(404, RemoteContext.BODY_LENGTH_NONE);
    }

    @Test
    void testRegularRecordingIsCheckedWhenItsDurationElapses() throws Exception {
        when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        RecordingsContext.StartRecordingRequest req = new RecordingsContext.StartRecordingRequest();
        req.name = "regular-timed";
        req.duration = 60_000;
        req.template =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><configuration"
                        + " version=\"2.0\"></configuration>";
        ByteArrayOutputStream startBody = mockPost(req);

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(201), anyLong());
        long id = mapper.readTree(startBody.toByteArray()).get("id").asLong();
        recordingsToClose.add(flightRecorder.getRecording(id).orElseThrow());
        verify(scheduler)
                .schedule(
                        any(Runnable.class),
                        longThat(delay -> delay > 0 && delay <= 61_000),
                        eq(TimeUnit.MILLISECONDS));
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
