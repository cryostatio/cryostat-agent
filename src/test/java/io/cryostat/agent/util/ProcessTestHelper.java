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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;

public class ProcessTestHelper {

    public static Process startDummyApp(String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-Dio.cryostat.agent.shaded.org.slf4j.simpleLogger.defaultLogLevel=WARNING");
        command.add("-cp");
        command.add(System.getProperty("project.build.testOutputDirectory", "target/test-classes"));
        command.add(DummyApp.class.getName());
        command.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        return pb.start();
    }

    public static String getAgentShadedJarPath() {
        String jarPath = System.getProperty("cryostat.agent.shaded.jar");
        Assertions.assertNotNull(jarPath, "Shaded JAR path must be provided");
        Assertions.assertTrue(
                Files.exists(Paths.get(jarPath)), "Shaded JAR must exist at: " + jarPath);
        return jarPath;
    }

    public static Process startAgentProcess(
            String jarPath, long pid, Map<String, String> properties) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-jar");
        command.add(jarPath);
        command.add(Long.toString(pid));

        if (properties != null) {
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                command.add(String.format("-D%s=%s", entry.getKey(), entry.getValue()));
            }
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        return pb.start();
    }

    public static Thread captureStream(InputStream stream, StringBuilder output) {
        Thread thread =
                new Thread(
                        () -> {
                            try (BufferedReader reader =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    stream, StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    synchronized (output) {
                                        output.append(line).append("\n");
                                    }
                                }
                            } catch (Exception e) {
                                // Ignore - process may have been terminated
                            }
                        });
        thread.start();
        return thread;
    }

    public static boolean waitForOutput(
            StringBuilder output, String expectedText, int maxAttempts, long sleepMillis)
            throws InterruptedException {
        for (int i = 0; i < maxAttempts; i++) {
            Thread.sleep(sleepMillis);
            synchronized (output) {
                if (output.toString().contains(expectedText)) {
                    return true;
                }
            }
        }
        return false;
    }
}
