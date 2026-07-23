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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import io.cryostat.agent.ConfigModule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import io.smallrye.config.SmallRyeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GcLogContextTest {

    @Mock SmallRyeConfig config;
    @Mock HttpExchange exchange;

    private final ObjectMapper mapper = new ObjectMapper();
    private GcLogging gcLogging;
    private GcLogContext ctx;

    @TempDir Path tempDir;

    @BeforeEach
    void setup() {
        lenient()
                .when(config.getValue(ConfigModule.CRYOSTAT_AGENT_GC_LOG_ENABLED, boolean.class))
                .thenReturn(true);
        gcLogging = spy(new GcLogging());
        ctx = new GcLogContext(mapper, config, gcLogging);
    }

    // -------------------------------------------------------------------------
    // GcLogContext.available / path
    // -------------------------------------------------------------------------

    @Test
    void testAvailableReturnsConfigValue() {
        assertTrue(ctx.available());
    }

    @Test
    void testAvailableFalseWhenDisabled() {
        when(config.getValue(ConfigModule.CRYOSTAT_AGENT_GC_LOG_ENABLED, boolean.class))
                .thenReturn(false);
        assertFalse(ctx.available());
    }

    @Test
    void testPath() {
        assertEquals("/gc-log/", ctx.path());
    }

    // -------------------------------------------------------------------------
    // Status endpoint
    // -------------------------------------------------------------------------

    @Test
    void testStatusWhenDisabled() throws Exception {
        doReturn(GcLogging.State.disabled()).when(gcLogging).queryState();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/gc-log/status"));
        when(exchange.getResponseBody()).thenReturn(baos);

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(200, RemoteContext.BODY_LENGTH_UNKNOWN);
        JsonNode node = mapper.readTree(baos.toByteArray());
        assertFalse(node.get("enabled").asBoolean());
        assertTrue(node.get("logFilePath").isNull());
    }

    @Test
    void testStatusWhenEnabledWithExistingFile() throws Exception {
        Path logFile = tempDir.resolve("gc.log");
        Files.writeString(logFile, "GC log content");
        doReturn(new GcLogging.State(true, logFile, "gc", "uptime")).when(gcLogging).queryState();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/gc-log/status"));
        when(exchange.getResponseBody()).thenReturn(baos);

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(200, RemoteContext.BODY_LENGTH_UNKNOWN);
        JsonNode node = mapper.readTree(baos.toByteArray());
        assertTrue(node.get("enabled").asBoolean());
        assertEquals("uptime", node.get("decorators").asText());
        assertEquals(logFile.toString(), node.get("logFilePath").asText());
    }

    @Test
    void testStatusWhenLoggingToStdout() throws Exception {
        doReturn(
                        new GcLogging.State(
                                true, GcLogging.DEV_STDOUT, "all=warning", "uptime,level,tags"))
                .when(gcLogging)
                .queryState();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/gc-log/status"));
        when(exchange.getResponseBody()).thenReturn(baos);

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(200, RemoteContext.BODY_LENGTH_UNKNOWN);
        JsonNode node = mapper.readTree(baos.toByteArray());
        assertTrue(node.get("enabled").asBoolean());
        assertEquals(GcLogging.DEV_STDOUT.toString(), node.get("logFilePath").asText());
    }

    // -------------------------------------------------------------------------
    // GET (log download) endpoint
    // -------------------------------------------------------------------------

    @Test
    void testGetReturns409WhenNotEnabled() throws Exception {
        doReturn(GcLogging.State.disabled()).when(gcLogging).queryState();
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/gc-log/"));

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(409, RemoteContext.BODY_LENGTH_NONE);
    }

    @Test
    void testGetReturns204WhenLogFileDoesNotExist() throws Exception {
        Path logFile = tempDir.resolve("missing.log");
        doReturn(new GcLogging.State(true, logFile, "gc", "time,level"))
                .when(gcLogging)
                .queryState();
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/gc-log/"));

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(204, RemoteContext.BODY_LENGTH_NONE);
    }

    @Test
    void testGetReturns204WhenLogFileIsEmpty() throws Exception {
        Path logFile = tempDir.resolve("empty.log");
        Files.write(logFile, new byte[0]);
        doReturn(new GcLogging.State(true, logFile, "gc", "time,level"))
                .when(gcLogging)
                .queryState();
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/gc-log/"));

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(204, RemoteContext.BODY_LENGTH_NONE);
    }

    @Test
    void testGetReturns204WhenLoggingToStdout() throws Exception {
        doReturn(
                        new GcLogging.State(
                                true, GcLogging.DEV_STDOUT, "all=warning", "uptime,level,tags"))
                .when(gcLogging)
                .queryState();
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/gc-log/"));

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(204, RemoteContext.BODY_LENGTH_NONE);
    }

    @Test
    void testGetReturns405ForNonGetMethod() throws Exception {
        when(exchange.getRequestMethod()).thenReturn("POST");

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(405, RemoteContext.BODY_LENGTH_NONE);
    }

    // -------------------------------------------------------------------------
    // GcLogging.parseVmLogListOutput
    // -------------------------------------------------------------------------

    @Test
    void testParseVmLogListOutputSetsStateFromLastFileEntry() {
        String output =
                "Available log levels: off, trace, debug, info, warning, error\n"
                        + "Log output configuration:\n"
                        + " #0: stdout all=warning uptime,level,tags (reconfigured)\n"
                        + " #1: stderr all=off uptime,level,tags\n"
                        + " #2: file=/tmp/gc.log all=off,gc=info time,level,tags"
                        + " filecount=5,filesize=20480K,async=false\n"
                        + " #3: file=/tmp/cryostat-gc-12768272396475621478.log all=off,gc=info"
                        + " time,level filecount=5,filesize=20480K,async=false (reconfigured)\n";
        GcLogging.State state = gcLogging.parseVmLogListOutput(output);
        assertTrue(state.enabled);
        assertEquals(
                java.nio.file.Paths.get("/tmp/cryostat-gc-12768272396475621478.log"),
                state.logFilePath);
        assertEquals("all=off,gc=info", state.what);
        assertEquals("time,level", state.decorators);
    }

    @Test
    void testParseVmLogListOutputAllOffNonFileEntriesReturnDisabled() {
        String output =
                "Log output configuration:\n"
                        + " #0: stdout all=off uptime,level,tags\n"
                        + " #1: stderr all=off uptime,level,tags\n";
        GcLogging.State state = gcLogging.parseVmLogListOutput(output);
        assertFalse(state.enabled);
        assertNull(state.logFilePath);
    }

    @Test
    void testParseVmLogListOutputActiveStdoutSetsDevStdoutPath() {
        String output =
                "Log output configuration:\n"
                        + " #0: stdout all=warning uptime,level,tags\n"
                        + " #1: stderr all=off uptime,level,tags\n";
        GcLogging.State state = gcLogging.parseVmLogListOutput(output);
        assertTrue(state.enabled);
        assertEquals(GcLogging.DEV_STDOUT, state.logFilePath);
        assertEquals("all=warning", state.what);
        assertEquals("uptime,level,tags", state.decorators);
    }

    @Test
    void testParseVmLogListOutputActiveStderrSetsDevStderrPath() {
        String output =
                "Log output configuration:\n"
                        + " #0: stdout all=off uptime,level,tags\n"
                        + " #1: stderr all=warning uptime,level\n";
        GcLogging.State state = gcLogging.parseVmLogListOutput(output);
        assertTrue(state.enabled);
        assertEquals(GcLogging.DEV_STDERR, state.logFilePath);
        assertEquals("all=warning", state.what);
        assertEquals("uptime,level", state.decorators);
    }

    @Test
    void testParseVmLogListOutputFileEntryTakesPrecedenceOverActiveStdout() {
        String output =
                "Log output configuration:\n"
                        + " #0: stdout all=warning uptime,level,tags\n"
                        + " #2: file=/tmp/gc.log all=off,gc=info time,level"
                        + " filecount=5,filesize=20480K,async=false\n";
        GcLogging.State state = gcLogging.parseVmLogListOutput(output);
        assertTrue(state.enabled);
        assertEquals(java.nio.file.Paths.get("/tmp/gc.log"), state.logFilePath);
        assertEquals("all=off,gc=info", state.what);
        assertEquals("time,level", state.decorators);
    }

    @Test
    void testParseVmLogListOutputUsesLastFileEntryWhenMultiplePresent() {
        String output =
                "Log output configuration:\n"
                        + " #0: stdout all=warning uptime,level,tags\n"
                        + " #2: file=/tmp/gc.log all=off,gc=info uptime,level"
                        + " filecount=5,filesize=20480K,async=false\n"
                        + " #3: file=/tmp/cryostat-gc-latest.log all=off,gc=debug time"
                        + " filecount=5,filesize=20480K,async=false\n";
        GcLogging.State state = gcLogging.parseVmLogListOutput(output);
        assertTrue(state.enabled);
        assertEquals(java.nio.file.Paths.get("/tmp/cryostat-gc-latest.log"), state.logFilePath);
        assertEquals("all=off,gc=debug", state.what);
        assertEquals("time", state.decorators);
    }

    @Test
    void testParseVmLogListOutputEmptyOutputReturnsDisabled() {
        GcLogging.State state = gcLogging.parseVmLogListOutput("");
        assertFalse(state.enabled);
        assertNull(state.logFilePath);
        assertEquals("time,level", state.decorators);
        assertEquals("gc", state.what);
    }

    // -------------------------------------------------------------------------
    // GcLogging.collectAndRedirect (stream-output guard)
    // -------------------------------------------------------------------------

    @Test
    void testCollectAndRedirectReturnsEmptyStreamForStdout() throws Exception {
        doReturn(
                        new GcLogging.State(
                                true, GcLogging.DEV_STDOUT, "all=warning", "uptime,level,tags"))
                .when(gcLogging)
                .queryState();
        try (InputStream stream = gcLogging.collectAndRedirect()) {
            assertEquals(0, stream.readAllBytes().length);
        }
    }

    @Test
    void testCollectAndRedirectReturnsEmptyStreamForStderr() throws Exception {
        doReturn(
                        new GcLogging.State(
                                true, GcLogging.DEV_STDERR, "all=warning", "uptime,level,tags"))
                .when(gcLogging)
                .queryState();
        try (InputStream stream = gcLogging.collectAndRedirect()) {
            assertEquals(0, stream.readAllBytes().length);
        }
    }

    // -------------------------------------------------------------------------
    // GcLogging.openCollectedLogs / collectLogPaths
    // -------------------------------------------------------------------------

    @Test
    void testCollectAndRedirectConcatenatesRotatedLogsAndDeletesThem() throws Exception {
        Path logFile = tempDir.resolve("gc.log");
        Path rotatedOne = tempDir.resolve("gc.log.1");
        Path rotatedTwo = tempDir.resolve("gc.log.2");
        Files.writeString(logFile, "current");
        Files.writeString(rotatedOne, "rotated-one");
        Files.writeString(rotatedTwo, "rotated-two");
        Files.setLastModifiedTime(logFile, java.nio.file.attribute.FileTime.fromMillis(30L));
        Files.setLastModifiedTime(rotatedOne, java.nio.file.attribute.FileTime.fromMillis(10L));
        Files.setLastModifiedTime(rotatedTwo, java.nio.file.attribute.FileTime.fromMillis(20L));

        try (InputStream stream = gcLogging.openCollectedLogs(gcLogging.collectLogPaths(logFile))) {
            assertEquals("rotated-onerotated-twocurrent", new String(stream.readAllBytes()));
        }

        assertFalse(Files.exists(logFile));
        assertFalse(Files.exists(rotatedOne));
        assertFalse(Files.exists(rotatedTwo));
    }

    @Test
    void testCollectLogPathsUsesPrefixAndLastModifiedOrdering() throws Exception {
        Path logFile = tempDir.resolve("gc.log");
        Path rotatedOlder = tempDir.resolve("gc.log.9");
        Path rotatedNewer = tempDir.resolve("gc.log.current");
        Path ignored = tempDir.resolve("other.log.1");
        Files.writeString(logFile, "current");
        Files.writeString(rotatedOlder, "older");
        Files.writeString(rotatedNewer, "newer");
        Files.writeString(ignored, "ignored");
        Files.setLastModifiedTime(logFile, java.nio.file.attribute.FileTime.fromMillis(30L));
        Files.setLastModifiedTime(rotatedOlder, java.nio.file.attribute.FileTime.fromMillis(10L));
        Files.setLastModifiedTime(rotatedNewer, java.nio.file.attribute.FileTime.fromMillis(20L));

        assertIterableEquals(
                java.util.List.of(rotatedOlder, rotatedNewer, logFile),
                gcLogging.collectLogPaths(logFile));
    }
}
