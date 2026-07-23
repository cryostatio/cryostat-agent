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
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;

import io.cryostat.agent.ConfigModule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import io.smallrye.config.SmallRyeConfig;
import org.apache.hc.core5.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class GcLogContext implements RemoteContext {

    static final String PATH = "/gc-log/";
    static final String STATUS_PATH = "/gc-log/status";

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ObjectMapper mapper;
    private final SmallRyeConfig config;
    private final GcLogging gcLogging;

    @Inject
    GcLogContext(ObjectMapper mapper, SmallRyeConfig config, GcLogging gcLogging) {
        this.mapper = mapper;
        this.config = config;
        this.gcLogging = gcLogging;
    }

    @Override
    public String path() {
        return PATH;
    }

    @Override
    public boolean available() {
        return config.getValue(ConfigModule.CRYOSTAT_AGENT_GC_LOG_ENABLED, boolean.class);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String mtd = exchange.getRequestMethod();
            if (!"GET".equals(mtd)) {
                log.warn("Unknown request method {}", mtd);
                exchange.sendResponseHeaders(HttpStatus.SC_METHOD_NOT_ALLOWED, BODY_LENGTH_NONE);
                return;
            }
            String requestPath = exchange.getRequestURI().getPath();
            if (STATUS_PATH.equals(requestPath)) {
                handleStatus(exchange);
            } else {
                handleGet(exchange);
            }
        } finally {
            exchange.close();
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", gcLogging.loggingEnabled);
        status.put("what", gcLogging.what);
        status.put("decorators", gcLogging.decorators);
        status.put(
                "logFilePath", gcLogging.gcLogPath != null ? gcLogging.gcLogPath.toString() : null);
        status.put(
                "hasLog",
                gcLogging.gcLogPath != null
                        && !gcLogging.isStreamOutput()
                        && Files.exists(gcLogging.gcLogPath));
        exchange.sendResponseHeaders(HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
        try (OutputStream response = exchange.getResponseBody()) {
            mapper.writeValue(response, status);
        }
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        if (!gcLogging.loggingEnabled || gcLogging.gcLogPath == null) {
            exchange.sendResponseHeaders(HttpStatus.SC_CONFLICT, BODY_LENGTH_NONE);
            return;
        }
        if (gcLogging.isStreamOutput()) {
            exchange.sendResponseHeaders(HttpStatus.SC_NO_CONTENT, BODY_LENGTH_NONE);
            return;
        }
        if (!Files.exists(gcLogging.gcLogPath) || Files.size(gcLogging.gcLogPath) == 0L) {
            exchange.sendResponseHeaders(HttpStatus.SC_NO_CONTENT, BODY_LENGTH_NONE);
            return;
        }
        InputStream stream;
        try {
            stream = gcLogging.collectAndRedirect();
        } catch (Exception e) {
            log.error("Failed to collect GC log", e);
            exchange.sendResponseHeaders(HttpStatus.SC_INTERNAL_SERVER_ERROR, BODY_LENGTH_NONE);
            return;
        }
        exchange.sendResponseHeaders(HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
        try (stream;
                OutputStream out = exchange.getResponseBody()) {
            stream.transferTo(out);
        }
    }
}
