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
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RecordingsContext implements RemoteContext {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ObjectMapper mapper;

    @Inject
    RecordingsContext(ObjectMapper mapper) {
        this.mapper = mapper;
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
                    List<RecordingInfo> recordings = getRecordings();
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

    private List<RecordingInfo> getRecordings() {
        return FlightRecorder.getFlightRecorder().getRecordings().stream()
                .map(RecordingInfo::new)
                .collect(Collectors.toList());
    }

    @SuppressFBWarnings(value = "URF_UNREAD_FIELD")
    private static class RecordingInfo {

        public final long id;
        public final String name;
        public final String state;
        public final Map<String, String> options;
        public final long startTime;
        public final long duration;
        public final boolean isContinuous;
        public final boolean toDisk;
        public final long maxSize;
        public final long maxAge;

        RecordingInfo(Recording rec) {
            this.id = rec.getId();
            this.name = rec.getName();
            this.state = rec.getState().name();
            this.options = rec.getSettings();
            if (rec.getStartTime() != null) {
                this.startTime = rec.getStartTime().toEpochMilli();
            } else {
                this.startTime = 0;
            }
            this.isContinuous = rec.getDuration() == null;
            this.duration = this.isContinuous ? 0 : rec.getDuration().toMillis();
            this.toDisk = rec.isToDisk();
            this.maxSize = rec.getMaxSize();
            if (rec.getMaxAge() != null) {
                this.maxAge = rec.getMaxAge().toMillis();
            } else {
                this.maxAge = 0;
            }
        }
    }
}
