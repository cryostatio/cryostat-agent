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
import org.junit.jupiter.api.Test;

public class FlightRecorderHelperTest {
    @Test
    public void testCreateRecordingWithTemplate() {
        FlightRecorderHelper helper = new FlightRecorderHelper();

        Optional<TemplatedRecording> recording =
                helper.createRecordingWithPredefinedTemplate("ALL");
        assertNotNull(recording);

        // Try to create recording with invalid template
        Optional<TemplatedRecording> invalid =
                helper.createRecordingWithPredefinedTemplate("invalid");
        assertTrue(invalid.isEmpty());

        Recording allTemplateRecording = recording.get().getRecording();
        allTemplateRecording.close();
        List<Recording> recordings = helper.getRecordings();

        assertEquals(0, recordings.size());
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
}
