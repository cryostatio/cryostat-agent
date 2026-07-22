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
        gcLogging = new GcLogging();
        ctx = new GcLogContext(mapper, config, gcLogging);
    }

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

    @Test
    void testOnVmLogInvokedEnablesLogging() {
        Path logFile = tempDir.resolve("gc.log");
        gcLogging.onVmLogInvoked(
                new Object[] {new String[] {"what=gc decorators=time,level output=" + logFile}});
        assertTrue(gcLogging.loggingEnabled);
        assertEquals(logFile, gcLogging.gcLogPath);
        assertEquals("time,level", gcLogging.decorators);
    }

    @Test
    void testOnVmLogInvokedDisablesLogging() {
        Path logFile = tempDir.resolve("gc.log");
        gcLogging.onVmLogInvoked(new Object[] {new String[] {"what=gc output=" + logFile}});
        assertTrue(gcLogging.loggingEnabled);

        gcLogging.onVmLogInvoked(new Object[] {new String[] {"disable=true"}});
        assertFalse(gcLogging.loggingEnabled);
        assertNull(gcLogging.gcLogPath);
        assertEquals("time,level", gcLogging.decorators);
    }

    @Test
    void testOnVmLogInvokedWithNullParameters() {
        gcLogging.onVmLogInvoked(null);
        assertFalse(gcLogging.loggingEnabled);
    }

    @Test
    void testOnVmLogInvokedWithEmptyParameters() {
        gcLogging.onVmLogInvoked(new Object[0]);
        assertFalse(gcLogging.loggingEnabled);
    }

    @Test
    void testOnVmLogInvokedWithEmptyStringArray() {
        gcLogging.onVmLogInvoked(new Object[] {new String[0]});
        assertFalse(gcLogging.loggingEnabled);
    }

    @Test
    void testStatusWhenDisabled() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/gc-log/status"));
        when(exchange.getResponseBody()).thenReturn(baos);

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(200, RemoteContext.BODY_LENGTH_UNKNOWN);
        JsonNode node = mapper.readTree(baos.toByteArray());
        assertFalse(node.get("enabled").asBoolean());
        assertFalse(node.get("hasLog").asBoolean());
    }

    @Test
    void testStatusWhenEnabledWithExistingFile() throws Exception {
        Path logFile = tempDir.resolve("gc.log");
        Files.writeString(logFile, "GC log content");
        gcLogging.onVmLogInvoked(
                new Object[] {new String[] {"what=gc decorators=uptime output=" + logFile}});

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/gc-log/status"));
        when(exchange.getResponseBody()).thenReturn(baos);

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(200, RemoteContext.BODY_LENGTH_UNKNOWN);
        JsonNode node = mapper.readTree(baos.toByteArray());
        assertTrue(node.get("enabled").asBoolean());
        assertTrue(node.get("hasLog").asBoolean());
        assertEquals("uptime", node.get("decorators").asText());
    }

    @Test
    void testGetReturns409WhenNotEnabled() throws Exception {
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/gc-log/"));

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(409, RemoteContext.BODY_LENGTH_NONE);
    }

    @Test
    void testGetReturns405ForNonGetMethod() throws Exception {
        when(exchange.getRequestMethod()).thenReturn("POST");

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(405, RemoteContext.BODY_LENGTH_NONE);
    }

    @Test
    void testOnVmLogInvokedWithoutDecorators() {
        Path logFile = tempDir.resolve("gc.log");
        gcLogging.onVmLogInvoked(new Object[] {new String[] {"what=gc output=" + logFile}});
        assertTrue(gcLogging.loggingEnabled);
        assertEquals(logFile, gcLogging.gcLogPath);
        assertEquals("time,level", gcLogging.decorators);
    }
}
