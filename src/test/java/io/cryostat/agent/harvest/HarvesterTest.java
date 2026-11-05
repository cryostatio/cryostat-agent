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
package io.cryostat.agent.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.cryostat.agent.CryostatClient;
import io.cryostat.agent.FlightRecorderHelper;
import io.cryostat.agent.Registration;

import jdk.jfr.Recording;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HarvesterTest {

    @Mock ScheduledExecutorService executor;
    @Mock ScheduledExecutorService workerPool;
    @Mock CryostatClient client;
    @Mock FlightRecorderHelper flightRecorderHelper;
    @Mock Registration registration;

    private Harvester harvester;
    private Recording recording;

    @BeforeEach
    void setup() {
        Harvester.RecordingSettings exitSettings = new Harvester.RecordingSettings();
        Harvester.RecordingSettings periodicSettings = new Harvester.RecordingSettings();
        harvester =
                new Harvester(
                        executor,
                        workerPool,
                        -1L,
                        "default",
                        5,
                        exitSettings,
                        30_000L,
                        periodicSettings,
                        false,
                        client,
                        flightRecorderHelper,
                        registration);
    }

    @AfterEach
    void cleanup() {
        if (recording != null) {
            recording.close();
            recording = null;
        }
    }

    @Test
    void testAdditionalLabelsSnapshotWithMaxAge() {
        recording = new Recording();
        recording.setMaxAge(Duration.ofSeconds(30));
        recording.start();

        long beforeMs = System.currentTimeMillis();
        Map<String, String> labels = harvester.additionalLabels(recording, true);
        long afterMs = System.currentTimeMillis();

        long startTime = Long.parseLong(labels.get("startTime"));
        assertEquals(String.valueOf(30_000L), labels.get("duration"));
        assertTrue(
                startTime >= beforeMs - 30_000L && startTime <= afterMs - 30_000L,
                "startTime should be approximately now - maxAge");
        assertEquals(String.valueOf(recording.getId()), labels.get("sourceRecordingId"));
    }

    @Test
    void testAdditionalLabelsSnapshotNoMaxAge() {
        recording = new Recording();

        Map<String, String> labels = harvester.additionalLabels(recording, true);

        assertEquals("0", labels.get("startTime"));
        assertEquals("0", labels.get("duration"));
        assertEquals(String.valueOf(recording.getId()), labels.get("sourceRecordingId"));
    }

    @Test
    void testAdditionalLabelsNormalRecordingWithFixedDuration() {
        recording = new Recording();
        recording.setDuration(Duration.ofSeconds(10));
        recording.start();

        Map<String, String> labels = harvester.additionalLabels(recording, false);

        long startTime = recording.getStartTime().toEpochMilli();
        assertEquals(String.valueOf(startTime), labels.get("startTime"));
        assertEquals(String.valueOf(10_000L), labels.get("duration"));
        assertEquals(String.valueOf(recording.getId()), labels.get("sourceRecordingId"));
    }

    @Test
    void testAdditionalLabelsNormalRecordingWithMaxAge() {
        recording = new Recording();
        recording.setMaxAge(Duration.ofSeconds(30));
        recording.start();

        Map<String, String> labels = harvester.additionalLabels(recording, false);

        long startTime = recording.getStartTime().toEpochMilli();
        assertEquals(String.valueOf(startTime), labels.get("startTime"));
        assertEquals(String.valueOf(30_000L), labels.get("duration"));
        assertEquals(String.valueOf(recording.getId()), labels.get("sourceRecordingId"));
    }

    @Test
    void testAdditionalLabelsNormalRecordingNoMaxAgeNoDuration() {
        recording = new Recording();

        Map<String, String> labels = harvester.additionalLabels(recording, false);

        assertEquals("0", labels.get("startTime"));
        assertEquals("0", labels.get("duration"));
        assertEquals(String.valueOf(recording.getId()), labels.get("sourceRecordingId"));
    }

    @Test
    void testAdditionalLabelsWithAutoanalyzeEnabled() {
        Harvester.RecordingSettings exitSettings = new Harvester.RecordingSettings();
        Harvester.RecordingSettings periodicSettings = new Harvester.RecordingSettings();
        Harvester autoanalyzeHarvester =
                new Harvester(
                        executor,
                        workerPool,
                        -1L,
                        "default",
                        5,
                        exitSettings,
                        30_000L,
                        periodicSettings,
                        true,
                        client,
                        flightRecorderHelper,
                        registration);

        recording = new Recording();

        Map<String, String> labels = autoanalyzeHarvester.additionalLabels(recording, false);

        assertEquals("true", labels.get("autoanalyze"));
        assertEquals("0", labels.get("startTime"));
        assertEquals("0", labels.get("duration"));
        assertEquals(String.valueOf(recording.getId()), labels.get("sourceRecordingId"));
    }

    @Test
    void testScheduledUploadSkippedWhilePreviousUploadInProgress() throws Exception {
        Harvester periodicHarvester =
                new Harvester(
                        executor,
                        workerPool,
                        60_000L,
                        "default",
                        5,
                        new Harvester.RecordingSettings(),
                        30_000L,
                        new Harvester.RecordingSettings(),
                        false,
                        client,
                        flightRecorderHelper,
                        registration);
        when(executor.submit(any(Runnable.class)))
                .thenAnswer(
                        inv -> {
                            inv.<Runnable>getArgument(0).run();
                            return null;
                        });
        CompletableFuture<Void> inFlight = new CompletableFuture<>();
        when(client.upload(any(), any(), anyInt(), any(), any())).thenReturn(inFlight);

        recording = new Recording();
        recording.start();

        try {
            periodicHarvester.start();
            ArgumentCaptor<Runnable> scheduled = ArgumentCaptor.forClass(Runnable.class);
            verify(workerPool)
                    .scheduleAtFixedRate(
                            scheduled.capture(),
                            eq(60_000L),
                            eq(60_000L),
                            eq(TimeUnit.MILLISECONDS));

            scheduled.getValue().run();
            verify(client, times(1)).upload(any(), any(), anyInt(), any(), any());

            scheduled.getValue().run();
            verify(client, times(1)).upload(any(), any(), anyInt(), any(), any());

            inFlight.complete(null);

            scheduled.getValue().run();
            verify(client, times(2)).upload(any(), any(), anyInt(), any(), any());
        } finally {
            periodicHarvester.stop();
        }
    }

    @Test
    void testDumpDeletedAfterSuccessfulScheduledUpload() throws Exception {
        when(client.upload(any(), any(), anyInt(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        Path dump = runScheduledUploadAndCaptureDump();

        assertFalse(Files.exists(dump), "dump should be deleted after a successful upload");
    }

    @Test
    void testDumpDeletedAfterFailedScheduledUpload() throws Exception {
        when(client.upload(any(), any(), anyInt(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IOException("upload failed")));

        Path dump = runScheduledUploadAndCaptureDump();

        assertFalse(Files.exists(dump), "dump should be deleted after a failed upload");
    }

    @Test
    void testDumpDeletedWhenUploadFailsSynchronously() throws Exception {
        when(client.upload(any(), any(), anyInt(), any(), any()))
                .thenThrow(new IOException("could not start upload"));

        Path dump = runScheduledUploadAndCaptureDump();

        assertFalse(Files.exists(dump), "dump should be deleted when the upload cannot start");
    }

    private Path runScheduledUploadAndCaptureDump() throws Exception {
        Harvester periodicHarvester =
                new Harvester(
                        executor,
                        workerPool,
                        60_000L,
                        "default",
                        5,
                        new Harvester.RecordingSettings(),
                        30_000L,
                        new Harvester.RecordingSettings(),
                        false,
                        client,
                        flightRecorderHelper,
                        registration);
        when(executor.submit(any(Runnable.class)))
                .thenAnswer(
                        inv -> {
                            inv.<Runnable>getArgument(0).run();
                            return null;
                        });

        recording = new Recording();
        recording.start();

        try {
            periodicHarvester.start();
            ArgumentCaptor<Runnable> scheduled = ArgumentCaptor.forClass(Runnable.class);
            verify(workerPool)
                    .scheduleAtFixedRate(
                            scheduled.capture(),
                            eq(60_000L),
                            eq(60_000L),
                            eq(TimeUnit.MILLISECONDS));

            scheduled.getValue().run();

            ArgumentCaptor<Path> dump = ArgumentCaptor.forClass(Path.class);
            verify(client).upload(any(), any(), anyInt(), any(), dump.capture());
            return dump.getValue();
        } finally {
            periodicHarvester.stop();
        }
    }
}
