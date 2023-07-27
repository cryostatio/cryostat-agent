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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.SettingDescriptor;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class EventTypesContext implements RemoteContext {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ObjectMapper mapper;

    @Inject
    EventTypesContext(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String path() {
        return "/event-types";
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String mtd = exchange.getRequestMethod();
            switch (mtd) {
                case "GET":
                    List<EventInfo> events = new ArrayList<>();
                    try {
                        events.addAll(getEventTypes());
                    } catch (Exception e) {
                        log.error("events serialization failure", e);
                        exchange.sendResponseHeaders(HttpStatus.SC_INTERNAL_SERVER_ERROR, 0);
                        break;
                    }
                    exchange.sendResponseHeaders(HttpStatus.SC_OK, 0);
                    try (OutputStream response = exchange.getResponseBody()) {
                        mapper.writeValue(response, events);
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

    private List<EventInfo> getEventTypes() {
        return FlightRecorder.getFlightRecorder().getEventTypes().stream()
                .map(
                        evt -> {
                            EventInfo evtInfo = new EventInfo(evt);
                            evtInfo.settings.addAll(
                                    evt.getSettingDescriptors().stream()
                                            .map(SettingInfo::new)
                                            .collect(Collectors.toList()));
                            return evtInfo;
                        })
                .collect(Collectors.toList());
    }

    @SuppressFBWarnings(value = "URF_UNREAD_FIELD")
    private static class EventInfo {

        public final String name;
        public final String label;
        public final String description;
        public final List<String> categories;
        public final List<SettingInfo> settings = new ArrayList<>();

        EventInfo(EventType evt) {
            this.name = evt.getName();
            this.label = evt.getLabel();
            this.description = evt.getDescription();
            this.categories = evt.getCategoryNames();
        }
    }

    @SuppressFBWarnings(value = "URF_UNREAD_FIELD")
    private static class SettingInfo {

        public final String name;
        public final String defaultValue;

        SettingInfo(SettingDescriptor desc) {
            this.name = desc.getName();
            this.defaultValue = desc.getDefaultValue();
        }
    }
}
