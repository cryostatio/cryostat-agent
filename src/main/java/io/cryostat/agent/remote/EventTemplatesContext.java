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
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import jdk.management.jfr.ConfigurationInfo;
import jdk.management.jfr.FlightRecorderMXBean;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class EventTemplatesContext implements RemoteContext {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ObjectMapper mapper;

    @Inject
    EventTemplatesContext(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String path() {
        return "/event-templates";
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String mtd = exchange.getRequestMethod();
            switch (mtd) {
                case "GET":
                    try {
                        exchange.sendResponseHeaders(HttpStatus.SC_OK, 0);
                        try (OutputStream response = exchange.getResponseBody()) {
                            FlightRecorderMXBean bean =
                                    ManagementFactory.getPlatformMXBean(FlightRecorderMXBean.class);
                            List<String> xmlTexts =
                                    bean.getConfigurations().stream()
                                            .map(ConfigurationInfo::getContents)
                                            .collect(Collectors.toList());
                            mapper.writeValue(response, xmlTexts);
                        }
                    } catch (Exception e) {
                        log.error("events serialization failure", e);
                    }
                    break;
                default:
                    log.warn("Unknown request method {}", mtd);
                    exchange.sendResponseHeaders(HttpStatus.SC_METHOD_NOT_ALLOWED, -1);
                    break;
            }
        } finally {
            exchange.close();
        }
    }
}
