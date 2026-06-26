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

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import io.cryostat.agent.CryostatClient;
import io.cryostat.agent.FlightRecorderHelper;
import io.cryostat.agent.Registration;

import jdk.jfr.Recording;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    void testAdditionalLabelsWithStartedRecording() {
        recording = new Recording();
        recording.setMaxAge(Duration.ofSeconds(30));
        recording.start();

        Map<String, String> labels = harvester.additionalLabels(recording);

        long startTime = recording.getStartTime().toEpochMilli();
        assertEquals(String.valueOf(startTime), labels.get("startTime"));
        assertEquals(String.valueOf(30_000L), labels.get("duration"));
        assertEquals(String.valueOf(recording.getId()), labels.get("sourceRecordingId"));
    }

    @Test
    void testAdditionalLabelsWithFixedDurationRecording() {
        recording = new Recording();
        recording.setDuration(Duration.ofSeconds(10));
        recording.start();

        Map<String, String> labels = harvester.additionalLabels(recording);

        assertEquals(String.valueOf(10_000L), labels.get("duration"));
        assertEquals(String.valueOf(recording.getId()), labels.get("sourceRecordingId"));
    }

    @Test
    void testAdditionalLabelsNullDurationAndNullMaxAge() {
        recording = new Recording();

        Map<String, String> labels = harvester.additionalLabels(recording);

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
                        periodicSettings,
                        true,
                        client,
                        flightRecorderHelper,
                        registration);

        recording = new Recording();

        Map<String, String> labels = autoanalyzeHarvester.additionalLabels(recording);

        assertEquals("true", labels.get("autoanalyze"));
        assertEquals("0", labels.get("startTime"));
        assertEquals("0", labels.get("duration"));
        assertEquals(String.valueOf(recording.getId()), labels.get("sourceRecordingId"));
    }
}
