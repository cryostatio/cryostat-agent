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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.common.unit.QuantityConversionException;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.configuration.events.EventOptionID;
import org.openjdk.jmc.flightrecorder.configuration.recording.RecordingOptionsBuilder;
import org.openjdk.jmc.rjmx.ServiceNotAvailableException;
import org.openjdk.jmc.rjmx.services.jfr.IFlightRecorderService;

import io.cryostat.agent.StringUtils;
import io.cryostat.core.FlightRecorderException;
import io.cryostat.core.net.JFRConnection;
import io.cryostat.core.net.JFRConnectionToolkit;
import io.cryostat.core.serialization.SerializableRecordingDescriptor;
import io.cryostat.core.templates.LocalStorageTemplateService;
import io.cryostat.core.templates.MutableTemplateService.InvalidEventTemplateException;
import io.cryostat.core.templates.MutableTemplateService.InvalidXmlException;
import io.cryostat.core.templates.RemoteTemplateService;
import io.cryostat.core.templates.Template;
import io.cryostat.core.templates.TemplateType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import io.smallrye.config.SmallRyeConfig;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RecordingsContext implements RemoteContext {

    private static final String PATH = "/recordings";
    private static final Pattern PATH_ID_PATTERN =
            Pattern.compile("^" + PATH + "/(\\d+)$", Pattern.MULTILINE);

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final SmallRyeConfig config;
    private final ObjectMapper mapper;
    private final JFRConnectionToolkit jfrConnectionToolkit;
    private final LocalStorageTemplateService localStorageTemplateService;

    @Inject
    RecordingsContext(
            SmallRyeConfig config,
            ObjectMapper mapper,
            JFRConnectionToolkit jfrConnectionToolkit,
            LocalStorageTemplateService localStorageTemplateService) {
        this.config = config;
        this.mapper = mapper;
        this.jfrConnectionToolkit = jfrConnectionToolkit;
        this.localStorageTemplateService = localStorageTemplateService;
    }

    @Override
    public String path() {
        return PATH;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String mtd = exchange.getRequestMethod();
            if (!ensureMethodAccepted(exchange)) {
                return;
            }
            int id = Integer.MIN_VALUE;
            switch (mtd) {
                case "GET":
                    id = extractId(exchange);
                    if (id == Integer.MIN_VALUE) {
                        handleGetList(exchange);
                    } else {
                        exchange.sendResponseHeaders(HttpStatus.SC_NOT_IMPLEMENTED, -1);
                    }
                    break;
                case "POST":
                    handleStart(exchange);
                    break;
                case "PATCH":
                    id = extractId(exchange);
                    if (id < 0) {
                        handleStop(exchange, id);
                    } else {
                        exchange.sendResponseHeaders(HttpStatus.SC_BAD_REQUEST, -1);
                    }
                case "DELETE":
                    id = extractId(exchange);
                    if (id >= 0) {
                        handleDelete(exchange, id);
                    } else {
                        exchange.sendResponseHeaders(HttpStatus.SC_BAD_REQUEST, -1);
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

    private static int extractId(HttpExchange exchange) throws IOException {
        Matcher m = PATH_ID_PATTERN.matcher(exchange.getRequestURI().getPath());
        if (!m.find()) {
            return Integer.MIN_VALUE;
        }
        return Integer.parseInt(m.group(1));
    }

    private void handleGetList(HttpExchange exchange) {
        try (OutputStream response = exchange.getResponseBody()) {
            List<SerializableRecordingDescriptor> recordings = getRecordings();
            exchange.sendResponseHeaders(HttpStatus.SC_OK, 0);
            mapper.writeValue(response, recordings);
        } catch (Exception e) {
            log.error("recordings serialization failure", e);
        }
    }

    private void handleStart(HttpExchange exchange) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            StartRecordingRequest req = mapper.readValue(body, StartRecordingRequest.class);
            if (!req.isValid()) {
                exchange.sendResponseHeaders(HttpStatus.SC_BAD_REQUEST, -1);
                return;
            }
            SerializableRecordingDescriptor recording = startRecording(req);
            exchange.sendResponseHeaders(HttpStatus.SC_CREATED, 0);
            try (OutputStream response = exchange.getResponseBody()) {
                mapper.writeValue(response, recording);
            }
        } catch (QuantityConversionException
                | ServiceNotAvailableException
                | FlightRecorderException
                | org.openjdk.jmc.rjmx.services.jfr.FlightRecorderException
                | InvalidEventTemplateException
                | InvalidXmlException
                | IOException e) {
            log.error("Failed to start recording", e);
            exchange.sendResponseHeaders(HttpStatus.SC_INTERNAL_SERVER_ERROR, -1);
        }
    }

    private void handleStop(HttpExchange exchange, int id) throws IOException {
        invokeOnRecording(
                exchange,
                id,
                r -> {
                    try {
                        boolean stopped = r.stop();
                        if (!stopped) {
                            sendHeader(exchange, HttpStatus.SC_BAD_REQUEST);
                        } else {
                            sendHeader(exchange, HttpStatus.SC_NO_CONTENT);
                        }
                    } catch (IllegalStateException e) {
                        sendHeader(exchange, HttpStatus.SC_CONFLICT);
                    }
                });
    }

    private void handleDelete(HttpExchange exchange, int id) throws IOException {
        invokeOnRecording(
                exchange,
                id,
                r -> {
                    r.close();
                    sendHeader(exchange, HttpStatus.SC_NO_CONTENT);
                });
    }

    private void invokeOnRecording(HttpExchange exchange, long id, Consumer<Recording> consumer) {
        FlightRecorder.getFlightRecorder().getRecordings().stream()
                .filter(r -> r.getId() == id)
                .findFirst()
                .ifPresentOrElse(
                        consumer::accept, () -> sendHeader(exchange, HttpStatus.SC_NOT_FOUND));
    }

    private void sendHeader(HttpExchange exchange, int status) {
        try {
            exchange.sendResponseHeaders(status, -1);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean ensureMethodAccepted(HttpExchange exchange) throws IOException {
        Set<String> blocked = Set.of("POST");
        String mtd = exchange.getRequestMethod();
        boolean restricted = blocked.contains(mtd);
        if (!restricted) {
            return true;
        }
        boolean passed = restricted && MutatingRemoteContext.apiWritesEnabled(config);
        if (!passed) {
            exchange.sendResponseHeaders(HttpStatus.SC_FORBIDDEN, -1);
        }
        return passed;
    }

    private List<SerializableRecordingDescriptor> getRecordings() {
        return FlightRecorder.getFlightRecorder().getRecordings().stream()
                .map(SerializableRecordingDescriptor::new)
                .collect(Collectors.toList());
    }

    private SerializableRecordingDescriptor startRecording(StartRecordingRequest req)
            throws QuantityConversionException, ServiceNotAvailableException,
                    FlightRecorderException,
                    org.openjdk.jmc.rjmx.services.jfr.FlightRecorderException,
                    InvalidEventTemplateException, InvalidXmlException, IOException {
        Runnable cleanup = () -> {};
        try {
            JFRConnection conn =
                    jfrConnectionToolkit.connect(
                            jfrConnectionToolkit.createServiceURL("localhost", 0));
            IConstrainedMap<EventOptionID> events;
            if (req.requestsCustomTemplate()) {
                Template template =
                        localStorageTemplateService.addTemplate(
                                new ByteArrayInputStream(
                                        req.template.getBytes(StandardCharsets.UTF_8)));
                events = localStorageTemplateService.getEvents(template).orElseThrow();
                cleanup =
                        () -> {
                            try {
                                localStorageTemplateService.deleteTemplate(template);
                            } catch (InvalidEventTemplateException | IOException e) {
                                log.error("Failed to clean up template " + template.getName(), e);
                            }
                        };
            } else {
                events =
                        new RemoteTemplateService(conn)
                                .getEvents(req.localTemplateName, TemplateType.TARGET).stream()
                                        .findFirst()
                                        .orElseThrow();
            }
            IFlightRecorderService svc = conn.getService();
            return new SerializableRecordingDescriptor(
                    svc.start(
                            new RecordingOptionsBuilder(conn.getService())
                                    .name(req.name)
                                    .duration(UnitLookup.MILLISECOND.quantity(req.duration))
                                    .maxSize(UnitLookup.BYTE.quantity(req.maxSize))
                                    .maxAge(UnitLookup.MILLISECOND.quantity(req.maxAge))
                                    .toDisk(true)
                                    .build(),
                            events));
        } finally {
            cleanup.run();
        }
    }

    static class StartRecordingRequest {

        public String name;
        public String localTemplateName;
        public String template;
        public long duration;
        public long maxSize;
        public long maxAge;

        boolean requestsCustomTemplate() {
            return !StringUtils.isBlank(template);
        }

        boolean requestsBundledTemplate() {
            return !StringUtils.isBlank(localTemplateName);
        }

        boolean isValid() {
            boolean requestsCustomTemplate = requestsCustomTemplate();
            boolean requestsBundledTemplate = requestsBundledTemplate();
            boolean requestsEither = requestsCustomTemplate || requestsBundledTemplate;
            boolean requestsBoth = requestsCustomTemplate && requestsBundledTemplate;
            return requestsEither && !requestsBoth;
        }
    }
}
