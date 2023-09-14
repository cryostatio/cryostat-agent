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

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
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
import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import io.smallrye.config.SmallRyeConfig;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RecordingsContext implements RemoteContext {

    private static final String PATH = "/recordings/";
    private static final Pattern PATH_ID_PATTERN =
            Pattern.compile("^" + PATH + "(\\d+)$", Pattern.MULTILINE);

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
            long id = Long.MIN_VALUE;
            switch (mtd) {
                case "GET":
                    id = extractId(exchange);
                    if (id == Long.MIN_VALUE) {
                        handleGetList(exchange);
                    } else {
                        handleGetRecording(exchange, id);
                    }
                    break;
                case "POST":
                    handleStartRecordingOrSnapshot(exchange);
                    break;
                case "PATCH":
                    id = extractId(exchange);
                    if (id >= 0) {
                        handleStopOrUpdate(exchange, id);
                    } else {
                        exchange.sendResponseHeaders(HttpStatus.SC_BAD_REQUEST, BODY_LENGTH_NONE);
                    }
                    break;
                case "DELETE":
                    id = extractId(exchange);
                    if (id >= 0) {
                        handleDelete(exchange, id);
                    } else {
                        exchange.sendResponseHeaders(HttpStatus.SC_BAD_REQUEST, BODY_LENGTH_NONE);
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

    private static long extractId(HttpExchange exchange) throws IOException {
        Matcher m = PATH_ID_PATTERN.matcher(exchange.getRequestURI().getPath());
        if (!m.find()) {
            return Long.MIN_VALUE;
        }
        return Long.parseLong(m.group(1));
    }

    private void handleGetList(HttpExchange exchange) {
        try (OutputStream response = exchange.getResponseBody()) {
            List<SerializableRecordingDescriptor> recordings = getRecordings();
            exchange.sendResponseHeaders(HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
            mapper.writeValue(response, recordings);
        } catch (Exception e) {
            log.error("recordings serialization failure", e);
        }
    }

    private void handleGetRecording(HttpExchange exchange, long id) {
        FlightRecorder.getFlightRecorder().getRecordings().stream()
                .filter(r -> r.getId() == id)
                .findFirst()
                .ifPresentOrElse(
                        r -> {
                            Recording copy = r.copy(true);
                            try (InputStream stream = copy.getStream(null, null);
                                    BufferedInputStream bis = new BufferedInputStream(stream);
                                    OutputStream response = exchange.getResponseBody()) {
                                if (stream == null) {
                                    exchange.sendResponseHeaders(
                                            HttpStatus.SC_NO_CONTENT, BODY_LENGTH_NONE);
                                } else {
                                    exchange.sendResponseHeaders(
                                            HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
                                    bis.transferTo(response);
                                }
                            } catch (IOException ioe) {
                                log.error("I/O error", ioe);
                                try {
                                    exchange.sendResponseHeaders(
                                            HttpStatus.SC_INTERNAL_SERVER_ERROR, BODY_LENGTH_NONE);
                                } catch (IOException ioe2) {
                                    log.error("Failed to write response", ioe2);
                                }
                            } finally {
                                copy.close();
                            }
                        },
                        () -> {
                            try {
                                exchange.sendResponseHeaders(
                                        HttpStatus.SC_NOT_FOUND, BODY_LENGTH_NONE);
                            } catch (IOException e) {
                                log.error("Failed to write response", e);
                            }
                        });
    }

    private void handleStartRecordingOrSnapshot(HttpExchange exchange) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            StartRecordingRequest req = mapper.readValue(body, StartRecordingRequest.class);
            if (req.requestSnapshot()) {
                try {
                    SerializableRecordingDescriptor snapshot = startSnapshot(req, exchange);
                    if (snapshot == null) {
                        exchange.sendResponseHeaders(
                                HttpStatus.SC_SERVICE_UNAVAILABLE, BODY_LENGTH_NONE);
                        return;
                    }
                    exchange.sendResponseHeaders(HttpStatus.SC_CREATED, BODY_LENGTH_UNKNOWN);
                    try (OutputStream response = exchange.getResponseBody()) {
                        mapper.writeValue(response, snapshot);
                    }
                } catch (IOException e) {
                    log.error("Failed to start snapshot", e);
                    exchange.sendResponseHeaders(
                            HttpStatus.SC_SERVICE_UNAVAILABLE, BODY_LENGTH_NONE);
                }
                return;
            }
            if (!req.isValid()) {
                exchange.sendResponseHeaders(HttpStatus.SC_BAD_REQUEST, BODY_LENGTH_NONE);
                return;
            }
            SerializableRecordingDescriptor recording = startRecording(req);
            exchange.sendResponseHeaders(HttpStatus.SC_CREATED, BODY_LENGTH_UNKNOWN);
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
            exchange.sendResponseHeaders(HttpStatus.SC_INTERNAL_SERVER_ERROR, BODY_LENGTH_NONE);
        }
    }

    private void handleStopOrUpdate(HttpExchange exchange, long id) throws IOException {
        try {
            JFRConnection conn =
                    jfrConnectionToolkit.connect(
                            jfrConnectionToolkit.createServiceURL("localhost", 0));
            IFlightRecorderService svc = conn.getService();
            IRecordingDescriptor dsc =
                    svc.getAvailableRecordings().stream()
                            .filter(r -> r.getId() == id)
                            .findFirst()
                            .get();
            RecordingOptionsBuilder builder =
                    new RecordingOptionsBuilder(conn.getService())
                            .name(dsc.getName())
                            .duration(dsc.getDuration())
                            .maxSize(dsc.getMaxSize())
                            .maxAge(dsc.getMaxAge())
                            .toDisk(dsc.getToDisk());

            boolean shouldStop = false;
            InputStream body = exchange.getRequestBody();
            JsonNode jsonMap = mapper.readTree(body);
            Iterator<Entry<String, JsonNode>> fields = jsonMap.fields();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                log.info("Processing {0}={1}", field.getKey(), field.getValue().toPrettyString());

                switch (field.getKey()) {
                    case "state":
                        if ("STOPPED".equals(field.getValue().textValue())) {
                            log.info("shouldStop = true");
                            shouldStop = true;
                            break;
                        }
                        exchange.sendResponseHeaders(HttpStatus.SC_BAD_REQUEST, BODY_LENGTH_NONE);
                        return;
                    case "name":
                        if (!StringUtils.isBlank(field.getValue().textValue())) {
                            log.info("name = {0}", field.getValue().textValue());
                            builder = builder.name(field.getValue().textValue());
                            break;
                        }
                        exchange.sendResponseHeaders(HttpStatus.SC_BAD_REQUEST, BODY_LENGTH_NONE);
                        return;
                    case "duration":
                        if (field.getValue().canConvertToLong()) {
                            log.info("duration = {0}", field.getValue().longValue());
                            builder = builder.duration(field.getValue().longValue());
                            break;
                        }
                        exchange.sendResponseHeaders(HttpStatus.SC_BAD_REQUEST, BODY_LENGTH_NONE);
                        return;
                    case "maxSize":
                        if (field.getValue().canConvertToLong()) {
                            log.info("maxSize = {0}", field.getValue().longValue());
                            builder = builder.maxSize(field.getValue().longValue());
                            break;
                        }
                        exchange.sendResponseHeaders(HttpStatus.SC_BAD_REQUEST, BODY_LENGTH_NONE);
                        return;
                    case "maxAge":
                        if (field.getValue().canConvertToLong()) {
                            log.info("maxAge = {0}", field.getValue().longValue());
                            builder = builder.maxAge(field.getValue().longValue());
                            break;
                        }
                        exchange.sendResponseHeaders(HttpStatus.SC_BAD_REQUEST, BODY_LENGTH_NONE);
                        return;
                    case "toDisk":
                        if (field.getValue().isBoolean()) {
                            log.info("toDisk = {0}", field.getValue().booleanValue());
                            builder = builder.toDisk(field.getValue().booleanValue());
                            break;
                        }
                        exchange.sendResponseHeaders(HttpStatus.SC_BAD_REQUEST, BODY_LENGTH_NONE);
                        return;
                    default:
                        log.warn("Unknown recording option {}", field.getKey());
                        exchange.sendResponseHeaders(HttpStatus.SC_BAD_REQUEST, BODY_LENGTH_NONE);
                        return;
                }
            }
            svc.updateRecordingOptions(dsc, builder.build());
            if (shouldStop) {
                svc.stop(dsc);
            }

            try (OutputStream response = exchange.getResponseBody()) {
                if (response == null) {
                    exchange.sendResponseHeaders(HttpStatus.SC_NO_CONTENT, BODY_LENGTH_NONE);
                } else {
                    exchange.sendResponseHeaders(HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
                    mapper.writeValue(response, dsc);
                }
            }
        } catch (ServiceNotAvailableException
                | org.openjdk.jmc.rjmx.services.jfr.FlightRecorderException
                | QuantityConversionException e) {
            log.error("Failed to update recording", e);
            exchange.sendResponseHeaders(HttpStatus.SC_INTERNAL_SERVER_ERROR, BODY_LENGTH_NONE);
        } finally {
            exchange.close();
        }
    }

    private void handleDelete(HttpExchange exchange, long id) throws IOException {
        invokeOnRecording(
                exchange,
                id,
                r -> {
                    r.close();
                    sendHeader(exchange, HttpStatus.SC_NO_CONTENT);
                });
    }

    private Optional<Recording> getRecordingById(long id) {
        return FlightRecorder.getFlightRecorder().getRecordings().stream()
                .filter(r -> r.getId() == id)
                .findFirst();
    }

    private void invokeOnRecording(HttpExchange exchange, long id, Consumer<Recording> consumer) {
        Optional<Recording> opt = getRecordingById(id);
        if (!opt.isPresent()) {
            sendHeader(exchange, HttpStatus.SC_NOT_FOUND);
        }
        try {
            consumer.accept(opt.get());
        } catch (Exception e) {
            log.error("Operation failed", e);
            sendHeader(exchange, HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private void sendHeader(HttpExchange exchange, int status) {
        try {
            exchange.sendResponseHeaders(status, BODY_LENGTH_NONE);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean ensureMethodAccepted(HttpExchange exchange) throws IOException {
        Set<String> alwaysAllowed = Set.of("GET");
        String mtd = exchange.getRequestMethod();
        boolean restricted = !alwaysAllowed.contains(mtd);
        if (!restricted) {
            return true;
        }
        boolean passed = MutatingRemoteContext.apiWritesEnabled(config);
        if (!passed) {
            exchange.sendResponseHeaders(HttpStatus.SC_FORBIDDEN, BODY_LENGTH_NONE);
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
                    InvalidEventTemplateException, InvalidXmlException, IOException,
                    FlightRecorderException {
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

    private SerializableRecordingDescriptor startSnapshot(
            StartRecordingRequest req, HttpExchange exchange) throws IOException {
        Recording snapshot = FlightRecorder.getFlightRecorder().takeSnapshot();
        if (snapshot.getSize() == 0) {
            log.warn("No active recordings");
            snapshot.close();
            return null;
        }
        return new SerializableRecordingDescriptor(snapshot);
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

        boolean requestSnapshot() {
            boolean snapshotName = name.equals("snapshot");
            boolean snapshotTemplate =
                    StringUtils.isBlank(template) && StringUtils.isBlank(localTemplateName);
            boolean snapshotFeatures = duration == 0 && maxSize == 0 && maxAge == 0;
            return snapshotName && snapshotTemplate && snapshotFeatures;
        }

        boolean isValid() {
            boolean requestsCustomTemplate = requestsCustomTemplate();
            boolean requestsBundledTemplate = requestsBundledTemplate();
            boolean requestsEither = requestsCustomTemplate || requestsBundledTemplate;
            boolean requestsBoth = requestsCustomTemplate && requestsBundledTemplate;
            return (requestsEither && !requestsBoth) || requestSnapshot();
        }
    }
}
