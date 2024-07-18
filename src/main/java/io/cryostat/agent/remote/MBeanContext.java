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
import java.io.OutputStream;

import javax.inject.Inject;

import io.cryostat.agent.model.MBeanInfo;
import io.cryostat.libcryostat.net.MBeanMetrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class MBeanContext implements RemoteContext {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ObjectMapper mapper;

    @Inject
    MBeanContext(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String path() {
        return "/mbean-metrics/";
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String mtd = exchange.getRequestMethod();
            switch (mtd) {
                case "GET":
                    try {
                        MBeanInfo info = new MBeanInfo();
                        MBeanMetrics metrics = info.getMBeanMetrics();
                        exchange.sendResponseHeaders(HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
                        try (OutputStream response = exchange.getResponseBody()) {
                            mapper.writeValue(response, metrics);
                        }
                    } catch (Exception e) {
                        log.error("mbean serialization failure", e);
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
