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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class FlightRecorderHelperDumpFileTest {

    @Test
    public void testParseStartupDumpDirectoriesReadsFilenameOptions() {
        List<Path> directories =
                FlightRecorderHelper.parseStartupDumpDirectories(
                        List.of(
                                "-Xmx1g",
                                "-XX:StartFlightRecording=name=a,duration=5s,filename=/tmp/a/",
                                "-XX:StartFlightRecording:filename=\"/tmp/b,c/\",disk=true",
                                "-XX:StartFlightRecording=filename=/tmp/%p/",
                                "-XX:StartFlightRecording=filename=/tmp/%t/",
                                "-XX:StartFlightRecording=name=nofile",
                                "-XX:StartFlightRecording",
                                "-XX:StartFlightRecordingX=filename=/tmp/x/"),
                        4242);

        assertEquals(
                List.of(Path.of("/tmp/a"), Path.of("/tmp/b,c"), Path.of("/tmp/4242")), directories);
    }

    @Test
    public void testFindDumpFileMatchesPidAndRecordingIdAndPrefersNewest() throws Exception {
        Path dir = Files.createTempDirectory("cryostat-dump-test-");
        try {
            Path older =
                    Files.createFile(dir.resolve("hotspot-pid-7-id-3-2026_01_01_00_00_00.jfr"));
            Path newer =
                    Files.createFile(dir.resolve("hotspot-pid-7-id-3-2026_01_02_00_00_00.jfr"));
            Files.createFile(dir.resolve("hotspot-pid-7-id-30-2026_01_03_00_00_00.jfr"));
            Files.createFile(dir.resolve("hotspot-pid-8-id-3-2026_01_03_00_00_00.jfr"));
            Files.createFile(dir.resolve("hotspot-pid-7-id-3-2026_01_03_00_00_00.txt"));

            assertEquals(Optional.of(newer), FlightRecorderHelper.findDumpFile(List.of(dir), 7, 3));
            assertTrue(FlightRecorderHelper.findDumpFile(List.of(dir), 7, 4).isEmpty());
            assertTrue(
                    FlightRecorderHelper.findDumpFile(List.of(dir.resolve("missing")), 7, 3)
                            .isEmpty());
            Files.delete(older);
            Files.delete(newer);
        } finally {
            try (var files = Files.list(dir)) {
                files.forEach(f -> f.toFile().delete());
            }
            Files.deleteIfExists(dir);
        }
    }
}
