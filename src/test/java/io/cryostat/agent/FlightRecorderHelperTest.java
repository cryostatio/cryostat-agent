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

        assertNotNull(recording);
        assertFalse(recording.isEmpty(), "Recording should not be empty for template: " + template);
    }

    @Test
    public void testStartandGetRecordingsWithPredefinedTemplate() {
        FlightRecorderHelper helper = new FlightRecorderHelper();

        Optional<TemplatedRecording> continuousRecording =
                helper.createRecordingWithPredefinedTemplate("Continuous");
        assertTrue(continuousRecording.isPresent());
        Optional<TemplatedRecording> profilingRecording =
                helper.createRecordingWithPredefinedTemplate("Profiling");
        assertTrue(profilingRecording.isPresent());
        Optional<TemplatedRecording> allRecording =
                helper.createRecordingWithPredefinedTemplate("ALL");
        assertTrue(allRecording.isPresent());
        Optional<TemplatedRecording> profileRecording =
                helper.createRecordingWithPredefinedTemplate("profile");
        assertTrue(profileRecording.isPresent());
        Optional<TemplatedRecording> defaultRecording =
                helper.createRecordingWithPredefinedTemplate("default");
        assertTrue(defaultRecording.isPresent());
        Optional<TemplatedRecording> invalidTemplate =
                helper.createRecordingWithPredefinedTemplate("invalidTemplate");
        assertTrue(invalidTemplate.isEmpty());

        List<Recording> recordings = helper.getRecordings();
        assertEquals(5, recordings.size());
    }

    @AfterEach
    public void cleanup() {
        FlightRecorderHelper helper = new FlightRecorderHelper();

        Recording recording = helper.getRecordings().get(0);
        recording.close();
        helper.getRecordings().clear();
    }
}
