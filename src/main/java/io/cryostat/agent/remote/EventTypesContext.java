/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
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
        String mtd = exchange.getRequestMethod();
        switch (mtd) {
            case "GET":
                try {
                    List<EventInfo> events = getEventTypes();
                    exchange.sendResponseHeaders(HttpStatus.SC_OK, 0);
                    try (OutputStream response = exchange.getResponseBody()) {
                        mapper.writeValue(response, events);
                    }
                } catch (Exception e) {
                    log.error("events serialization failure", e);
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
