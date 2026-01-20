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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.ParseException;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.inject.Inject;

import io.cryostat.agent.FlightRecorderHelper;
import io.cryostat.libcryostat.serialization.SerializableRecordingDescriptor;
import io.cryostat.libcryostat.templates.InvalidEventTemplateException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import io.smallrye.config.SmallRyeConfig;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RecordingsContext implements RemoteContext {

    private static final String PATH = "/recordings/";
    private static final Pattern PATH_ID_PATTERN =
            Pattern.compile("^" + PATH + "(\\d+)$", Pattern.MULTILINE);

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final SmallRyeConfig config;
    private final ObjectMapper mapper;
    private final FlightRecorderHelper flightRecorder;

    @Inject
    RecordingsContext(
            SmallRyeConfig config, ObjectMapper mapper, FlightRecorderHelper flightRecorder) {
        this.config = config;
        this.mapper = mapper;
        this.flightRecorder = flightRecorder;
    }

    @Override
    public String path() {
        return PATH;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!ensureMethodAccepted(exchange)) {
                return;
            }
            String mtd = exchange.getRequestMethod();
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
            List<SerializableRecordingDescriptor> recordings =
                    flightRecorder.getRecordings().stream()
                            .map(SerializableRecordingDescriptor::new)
                            .collect(Collectors.toList());
            exchange.sendResponseHeaders(HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
            mapper.writeValue(response, recordings);
        } catch (Exception e) {
            log.error("recordings serialization failure", e);
        }
    }

    private void handleGetRecording(HttpExchange exchange, long recordingId) {
        flightRecorder
                .getRecording(recordingId)
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
                    SerializableRecordingDescriptor snapshot =
                            flightRecorder
                                    .createSnapshot()
                                    .map(SerializableRecordingDescriptor::new)
                                    .orElse(null);
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
                log.warn("Invalid recording start request: {}", req);
                exchange.sendResponseHeaders(HttpStatus.SC_BAD_REQUEST, BODY_LENGTH_NONE);
                return;
            }
            SerializableRecordingDescriptor recording = startRecording(req);
            exchange.sendResponseHeaders(HttpStatus.SC_CREATED, BODY_LENGTH_UNKNOWN);
            try (OutputStream response = exchange.getResponseBody()) {
                mapper.writeValue(response, recording);
            }
        } catch (IOException e) {
            log.error("Failed to start recording", e);
            exchange.sendResponseHeaders(HttpStatus.SC_INTERNAL_SERVER_ERROR, BODY_LENGTH_NONE);
        } catch (InvalidEventTemplateException e) {
            log.warn("Invalid custom event template", e);
            exchange.sendResponseHeaders(HttpStatus.SC_BAD_REQUEST, BODY_LENGTH_NONE);
        }
    }

    private void handleStopOrUpdate(HttpExchange exchange, long recordingId) throws IOException {
        try {
            boolean shouldStop = false;
            InputStream body = exchange.getRequestBody();
            JsonNode jsonMap = mapper.readTree(body);
            Iterator<Entry<String, JsonNode>> fields = jsonMap.fields();

            Optional<Recording> recordingOpt = flightRecorder.getRecording(recordingId);
            if (recordingOpt.isEmpty()) {
                sendHeader(exchange, HttpStatus.SC_NOT_FOUND);
                return;
            }
            Recording recording = recordingOpt.get();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                switch (field.getKey()) {
                    case "state":
                        if ("stopped".equalsIgnoreCase(field.getValue().textValue())) {
                            shouldStop = true;
                            break;
                        }
                        sendHeader(exchange, HttpStatus.SC_BAD_REQUEST);
                        return;
                    case "name":
                        if (!StringUtils.isBlank(field.getValue().textValue())) {
                            recording.setName(field.getValue().textValue());
                            break;
                        }
                        sendHeader(exchange, HttpStatus.SC_BAD_REQUEST);
                        return;
                    case "duration":
                        if (field.getValue().canConvertToLong()) {
                            recording.setDuration(Duration.ofMillis(field.getValue().longValue()));
                            break;
                        }
                        sendHeader(exchange, HttpStatus.SC_BAD_REQUEST);
                        return;
                    case "maxSize":
                        if (field.getValue().canConvertToLong()) {
                            recording.setMaxSize(field.getValue().longValue());
                            break;
                        }
                        sendHeader(exchange, HttpStatus.SC_BAD_REQUEST);
                        return;
                    case "maxAge":
                        if (field.getValue().canConvertToLong()) {
                            recording.setMaxAge(Duration.ofMillis(field.getValue().longValue()));
                            break;
                        }
                        sendHeader(exchange, HttpStatus.SC_BAD_REQUEST);
                        return;
                    case "toDisk":
                        if (field.getValue().isBoolean()) {
                            recording.setToDisk(field.getValue().booleanValue());
                            break;
                        }
                        sendHeader(exchange, HttpStatus.SC_BAD_REQUEST);
                        return;
                    default:
                        log.warn("Unknown recording option {}", field.getKey());
                        sendHeader(exchange, HttpStatus.SC_BAD_REQUEST);
                        return;
                }
            }
            if (shouldStop) {
                try {
                    if (!recording.stop()) {
                        sendHeader(exchange, HttpStatus.SC_INTERNAL_SERVER_ERROR);
                    }
                } catch (IllegalStateException e) {
                    sendHeader(
                            exchange,
                            recording.getState().equals(RecordingState.STOPPED)
                                    ? HttpStatus.SC_NO_CONTENT
                                    : HttpStatus.SC_INTERNAL_SERVER_ERROR);
                }
            }

            try (OutputStream response = exchange.getResponseBody()) {
                if (response == null) {
                    sendHeader(exchange, HttpStatus.SC_NO_CONTENT);
                } else {
                    exchange.sendResponseHeaders(HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
                    mapper.writeValue(
                            response,
                            flightRecorder
                                    .getRecording(recordingId)
                                    .map(SerializableRecordingDescriptor::new)
                                    .get());
                }
            }
        } catch (Exception e) {
            log.error("Failed to update recording", e);
            sendHeader(exchange, HttpStatus.SC_INTERNAL_SERVER_ERROR);
        } finally {
            exchange.close();
        }
    }

    private void handleDelete(HttpExchange exchange, long recordingId) throws IOException {
        try {
            Optional<Recording> opt = flightRecorder.getRecording(recordingId);
            if (opt.isEmpty()) {
                sendHeader(exchange, HttpStatus.SC_NOT_FOUND);
                return;
            }
            opt.get().close();
            sendHeader(exchange, HttpStatus.SC_NO_CONTENT);
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

    private SerializableRecordingDescriptor startRecording(StartRecordingRequest req)
            throws InvalidEventTemplateException {
        Recording recording;
        if (req.requestsCustomTemplate()) {
            try {
                recording = flightRecorder.createRecordingWithCustomTemplate(req.template);
            } catch (IOException | ParseException e) {
                throw new InvalidEventTemplateException("Invalid event template contents", e);
            }
        } else {
            recording =
                    flightRecorder
                            .createRecordingWithPredefinedTemplate(req.localTemplateName)
                            .orElseThrow(
                                    () -> new InvalidEventTemplateException(req.localTemplateName))
                            .getRecording();
        }
        recording.setName(req.name);
        recording.setToDisk(true);
        recording.setDuration(req.duration > 0 ? Duration.ofMillis(req.duration) : null);
        recording.setMaxSize(req.maxSize);
        recording.setMaxAge(Duration.ofMillis(req.maxAge));
        recording.start();
        return new SerializableRecordingDescriptor(recording);
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
