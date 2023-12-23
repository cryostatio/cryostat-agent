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

import java.util.List;
import java.util.Optional;

import io.cryostat.agent.FlightRecorderHelper.TemplatedRecording;

import jdk.jfr.Recording;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class FlightRecorderHelperTest {
    @ParameterizedTest
    @ValueSource(strings = {"default", "Continuous", "profile", "Profiling", "ALL"})
    public void testCreateRecordingWithTemplate(String template) {
        FlightRecorderHelper helper = new FlightRecorderHelper();

        Optional<TemplatedRecording> recording =
                helper.createRecordingWithPredefinedTemplate(template);

        switch (template) {
            case "ALL":
                assertNotNull(recording);
                assertFalse(recording.isEmpty());
                break;
            case "default":
                assertFalse(recording.isEmpty());
                break;
            case "Continuous":
                assertFalse(recording.isEmpty());
                break;
            case "profile":
                assertFalse(recording.isEmpty());
                break;
            case "Profiling":
                assertFalse(recording.isEmpty());
                break;
            default:
                fail("Unexpected template: " + template);
                assertTrue(recording.isEmpty());
        }
    }

    @Test
    public void testGetRecordings() {
        FlightRecorderHelper helper = new FlightRecorderHelper();

        Optional<TemplatedRecording> continuousRecording =
                helper.createRecordingWithPredefinedTemplate("ALL");
        assertTrue(continuousRecording.isPresent());
        Optional<TemplatedRecording> profilingRecording =
                helper.createRecordingWithPredefinedTemplate("ALL");
        assertTrue(profilingRecording.isPresent());
        Optional<TemplatedRecording> allRecording =
                helper.createRecordingWithPredefinedTemplate("ALL");
        assertTrue(allRecording.isPresent());

        List<Recording> recordings = helper.getRecordings();
        assertEquals(3, recordings.size());
    }

    @AfterEach
    public void cleanup() {
        FlightRecorderHelper helper = new FlightRecorderHelper();

        Recording recording = helper.getRecordings().get(0);
        recording.close();
        helper.getRecordings().clear();
    }
}
