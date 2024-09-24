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
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

import javax.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GCContext implements RemoteContext {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ObjectMapper objectMapper;

    @Inject
    GCContext(ObjectMapper mapper) {
        objectMapper = mapper;
    }

    @Override
    public String path() {
        return "/gc/";
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String mtd = exchange.getRequestMethod();
            switch (mtd) {
                case "GET":
                    try {
                        MemoryMXBean memorybean = ManagementFactory.getMemoryMXBean();
                        memorybean.gc();
                        exchange.sendResponseHeaders(HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
                    } catch (Exception e) {
                        log.error("failed to fetch memoryMXBean", e);
                        exchange.sendResponseHeaders(
                                HttpStatus.SC_INTERNAL_SERVER_ERROR, BODY_LENGTH_NONE);
                    }
                    break;
                default:
                    log.warn("Unknown request method {}", mtd);
                    exchange.sendResponseHeaders(
                            HttpStatus.SC_METHOD_NOT_ALLOWED, BODY_LENGTH_NONE);
                    break;
            }
        } finally {
            exchange.close();
        }
    }
}
