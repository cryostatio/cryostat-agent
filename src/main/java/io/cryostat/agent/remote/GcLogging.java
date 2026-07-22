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

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    private final Logger log = LoggerFactory.getLogger(getClass());

    volatile Path gcLogPath = null;
    volatile boolean loggingEnabled = false;
    volatile String decorators = "time,level";
    volatile String what = "gc";

    public GcLogging() {}

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

    /**
     * Redirects JVM GC logging to a fresh temp file, closing the current log at {@code
     * currentPath}. Returns an {@link InputStream} over the closed log content. The caller is
     * responsible for closing the stream.
     */
    public InputStream collectAndRedirect() throws Exception {
        if (!loggingEnabled || gcLogPath == null) {
            throw new IllegalStateException("GC logging is not active");
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
        if (!Files.exists(currentPath)) {
            log.warn("GC log file not found after redirect: {}", currentPath);
            throw new IllegalStateException("GC log file missing after redirect: " + currentPath);
        }
        return DeletingInputStream.of(currentPath);
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
