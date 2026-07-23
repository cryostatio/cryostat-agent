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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GcLogging {

    private static final Pattern VM_LOG_LIST_FILE_PATTERN =
            Pattern.compile("^\\s*#\\d+: file=(\\S+) (\\S+) (\\S+)(?:\\s+(\\S+))?");
    private static final Pattern VM_LOG_LIST_NONFILE_PATTERN =
            Pattern.compile("^\\s*#\\d+: (stdout|stderr) (\\S+) (\\S+)");
    private static final Pattern FILECOUNT_PATTERN =
            Pattern.compile("(?:^|,)filecount=(\\d+)(?:,|$)");

    static final Path DEV_STDOUT = Paths.get("/dev/stdout");
    static final Path DEV_STDERR = Paths.get("/dev/stderr");

    private final Logger log = LoggerFactory.getLogger(getClass());

    public GcLogging() {}

    /** Immutable snapshot of the JVM unified logging configuration for GC output. */
    static class State {
        public final boolean enabled;

        @JsonSerialize(using = ToStringSerializer.class)
        public final Path logFilePath;

        public final String what;
        public final String decorators;

        /** Raw {@code output_options} string from {@code vmLog list}, or empty. */
        @JsonIgnore public final String outputOptions;

        State(
                boolean loggingEnabled,
                Path gcLogPath,
                String what,
                String decorators,
                String outputOptions) {
            this.enabled = loggingEnabled;
            this.logFilePath = gcLogPath;
            this.what = what;
            this.decorators = decorators;
            this.outputOptions = outputOptions == null ? "" : outputOptions;
        }

        static State disabled() {
            return new State(false, null, "gc", "time,level", "");
        }

        @JsonIgnore
        boolean isStreamOutput() {
            return DEV_STDOUT.equals(logFilePath) || DEV_STDERR.equals(logFilePath);
        }

        /**
         * Returns the {@code filecount} value from {@code output_options}, or {@link
         * Integer#MAX_VALUE} if not specified (treat as unbounded).
         */
        @JsonIgnore
        int filecount() {
            if (outputOptions.isBlank()) {
                return Integer.MAX_VALUE;
            }
            Matcher m = FILECOUNT_PATTERN.matcher(outputOptions);
            if (!m.find()) {
                return Integer.MAX_VALUE;
            }
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return Integer.MAX_VALUE;
            }
        }
    }

    /**
     * Queries the JVM's unified logging configuration via {@code vmLog list} and returns the
     * current GC logging state. Always reflects the live JVM configuration.
     */
    public State queryState() {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        String output;
        try {
            Object result =
                    server.invoke(
                            ObjectName.getInstance("com.sun.management:type=DiagnosticCommand"),
                            "vmLog",
                            new Object[] {new String[] {"list"}},
                            new String[] {String[].class.getName()});
            output = String.valueOf(result);
        } catch (InstanceNotFoundException
                | MBeanException
                | MalformedObjectNameException
                | ReflectionException e) {
            log.debug("Could not query vmLog list to determine GC log state", e);
            return State.disabled();
        }
        return parseVmLogListOutput(output);
    }

    State parseVmLogListOutput(String output) {
        String lastFileLine = null;
        String lastActiveNonFileLine = null;
        for (String line : output.split("\n")) {
            if (VM_LOG_LIST_FILE_PATTERN.matcher(line).find()) {
                lastFileLine = line;
            } else {
                Matcher nfm = VM_LOG_LIST_NONFILE_PATTERN.matcher(line);
                if (nfm.find() && !"all=off".equals(nfm.group(2))) {
                    lastActiveNonFileLine = line;
                }
            }
        }
        if (lastFileLine != null) {
            Matcher m = VM_LOG_LIST_FILE_PATTERN.matcher(lastFileLine);
            if (!m.find()) {
                return State.disabled();
            }
            return new State(true, Paths.get(m.group(1)), m.group(2), m.group(3), m.group(4));
        } else if (lastActiveNonFileLine != null) {
            Matcher m = VM_LOG_LIST_NONFILE_PATTERN.matcher(lastActiveNonFileLine);
            if (!m.find()) {
                return State.disabled();
            }
            Path streamPath = "stdout".equals(m.group(1)) ? DEV_STDOUT : DEV_STDERR;
            return new State(true, streamPath, m.group(2), m.group(3), "");
        }
        return State.disabled();
    }

    /**
     * Issues a {@code vmLog rotate} to force the JVM to close the current log file and begin
     * writing to a new one, then returns an {@link InputStream} over all rotated (non-current) log
     * files concatenated in chronological order. Log rotation and retention are managed by the
     * JVM's own {@code output_options} (e.g. {@code filecount=10,filesize=100m}). The file at
     * {@code currentPath} is the JVM's active write target after rotation and is always excluded by
     * path identity — not by modification time. The caller is responsible for closing the returned
     * stream.
     */
    public InputStream collectAfterRotate() throws Exception {
        State state = queryState();
        if (!state.enabled || state.logFilePath == null) {
            throw new IllegalStateException("GC logging is not active");
        }
        if (state.isStreamOutput()) {
            return new ByteArrayInputStream(new byte[0]);
        }
        Path currentPath = state.logFilePath;
        issueRotate();
        List<Path> collectedPaths = collectLogPaths(currentPath);
        if (collectedPaths.isEmpty()) {
            if (state.filecount() <= 1) {
                log.debug(
                        "filecount<=1: no rotated files available, reading active log directly"
                                + " (torn reads possible): {}",
                        currentPath);
                return openCollectedLogs(List.of(currentPath));
            }
            log.warn("No rotated GC log files found for: {}", currentPath);
            return new ByteArrayInputStream(new byte[0]);
        }
        return openCollectedLogs(collectedPaths);
    }

    InputStream openCollectedLogs(List<Path> paths) throws IOException {
        List<InputStream> streams = new ArrayList<>();
        boolean hasContent = false;
        try {
            for (Path path : paths) {
                long size = Files.size(path);
                if (size == 0L) {
                    continue;
                }
                streams.add(Files.newInputStream(path));
                hasContent = true;
            }
            if (!hasContent) {
                return new ByteArrayInputStream(new byte[0]);
            }
            return new SequenceInputStream(Collections.enumeration(streams));
        } catch (IOException e) {
            IOException suppressed = null;
            for (InputStream stream : streams) {
                try {
                    stream.close();
                } catch (IOException closeException) {
                    if (suppressed == null) {
                        suppressed = closeException;
                    } else {
                        suppressed.addSuppressed(closeException);
                    }
                }
            }
            if (suppressed != null) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    void issueRotate() throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        try {
            server.invoke(
                    ObjectName.getInstance("com.sun.management:type=DiagnosticCommand"),
                    InvokeContext.VM_LOG,
                    new Object[] {new String[] {"rotate"}},
                    new String[] {String[].class.getName()});
        } catch (InstanceNotFoundException
                | MBeanException
                | MalformedObjectNameException
                | ReflectionException e) {
            throw new Exception("vmLog rotate failed", e);
        }
    }

    /**
     * Returns all rotated sibling log files of {@code currentPath} sorted in oldest-first
     * chronological order. The JVM unified logging rotation scheme names files with a numeric
     * suffix: {@code gc.log.0} holds the most recently closed content, {@code gc.log.1} is older,
     * {@code gc.log.2} older still, and so on. Correct concatenation order is therefore descending
     * by suffix index (highest index = oldest). The file at {@code currentPath} itself is always
     * excluded by path identity: it is the active write target and must never be read.
     */
    List<Path> collectLogPaths(Path currentPath) throws IOException {
        List<Path> paths = new ArrayList<>();
        Path parent = currentPath.getParent();
        Path fileNamePath = currentPath.getFileName();
        if (parent == null || fileNamePath == null || !Files.isDirectory(parent)) {
            return paths;
        }
        String fileName = fileNamePath.toString();
        try (DirectoryStream<Path> stream =
                Files.newDirectoryStream(
                        parent, entry -> isRotatedLog(currentPath, fileName, entry))) {
            for (Path path : stream) {
                paths.add(path);
            }
        }
        paths.sort(Comparator.comparingInt(this::rotationIndex).reversed());
        return paths;
    }

    private boolean isRotatedLog(Path currentPath, String fileName, Path candidate) {
        Path candidateFileName = candidate.getFileName();
        return !currentPath.equals(candidate)
                && candidateFileName != null
                && Files.isRegularFile(candidate)
                && candidateFileName.toString().startsWith(fileName + ".");
    }

    /**
     * Returns the numeric rotation index encoded in a rotated log file's name suffix (e.g. {@code
     * gc.log.3} → {@code 3}). Returns {@code -1} for any file whose suffix is not a non-negative
     * integer so that such files sort before all indexed files when the comparator is reversed.
     */
    private int rotationIndex(Path path) {
        Path fileNamePath = path.getFileName();
        if (fileNamePath == null) {
            return -1;
        }
        String name = fileNamePath.toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return -1;
        }
        String suffix = name.substring(dot + 1);
        try {
            int index = Integer.parseInt(suffix);
            return index >= 0 ? index : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
