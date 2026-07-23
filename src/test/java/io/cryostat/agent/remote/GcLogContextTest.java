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
        doReturn(new GcLogging.State(true, logFile, "gc", "uptime", ""))
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
        assertEquals("uptime", node.get("decorators").asText());
        assertEquals(logFile.toString(), node.get("logFilePath").asText());
    }

    @Test
    void testStatusWhenLoggingToStdout() throws Exception {
        doReturn(
                        new GcLogging.State(
                                true, GcLogging.DEV_STDOUT, "all=warning", "uptime,level,tags", ""))
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
    void testGetReturns200WithEmptyBodyWhenNoRotatedFiles() throws Exception {
        Path logFile = tempDir.resolve("gc.log");
        doReturn(new GcLogging.State(true, logFile, "gc", "time,level", ""))
                .when(gcLogging)
                .queryState();
        doReturn(new java.io.ByteArrayInputStream(new byte[0]))
                .when(gcLogging)
                .collectAfterRotate();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/gc-log/"));
        when(exchange.getResponseBody()).thenReturn(baos);

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(200, RemoteContext.BODY_LENGTH_UNKNOWN);
        assertEquals(0, baos.size());
    }

    @Test
    void testGetReturns204WhenLoggingToStdout() throws Exception {
        doReturn(
                        new GcLogging.State(
                                true, GcLogging.DEV_STDOUT, "all=warning", "uptime,level,tags", ""))
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
        assertEquals("filecount=5,filesize=20480K,async=false", state.outputOptions);
        assertEquals(5, state.filecount());
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
    // GcLogging.collectAfterRotate (stream-output guard)
    // -------------------------------------------------------------------------

    @Test
    void testCollectAfterRotateReturnsEmptyStreamForStdout() throws Exception {
        doReturn(
                        new GcLogging.State(
                                true, GcLogging.DEV_STDOUT, "all=warning", "uptime,level,tags", ""))
                .when(gcLogging)
                .queryState();
        try (InputStream stream = gcLogging.collectAfterRotate()) {
            assertEquals(0, stream.readAllBytes().length);
        }
    }

    @Test
    void testCollectAfterRotateReturnsEmptyStreamForStderr() throws Exception {
        doReturn(
                        new GcLogging.State(
                                true, GcLogging.DEV_STDERR, "all=warning", "uptime,level,tags", ""))
                .when(gcLogging)
                .queryState();
        try (InputStream stream = gcLogging.collectAfterRotate()) {
            assertEquals(0, stream.readAllBytes().length);
        }
    }

    // -------------------------------------------------------------------------
    // GcLogging.openCollectedLogs / collectLogPaths
    // -------------------------------------------------------------------------

    @Test
    void testOpenCollectedLogsConcatenatesRotatedLogsOldestFirst() throws Exception {
        Path logFile = tempDir.resolve("gc.log");
        Path rotatedIndex0 = tempDir.resolve("gc.log.0");
        Path rotatedIndex1 = tempDir.resolve("gc.log.1");
        Path rotatedIndex2 = tempDir.resolve("gc.log.2");
        Files.writeString(logFile, "current");
        Files.writeString(rotatedIndex0, "newest-sealed");
        Files.writeString(rotatedIndex1, "middle");
        Files.writeString(rotatedIndex2, "oldest");

        try (InputStream stream = gcLogging.openCollectedLogs(gcLogging.collectLogPaths(logFile))) {
            assertEquals("oldestmiddlenewest-sealed", new String(stream.readAllBytes()));
        }

        assertTrue(Files.exists(rotatedIndex0));
        assertTrue(Files.exists(rotatedIndex1));
        assertTrue(Files.exists(rotatedIndex2));
        assertTrue(Files.exists(logFile));
    }

    @Test
    void testCollectLogPathsExcludesCurrentPathAndOrdersByRotationIndex() throws Exception {
        Path logFile = tempDir.resolve("gc.log");
        Path index0 = tempDir.resolve("gc.log.0");
        Path index3 = tempDir.resolve("gc.log.3");
        Path index9 = tempDir.resolve("gc.log.9");
        Path ignored = tempDir.resolve("other.log.1");
        Files.writeString(logFile, "current");
        Files.writeString(index0, "newest-sealed");
        Files.writeString(index3, "middle");
        Files.writeString(index9, "oldest");
        Files.writeString(ignored, "ignored");

        assertIterableEquals(
                java.util.List.of(index9, index3, index0), gcLogging.collectLogPaths(logFile));
    }

    @Test
    void testCollectLogPathsRequiresDotSeparatorBeforeIndex() throws Exception {
        Path logFile = tempDir.resolve("gc.log");
        Path validRotated = tempDir.resolve("gc.log.0");
        Path noSeparator = tempDir.resolve("gc.log0");
        Files.writeString(logFile, "current");
        Files.writeString(validRotated, "rotated");
        Files.writeString(noSeparator, "not-rotated");

        assertIterableEquals(java.util.List.of(validRotated), gcLogging.collectLogPaths(logFile));
    }

    // -------------------------------------------------------------------------
    // GcLogging.State.filecount()
    // -------------------------------------------------------------------------

    @Test
    void testFilecountParsedFromOutputOptions() {
        GcLogging.State state =
                new GcLogging.State(true, null, "gc", "uptime", "filecount=3,filesize=1m");
        assertEquals(3, state.filecount());
    }

    @Test
    void testFilecountReturnsMaxValueWhenOutputOptionsEmpty() {
        GcLogging.State state = new GcLogging.State(true, null, "gc", "uptime", "");
        assertEquals(Integer.MAX_VALUE, state.filecount());
    }

    @Test
    void testFilecountReturnsMaxValueWhenNoFilecountKeyPresent() {
        GcLogging.State state =
                new GcLogging.State(true, null, "gc", "uptime", "filesize=1m,async=false");
        assertEquals(Integer.MAX_VALUE, state.filecount());
    }

    // -------------------------------------------------------------------------
    // GcLogging.collectAfterRotate — filecount=1 fallback
    // -------------------------------------------------------------------------

    @Test
    void testCollectAfterRotateFallsBackToActiveFileWhenFilecountIsOne() throws Exception {
        Path logFile = tempDir.resolve("gc.log");
        Files.writeString(logFile, "live-content");
        doReturn(new GcLogging.State(true, logFile, "gc", "uptime", "filecount=1,filesize=1m"))
                .when(gcLogging)
                .queryState();
        doNothing().when(gcLogging).issueRotate();

        try (InputStream stream = gcLogging.collectAfterRotate()) {
            assertEquals("live-content", new String(stream.readAllBytes()));
        }
    }
}
