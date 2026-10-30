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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;

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
class AsyncProfilerContextTest {

    @Mock HttpExchange exchange;
    @Mock SmallRyeConfig config;
    @Mock ScheduledExecutorService scheduler;
    @Mock AsyncProfilerExecutor executor;

    private final ObjectMapper mapper = new ObjectMapper();
    private AsyncProfilerContext ctx;

    @BeforeEach
    void setup(@TempDir Path repository) {
        ctx = new AsyncProfilerContext(config, mapper, scheduler, executor, repository);
    }

    @Test
    void testStatusRequestSendsServiceUnavailableWhenProfilerNotAvailable() throws Exception {
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(executor.isAvailable()).thenReturn(false);

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(503, RemoteContext.BODY_LENGTH_NONE);
    }

    @Test
    void testPostRequestSendsServiceUnavailableWhenProfilerNotAvailable() throws Exception {
        when(exchange.getRequestMethod()).thenReturn("POST");
        when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        when(executor.isAvailable()).thenReturn(false);

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(503, RemoteContext.BODY_LENGTH_NONE);
    }

    @Test
    void testGetWithUnrecognizedIdSendsNotFound() throws Exception {
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(executor.isAvailable()).thenReturn(true);
        when(exchange.getRequestURI()).thenReturn(URI.create("/async-profiler/nonexistent"));

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(404, RemoteContext.BODY_LENGTH_NONE);
    }

    @Test
    void testDeleteWithUnrecognizedIdSendsNotFound() throws Exception {
        when(exchange.getRequestMethod()).thenReturn("DELETE");
        when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        when(executor.isAvailable()).thenReturn(true);
        when(exchange.getRequestURI()).thenReturn(URI.create("/async-profiler/nonexistent"));

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(404, RemoteContext.BODY_LENGTH_NONE);
    }

    @Test
    void testPostWithUnrecognizedPathSendsNotFound() throws Exception {
        when(exchange.getRequestMethod()).thenReturn("POST");
        when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        when(executor.isAvailable()).thenReturn(true);
        when(exchange.getRequestURI()).thenReturn(URI.create("/async-profiler/nonexistent"));

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(404, RemoteContext.BODY_LENGTH_NONE);
    }

    @Test
    void testDeleteWithAbsentUuidSendsNotFound() throws Exception {
        when(exchange.getRequestMethod()).thenReturn("DELETE");
        when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        when(executor.isAvailable()).thenReturn(true);
        when(exchange.getRequestURI())
                .thenReturn(URI.create("/async-profiler/af9cb3e2-7152-4011-8389-093780f8d668"));

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(404, RemoteContext.BODY_LENGTH_NONE);
    }

    @Test
    void testDeleteWithoutIdSendsBadRequest() throws Exception {
        when(exchange.getRequestMethod()).thenReturn("DELETE");
        when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        when(executor.isAvailable()).thenReturn(true);
        when(exchange.getRequestURI()).thenReturn(URI.create("/async-profiler/"));

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(400, RemoteContext.BODY_LENGTH_NONE);
    }

    @Test
    void testDeleteStatusResourceSendsBadRequest() throws Exception {
        when(exchange.getRequestMethod()).thenReturn("DELETE");
        when(config.getValue(anyString(), eq(boolean.class))).thenReturn(true);
        when(executor.isAvailable()).thenReturn(true);
        when(exchange.getRequestURI()).thenReturn(URI.create("/async-profiler/status"));

        ctx.handle(exchange);

        verify(exchange).sendResponseHeaders(400, RemoteContext.BODY_LENGTH_NONE);
    }
}
