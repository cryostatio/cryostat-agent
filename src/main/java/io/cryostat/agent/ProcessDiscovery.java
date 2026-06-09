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

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import io.cryostat.agent.shaded.ShadeLogger;

/**
 * Discovers Java processes using the ProcessHandle API, providing a replacement for
 * VirtualMachine.list() that works without the Attach API.
 */
class ProcessDiscovery {

    /**
     * Lists all Java processes visible to the current user.
     *
     * @return a list of JavaProcessInfo objects representing Java processes
     */
    static List<JavaProcessInfo> listJavaProcesses() {
        try {
            return ProcessHandle.allProcesses()
                    .filter(ProcessDiscovery::isJavaProcess)
                    .map(ProcessDiscovery::toJavaProcessInfo)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            ShadeLogger.getAnonymousLogger()
                    .warning(
                            String.format(
                                    "Failed to list Java processes using ProcessHandle: %s",
                                    e.getMessage()));
            return List.of();
        }
    }

    /**
     * Determines if a ProcessHandle represents a Java process.
     *
     * @param ph the ProcessHandle to check
     * @return true if the process is a Java process, false otherwise
     */
    private static boolean isJavaProcess(ProcessHandle ph) {
        Optional<String> cmd = ph.info().command();
        if (!cmd.isPresent()) {
            return false;
        }

        String command = cmd.get().toLowerCase();
        return command.contains("java") || command.endsWith("java") || command.endsWith("java.exe");
    }

    /**
     * Converts a ProcessHandle to a JavaProcessInfo object.
     *
     * @param ph the ProcessHandle to convert
     * @return a JavaProcessInfo object
     */
    private static JavaProcessInfo toJavaProcessInfo(ProcessHandle ph) {
        String pid = String.valueOf(ph.pid());
        String displayName = extractDisplayName(ph);
        return new JavaProcessInfo(pid, displayName);
    }

    /**
     * Extracts a display name from a ProcessHandle by analyzing its command line arguments.
     *
     * @param ph the ProcessHandle to extract the display name from
     * @return a display name for the process
     */
    private static String extractDisplayName(ProcessHandle ph) {
        Optional<String[]> args = ph.info().arguments();
        if (args.isPresent()) {
            String[] argArray = args.get();

            // Look for -jar argument
            for (int i = 0; i < argArray.length - 1; i++) {
                if (argArray[i].equals("-jar")) {
                    return new File(argArray[i + 1]).getName();
                }
            }

            // Look for main class (first non-option argument)
            for (String arg : argArray) {
                if (!arg.startsWith("-") && !arg.isEmpty()) {
                    // Extract simple class name
                    int lastDot = arg.lastIndexOf('.');
                    return lastDot >= 0 ? arg.substring(lastDot + 1) : arg;
                }
            }
        }

        // Fallback to command name
        return ph.info().command().map(cmd -> new File(cmd).getName()).orElse("java-" + ph.pid());
    }
}
