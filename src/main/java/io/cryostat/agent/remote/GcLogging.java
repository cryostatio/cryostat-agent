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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GcLogging {

    private static final Pattern OUTPUT_PATTERN = Pattern.compile("(?:^|\\s)output=([^\\s]+)");
    private static final Pattern DECORATORS_PATTERN =
            Pattern.compile("(?:^|\\s)decorators=([^\\s]+)");
    private static final Pattern WHAT_PATTERN = Pattern.compile("(?:^|\\s)what=([^\\s]+)");
    private static final Pattern VM_LOG_LIST_FILE_PATTERN =
            Pattern.compile("^\\s*#\\d+: file=(\\S+) (\\S+) (\\S+)");
    private static final Pattern VM_LOG_LIST_NONFILE_PATTERN =
            Pattern.compile("^\\s*#\\d+: (stdout|stderr) (\\S+) (\\S+)");

    static final Path DEV_STDOUT = Paths.get("/dev/stdout");
    static final Path DEV_STDERR = Paths.get("/dev/stderr");

    private final Logger log = LoggerFactory.getLogger(getClass());

    volatile Path gcLogPath = null;
    volatile boolean loggingEnabled = false;
    volatile String decorators = "time,level";
    volatile String what = "gc";

    public GcLogging() {}

    public void loadInitialState() {
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
            log.debug("Could not query vmLog list to determine initial GC log state", e);
            return;
        }
        applyVmLogListOutput(output);
    }

    void applyVmLogListOutput(String output) {
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
                return;
            }
            gcLogPath = Paths.get(m.group(1));
            what = m.group(2);
            decorators = m.group(3);
            loggingEnabled = true;
            log.debug(
                    "Initialized GC logging state from vmLog list: path={}, what={}, decorators={}",
                    gcLogPath,
                    what,
                    decorators);
        } else if (lastActiveNonFileLine != null) {
            Matcher m = VM_LOG_LIST_NONFILE_PATTERN.matcher(lastActiveNonFileLine);
            if (!m.find()) {
                return;
            }
            gcLogPath = "stdout".equals(m.group(1)) ? DEV_STDOUT : DEV_STDERR;
            what = m.group(2);
            decorators = m.group(3);
            loggingEnabled = true;
            log.debug(
                    "Initialized GC logging state from vmLog list (stream output): path={},"
                            + " what={}, decorators={}",
                    gcLogPath,
                    what,
                    decorators);
        } else {
            log.debug("No active VM log output found in vmLog list output");
        }
    }

    public void onVmLogInvoked(Object[] parameters) {
        if (parameters == null || parameters.length == 0) {
            return;
        }
        String args;
        if (parameters[0] instanceof String[]) {
            String[] strArr = (String[]) parameters[0];
            if (strArr.length == 0) {
                return;
            }
            args = strArr[0];
        } else {
            args = String.valueOf(parameters[0]);
        }
        if (args.contains("disable=true")) {
            loggingEnabled = false;
            gcLogPath = null;
            decorators = "time,level";
            what = "gc";
            log.debug("GC logging disabled");
            return;
        }
        Matcher outputMatcher = OUTPUT_PATTERN.matcher(args);
        if (outputMatcher.find()) {
            gcLogPath = Paths.get(outputMatcher.group(1));
            loggingEnabled = true;
            Matcher decoratorsMatcher = DECORATORS_PATTERN.matcher(args);
            if (decoratorsMatcher.find()) {
                decorators = decoratorsMatcher.group(1);
            }
            Matcher whatMatcher = WHAT_PATTERN.matcher(args);
            if (whatMatcher.find()) {
                what = whatMatcher.group(1);
            }
            log.debug(
                    "GC logging enabled, path={}, what={}, decorators={}",
                    gcLogPath,
                    what,
                    decorators);
        }
    }

    boolean isStreamOutput() {
        return DEV_STDOUT.equals(gcLogPath) || DEV_STDERR.equals(gcLogPath);
    }

    /**
     * Redirects JVM GC logging to a fresh temp file, closing the current log at {@code
     * currentPath}. Returns an {@link InputStream} over the closed log content. The caller is
     * responsible for closing the stream.
     */
    public InputStream collectAndRedirect() throws Exception {
        if (!loggingEnabled || gcLogPath == null) {
            throw new IllegalStateException("GC logging is not active");
        }
        if (isStreamOutput()) {
            return new ByteArrayInputStream(new byte[0]);
        }
        Path currentPath = gcLogPath;
        Path nextPath = Files.createTempFile("cryostat-gc-", ".log");
        Files.delete(nextPath);
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        String redirectArg = "what=" + what + " decorators=" + decorators + " output=" + nextPath;
        try {
            server.invoke(
                    ObjectName.getInstance("com.sun.management:type=DiagnosticCommand"),
                    InvokeContext.VM_LOG,
                    new Object[] {new String[] {redirectArg}},
                    new String[] {String[].class.getName()});
        } catch (InstanceNotFoundException
                | MBeanException
                | MalformedObjectNameException
                | ReflectionException e) {
            try {
                Files.deleteIfExists(nextPath);
            } catch (IOException ignored) {
            }
            throw new Exception("VM.log redirect failed", e);
        }
        gcLogPath = nextPath;
        List<Path> collectedPaths = collectLogPaths(currentPath);
        if (collectedPaths.isEmpty()) {
            log.warn("GC log file not found after redirect: {}", currentPath);
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
                    Files.deleteIfExists(path);
                    continue;
                }
                streams.add(DeletingInputStream.of(path));
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

    List<Path> collectLogPaths(Path currentPath) throws IOException {
        List<Path> paths = new ArrayList<>();
        if (Files.exists(currentPath)) {
            paths.add(currentPath);
        }
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
        paths.sort(Comparator.comparingLong(this::lastModified));
        return paths;
    }

    private boolean isRotatedLog(Path currentPath, String fileName, Path candidate) {
        Path candidateFileName = candidate.getFileName();
        return !currentPath.equals(candidate)
                && candidateFileName != null
                && Files.isRegularFile(candidate)
                && candidateFileName.toString().startsWith(fileName);
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static class DeletingInputStream extends InputStream {

        private final InputStream delegate;
        private final Path path;

        private DeletingInputStream(InputStream delegate, Path path) {
            this.delegate = delegate;
            this.path = path;
        }

        static DeletingInputStream of(Path path) throws IOException {
            return new DeletingInputStream(Files.newInputStream(path), path);
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return delegate.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            try {
                delegate.close();
            } finally {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
