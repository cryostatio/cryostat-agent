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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UnifiedLogContextTest {

    @Mock HttpExchange exchange;

    private final ObjectMapper mapper = new ObjectMapper();
    private UnifiedLogging logging;
    private UnifiedLogContext ctx;

    @TempDir Path tempDir;

    @BeforeEach
    void setup() {
        logging = spy(new UnifiedLogging());
        ctx = new UnifiedLogContext(mapper, true, logging);
    }

    // -------------------------------------------------------------------------
    // UnifiedLogContext.available / path
    // -------------------------------------------------------------------------

    @Test
    void testAvailableReturnsConfigValue() {
        assertTrue(ctx.available());
    }

    @Test
    void testAvailableFalseWhenDisabled() {
        UnifiedLogContext disabledCtx = new UnifiedLogContext(mapper, false, logging);
        assertFalse(disabledCtx.available());
    }

    @Test
    void testPath() {
        assertEquals("/unified-log/", ctx.path());
    }

    // -------------------------------------------------------------------------
    // Status endpoint
    // -------------------------------------------------------------------------

    @Test
    void testStatusWhenDisabled() throws Exception {
        doReturn(UnifiedLogging.State.disabled()).when(logging).queryState();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/unified-log/status"));
        when(exchange.getResponseBody()).thenReturn(baos);

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(200, RemoteContext.BODY_LENGTH_UNKNOWN);
        JsonNode node = mapper.readTree(baos.toByteArray());
        assertFalse(node.get("enabled").asBoolean());
        assertTrue(node.get("logFilePath").isNull());
    }

    @Test
    void testStatusWhenEnabledWithExistingFile() throws Exception {
        Path logFile = tempDir.resolve("test.log");
        Files.writeString(logFile, "log content");
        doReturn(new UnifiedLogging.State(true, logFile, "gc", "uptime", ""))
                .when(logging)
                .queryState();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/unified-log/status"));
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
                        new UnifiedLogging.State(
                                true,
                                UnifiedLogging.DEV_STDOUT,
                                "all=warning",
                                "uptime,level,tags",
                                ""))
                .when(logging)
                .queryState();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/unified-log/status"));
        when(exchange.getResponseBody()).thenReturn(baos);

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(200, RemoteContext.BODY_LENGTH_UNKNOWN);
        JsonNode node = mapper.readTree(baos.toByteArray());
        assertTrue(node.get("enabled").asBoolean());
        assertEquals(UnifiedLogging.DEV_STDOUT.toString(), node.get("logFilePath").asText());
    }

    // -------------------------------------------------------------------------
    // GET (log download) endpoint
    // -------------------------------------------------------------------------

    @Test
    void testGetReturns404WhenNotEnabled() throws Exception {
        doThrow(new UnifiedLogException("Logging is not active"))
                .when(logging)
                .collectAfterRotate();
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/unified-log/"));

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(404, RemoteContext.BODY_LENGTH_NONE);
    }

    @Test
    void testGetReturns204WhenNoRotatedFiles() throws Exception {
        doReturn(new ByteArrayInputStream(new byte[0])).when(logging).collectAfterRotate();
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/unified-log/"));

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(204, RemoteContext.BODY_LENGTH_NONE);
        verify(exchange, never()).getResponseBody();
    }

    @Test
    void testGetReturns204WhenActiveLogFileIsEmpty() throws Exception {
        doReturn(new ByteArrayInputStream(new byte[0])).when(logging).collectAfterRotate();
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/unified-log/"));

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(204, RemoteContext.BODY_LENGTH_NONE);
        verify(exchange, never()).getResponseBody();
    }

    @Test
    void testGetReturns200WithContentWhenRotatedFilesExist() throws Exception {
        byte[] content = "log content".getBytes();
        doReturn(new ByteArrayInputStream(content)).when(logging).collectAfterRotate();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/unified-log/"));
        when(exchange.getResponseBody()).thenReturn(baos);

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(200, RemoteContext.BODY_LENGTH_UNKNOWN);
        assertArrayEquals(content, baos.toByteArray());
    }

    @Test
    void testGetReturns204WhenLoggingToStdout() throws Exception {
        doReturn(
                        new UnifiedLogging.State(
                                true,
                                UnifiedLogging.DEV_STDOUT,
                                "all=warning",
                                "uptime,level,tags",
                                ""))
                .when(logging)
                .queryState();
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/unified-log/"));

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
    // UnifiedLogging.parseVmLogListOutput
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
                        + " #3: file=/tmp/cryostat-12768272396475621478.log all=off,gc=info"
                        + " time,level filecount=5,filesize=20480K,async=false (reconfigured)\n";
        UnifiedLogging.State state = logging.parseVmLogListOutput(output);
        assertTrue(state.enabled);
        assertEquals(Paths.get("/tmp/cryostat-12768272396475621478.log"), state.logFilePath);
        assertEquals("all=off,gc=info", state.what);
        assertEquals("time,level", state.decorators);
        assertEquals("filecount=5,filesize=20480K,async=false", state.outputOptions);
    }

    @Test
    void testParseVmLogListOutputAllOffNonFileEntriesReturnDisabled() {
        String output =
                "Log output configuration:\n"
                        + " #0: stdout all=off uptime,level,tags\n"
                        + " #1: stderr all=off uptime,level,tags\n";
        UnifiedLogging.State state = logging.parseVmLogListOutput(output);
        assertFalse(state.enabled);
        assertNull(state.logFilePath);
    }

    @Test
    void testParseVmLogListOutputActiveStdoutSetsDevStdoutPath() {
        String output =
                "Log output configuration:\n"
                        + " #0: stdout all=warning uptime,level,tags\n"
                        + " #1: stderr all=off uptime,level,tags\n";
        UnifiedLogging.State state = logging.parseVmLogListOutput(output);
        assertTrue(state.enabled);
        assertEquals(UnifiedLogging.DEV_STDOUT, state.logFilePath);
        assertEquals("all=warning", state.what);
        assertEquals("uptime,level,tags", state.decorators);
    }

    @Test
    void testParseVmLogListOutputActiveStderrSetsDevStderrPath() {
        String output =
                "Log output configuration:\n"
                        + " #0: stdout all=off uptime,level,tags\n"
                        + " #1: stderr all=warning uptime,level\n";
        UnifiedLogging.State state = logging.parseVmLogListOutput(output);
        assertTrue(state.enabled);
        assertEquals(UnifiedLogging.DEV_STDERR, state.logFilePath);
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
        UnifiedLogging.State state = logging.parseVmLogListOutput(output);
        assertTrue(state.enabled);
        assertEquals(Paths.get("/tmp/gc.log"), state.logFilePath);
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
                        + " #3: file=/tmp/cryostat-latest.log all=off,gc=debug time"
                        + " filecount=5,filesize=20480K,async=false\n";
        UnifiedLogging.State state = logging.parseVmLogListOutput(output);
        assertTrue(state.enabled);
        assertEquals(Paths.get("/tmp/cryostat-latest.log"), state.logFilePath);
        assertEquals("all=off,gc=debug", state.what);
        assertEquals("time", state.decorators);
    }

    @Test
    void testParseVmLogListOutputEmptyOutputReturnsDisabled() {
        UnifiedLogging.State state = logging.parseVmLogListOutput("");
        assertFalse(state.enabled);
        assertNull(state.logFilePath);
        assertEquals("", state.decorators);
        assertEquals("", state.what);
    }

    // -------------------------------------------------------------------------
    // UnifiedLogging.collectAfterRotate (stream-output guard)
    // -------------------------------------------------------------------------

    @Test
    void testCollectAfterRotateReturnsEmptyStreamForStdout() throws Exception {
        doReturn(
                        new UnifiedLogging.State(
                                true,
                                UnifiedLogging.DEV_STDOUT,
                                "all=warning",
                                "uptime,level,tags",
                                ""))
                .when(logging)
                .queryState();
        try (InputStream stream = logging.collectAfterRotate()) {
            assertEquals(0, stream.readAllBytes().length);
        }
    }

    @Test
    void testCollectAfterRotateReturnsEmptyStreamForStderr() throws Exception {
        doReturn(
                        new UnifiedLogging.State(
                                true,
                                UnifiedLogging.DEV_STDERR,
                                "all=warning",
                                "uptime,level,tags",
                                ""))
                .when(logging)
                .queryState();
        try (InputStream stream = logging.collectAfterRotate()) {
            assertEquals(0, stream.readAllBytes().length);
        }
    }

    // -------------------------------------------------------------------------
    // UnifiedLogging.openCollectedLogs / collectLogPaths
    // -------------------------------------------------------------------------

    @Test
    void testOpenCollectedLogsConcatenatesRotatedLogsOldestFirst() throws Exception {
        Path logFile = tempDir.resolve("test.log");
        Path rotatedOldest = tempDir.resolve("test.log.2");
        Path rotatedMiddle = tempDir.resolve("test.log.0");
        Path rotatedNewest = tempDir.resolve("test.log.1");
        Files.writeString(logFile, "current");
        Files.writeString(rotatedOldest, "oldest");
        Files.setLastModifiedTime(rotatedOldest, FileTime.from(Instant.ofEpochSecond(1000)));
        Files.writeString(rotatedMiddle, "middle");
        Files.setLastModifiedTime(rotatedMiddle, FileTime.from(Instant.ofEpochSecond(2000)));
        Files.writeString(rotatedNewest, "newest-sealed");
        Files.setLastModifiedTime(rotatedNewest, FileTime.from(Instant.ofEpochSecond(3000)));

        try (InputStream stream = logging.openCollectedLogs(logging.collectLogPaths(logFile))) {
            assertEquals("oldestmiddlenewest-sealed", new String(stream.readAllBytes()));
        }

        assertTrue(Files.exists(rotatedOldest));
        assertTrue(Files.exists(rotatedMiddle));
        assertTrue(Files.exists(rotatedNewest));
        assertTrue(Files.exists(logFile));
    }

    @Test
    void testCollectLogPathsExcludesCurrentPathAndOrdersByModificationTime() throws Exception {
        Path logFile = tempDir.resolve("test.log");
        Path fileA = tempDir.resolve("test.log.0");
        Path fileB = tempDir.resolve("test.log.3");
        Path fileC = tempDir.resolve("test.log.9");
        Path ignored = tempDir.resolve("other.log.1");
        Files.writeString(logFile, "current");
        Files.writeString(fileA, "newest-sealed");
        Files.setLastModifiedTime(fileA, FileTime.from(Instant.ofEpochSecond(3000)));
        Files.writeString(fileB, "middle");
        Files.setLastModifiedTime(fileB, FileTime.from(Instant.ofEpochSecond(2000)));
        Files.writeString(fileC, "oldest");
        Files.setLastModifiedTime(fileC, FileTime.from(Instant.ofEpochSecond(1000)));
        Files.writeString(ignored, "ignored");

        assertIterableEquals(
                java.util.List.of(fileC, fileB, fileA), logging.collectLogPaths(logFile));
    }

    @Test
    void testCollectLogPathsRequiresDotSeparatorBeforeIndex() throws Exception {
        Path logFile = tempDir.resolve("test.log");
        Path validRotated = tempDir.resolve("test.log.0");
        Path noSeparator = tempDir.resolve("test.log0");
        Files.writeString(logFile, "current");
        Files.writeString(validRotated, "rotated");
        Files.writeString(noSeparator, "not-rotated");

        assertIterableEquals(java.util.List.of(validRotated), logging.collectLogPaths(logFile));
    }

    // -------------------------------------------------------------------------
    // UnifiedLogging.collectAfterRotate — active file always appended
    // -------------------------------------------------------------------------

    @Test
    void testCollectAfterRotateAlwaysIncludesActiveFileLast() throws Exception {
        Path logFile = tempDir.resolve("test.log");
        Path rotated = tempDir.resolve("test.log.0");
        Files.writeString(rotated, "sealed");
        Files.setLastModifiedTime(rotated, FileTime.from(Instant.ofEpochSecond(1000)));
        Files.writeString(logFile, "active");
        doReturn(new UnifiedLogging.State(true, logFile, "gc", "uptime", "filecount=5,filesize=1m"))
                .when(logging)
                .queryState();
        doNothing().when(logging).issueRotate();

        try (InputStream stream = logging.collectAfterRotate()) {
            assertEquals("sealedactive", new String(stream.readAllBytes()));
        }
    }

    @Test
    void testCollectAfterRotateIncludesActiveFileEvenWhenNoRotatedFilesExist() throws Exception {
        Path logFile = tempDir.resolve("test.log");
        Files.writeString(logFile, "live-content");
        doReturn(new UnifiedLogging.State(true, logFile, "gc", "uptime", "filecount=1,filesize=1m"))
                .when(logging)
                .queryState();
        doNothing().when(logging).issueRotate();

        try (InputStream stream = logging.collectAfterRotate()) {
            assertEquals("live-content", new String(stream.readAllBytes()));
        }
    }
}
