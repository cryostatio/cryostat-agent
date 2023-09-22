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
import java.util.List;

import javax.inject.Inject;

import io.cryostat.agent.FlightRecorderModule;
import io.cryostat.agent.FlightRecorderModule.RecordingInfo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RecordingsContext implements RemoteContext {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ObjectMapper mapper;
    private FlightRecorderModule flightRecorderModule;

    @Inject
    RecordingsContext(ObjectMapper mapper) {
        this.mapper = mapper;
        flightRecorderModule = new FlightRecorderModule();
    }

    @Override
    public String path() {
        return "/recordings";
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String mtd = exchange.getRequestMethod();
        switch (mtd) {
            case "GET":
                try {
                    List<RecordingInfo> recordings = flightRecorderModule.getRecordings();
                    exchange.sendResponseHeaders(HttpStatus.SC_OK, 0);
                    try (OutputStream response = exchange.getResponseBody()) {
                        mapper.writeValue(response, recordings);
                    }
                } catch (Exception e) {
                    log.error("recordings serialization failure", e);
                } finally {
                    exchange.close();
                }
                break;
            default:
                exchange.sendResponseHeaders(HttpStatus.SC_NOT_FOUND, -1);
                exchange.close();
                break;
        }
    }
}
