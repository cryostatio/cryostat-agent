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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import jdk.jfr.Configuration;
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
    private final AsyncProfilerExecutor asyncProfilerExecutor;

    /**
     * Tracks bookkeeping for a jfrsync-enabled recording, keyed by the agent-local recording ID.
     * async-profiler's {@code JfrSync.start()} calls {@code recording.setDestination(...)} on the
     * {@code jdk.jfr.Recording} it creates and owns; the JDK synchronously dumps a
     * destination-bound recording to disk and closes it the instant it stops, for any stop reason,
     * including its own duration timer simply elapsing. There is no observable "STOPPED but still
     * queryable via the Recording API" window -- the recording just disappears from {@code
     * FlightRecorder}'s list. This tracked state lets the list/status/download/delete handlers keep
     * treating the recording as present (now in a STOPPED state, with its content available from
     * the tracked output file on disk) even after the backing {@code Recording} object is gone.
     */
    private final Map<Long, JfrSyncRecording> jfrSyncRecordings = new ConcurrentHashMap<>();

    @Inject
    RecordingsContext(
            SmallRyeConfig config,
            ObjectMapper mapper,
            FlightRecorderHelper flightRecorder,
            AsyncProfilerExecutor asyncProfilerExecutor) {
        this.config = config;
        this.mapper = mapper;
        this.flightRecorder = flightRecorder;
        this.asyncProfilerExecutor = asyncProfilerExecutor;
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
                            .collect(Collectors.toCollection(ArrayList::new));
            Set<Long> liveIds =
                    recordings.stream()
                            .map(SerializableRecordingDescriptor::getId)
                            .collect(Collectors.toSet());
            // a jfrsync-enabled recording whose backing Recording has already stopped-and-closed
            // (see jfrSyncRecordings javadoc) is still reported here, as STOPPED, so that the
            // Cryostat server's recording-sync does not mistake it for an orphaned recording and
            // delete its ActiveRecording row before archiveOnStop can act on it.
            jfrSyncRecordings.forEach(
                    (id, info) -> {
                        if (!liveIds.contains(id) && Files.isRegularFile(info.outputFile)) {
                            recordings.add(info.toStoppedDescriptor(id));
                        }
                    });
            exchange.sendResponseHeaders(HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
            mapper.writeValue(response, recordings);
        } catch (Exception e) {
            log.error("recordings serialization failure", e);
        }
    }

    private void handleGetRecording(HttpExchange exchange, long recordingId) {
        Optional<Recording> liveRecording = flightRecorder.getRecording(recordingId);
        if (liveRecording.isPresent()) {
            streamLiveRecording(exchange, liveRecording.get());
            return;
        }
        JfrSyncRecording info = jfrSyncRecordings.get(recordingId);
        if (info != null) {
            // the backing Recording has already stopped-and-closed (see jfrSyncRecordings
            // javadoc), so the normal Recording-API-based streaming path above is unusable; serve
            // the bytes directly from the known output file on disk instead, the same strategy
            // AsyncProfilerContext already uses for its own repository files.
            streamJfrSyncOutputFile(exchange, info.outputFile);
            return;
        }
        sendHeader(exchange, HttpStatus.SC_NOT_FOUND);
    }

    private void streamLiveRecording(HttpExchange exchange, Recording r) {
        Recording copy = r.copy(true);
        try (InputStream stream = copy.getStream(null, null);
                BufferedInputStream bis = new BufferedInputStream(stream);
                OutputStream response = exchange.getResponseBody()) {
            if (stream == null) {
                exchange.sendResponseHeaders(HttpStatus.SC_NO_CONTENT, BODY_LENGTH_NONE);
            } else {
                exchange.sendResponseHeaders(HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
                bis.transferTo(response);
            }
        } catch (IOException ioe) {
            log.error("I/O error", ioe);
            try {
                exchange.sendResponseHeaders(HttpStatus.SC_INTERNAL_SERVER_ERROR, BODY_LENGTH_NONE);
            } catch (IOException ioe2) {
                log.error("Failed to write response", ioe2);
            }
        } finally {
            copy.close();
        }
    }

    private void streamJfrSyncOutputFile(HttpExchange exchange, Path outputFile) {
        if (!Files.isRegularFile(outputFile)) {
            sendHeader(exchange, HttpStatus.SC_NOT_FOUND);
            return;
        }
        try (InputStream stream = Files.newInputStream(outputFile);
                BufferedInputStream bis = new BufferedInputStream(stream);
                OutputStream response = exchange.getResponseBody()) {
            exchange.sendResponseHeaders(HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
            bis.transferTo(response);
        } catch (IOException ioe) {
            log.error("I/O error", ioe);
            try {
                exchange.sendResponseHeaders(HttpStatus.SC_INTERNAL_SERVER_ERROR, BODY_LENGTH_NONE);
            } catch (IOException ioe2) {
                log.error("Failed to write response", ioe2);
            }
        }
    }

    /**
     * Resolves a recording descriptor for status-reporting purposes, falling back to a synthesized
     * STOPPED descriptor built from tracked jfrsync bookkeeping when the backing {@code Recording}
     * is no longer registered with {@code FlightRecorder} (see {@link #jfrSyncRecordings} javadoc).
     */
    private Optional<SerializableRecordingDescriptor> findRecordingDescriptor(long recordingId) {
        Optional<Recording> live = flightRecorder.getRecording(recordingId);
        if (live.isPresent()) {
            return live.map(SerializableRecordingDescriptor::new);
        }
        JfrSyncRecording info = jfrSyncRecordings.get(recordingId);
        if (info != null && Files.isRegularFile(info.outputFile)) {
            return Optional.of(info.toStoppedDescriptor(recordingId));
        }
        return Optional.empty();
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
            if (req.requestsJfrSync() && !asyncProfilerExecutor.isAvailable()) {
                log.warn(
                        "jfrsync-enabled recording requested but async-profiler is not available"
                                + " on this target");
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
        } catch (IllegalArgumentException | IllegalStateException e) {
            // thrown by AsyncProfilerExecutor#execute (ultimately AsyncProfilerMXBean#execute)
            // for a jfrsync-enabled recording request: an unrecognized/invalid asprofEvents
            // name, or an attempt to start jfrsync while async-profiler is already running a
            // standalone profile.
            log.warn("jfrsync recording request rejected by async-profiler", e);
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
                // the backing Recording of a jfrsync-enabled recording may have already
                // stopped-and-closed on its own (see jfrSyncRecordings javadoc) by the time this
                // request arrives; report it as STOPPED rather than not found, since it is not
                // actually orphaned
                Optional<SerializableRecordingDescriptor> jfrSyncDescriptor =
                        findRecordingDescriptor(recordingId);
                if (jfrSyncDescriptor.isPresent()) {
                    exchange.sendResponseHeaders(HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
                    try (OutputStream response = exchange.getResponseBody()) {
                        mapper.writeValue(response, jfrSyncDescriptor.get());
                    }
                } else {
                    sendHeader(exchange, HttpStatus.SC_NOT_FOUND);
                }
                return;
            }
            Recording recording = recordingOpt.get();
            try {
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
                                recording.setDuration(
                                        Duration.ofMillis(field.getValue().longValue()));
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
                                recording.setMaxAge(
                                        Duration.ofMillis(field.getValue().longValue()));
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
            } finally {
                // for a jfrsync-enabled recording, refresh its bookkeeping from the live Recording
                // after every update, including partially-applied ones that end in a rejected
                // field, and not only on an explicit stop: JfrSync.start() set a destination on
                // this Recording, so it is dumped-and-closed the instant it stops for any reason,
                // including its duration timer elapsing (see jfrSyncRecordings javadoc), after
                // which the Recording API can no longer answer questions about it.
                // computeIfPresent is atomic with respect to a concurrent delete, so a removed
                // entry is never resurrected.
                jfrSyncRecordings.computeIfPresent(
                        recordingId, (id, info) -> info.capture(recording));
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

            Optional<SerializableRecordingDescriptor> descriptor =
                    findRecordingDescriptor(recordingId);
            try (OutputStream response = exchange.getResponseBody()) {
                if (response == null) {
                    sendHeader(exchange, HttpStatus.SC_NO_CONTENT);
                } else if (descriptor.isEmpty()) {
                    sendHeader(exchange, HttpStatus.SC_NOT_FOUND);
                } else {
                    exchange.sendResponseHeaders(HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
                    mapper.writeValue(response, descriptor.get());
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
                // the backing Recording of a jfrsync-enabled recording may have already
                // stopped-and-closed on its own (see jfrSyncRecordings javadoc); its tracked files
                // still need to be cleaned up even though there is no live Recording to close
                if (!jfrSyncRecordings.containsKey(recordingId)) {
                    sendHeader(exchange, HttpStatus.SC_NOT_FOUND);
                    return;
                }
            } else {
                Recording recording = opt.get();
                // for a jfrsync-enabled recording tracked in jfrSyncRecordings, call stop() first
                // to stop the async-profiler via the bound FlightRecorderListener, before cleanup
                // removes its state and files
                if (jfrSyncRecordings.containsKey(recordingId)) {
                    try {
                        recording.stop();
                    } catch (IllegalStateException e) {
                        log.warn("Recording already stopped", e);
                    }
                } else {
                    recording.close();
                }
            }
            cleanupJfrSyncRecordingFiles(recordingId);
            sendHeader(exchange, HttpStatus.SC_NO_CONTENT);
        } catch (Exception e) {
            log.error("Operation failed", e);
            sendHeader(exchange, HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private void cleanupJfrSyncRecordingFiles(long recordingId) {
        JfrSyncRecording info = jfrSyncRecordings.remove(recordingId);
        if (info == null) {
            return;
        }
        deleteQuietly(info.jfcFile);
        deleteJfrSyncOutputQuietly(info.outputFile);
    }

    private void deleteJfrSyncOutputQuietly(Path outputFile) {
        if (outputFile == null) {
            return;
        }
        deleteQuietly(outputFile);
        deleteQuietly(outputFile.getParent());
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete temporary jfrsync template file {}", path, e);
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
            throws InvalidEventTemplateException, IOException {
        if (req.requestsJfrSync()) {
            return startJfrSyncRecording(req);
        }
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

    /**
     * Starts a recording that is driven by async-profiler's {@code jfrsync} mode: {@code
     * one.profiler:type=AsyncProfiler}'s native {@code execute("start,jfr,jfrsync= ...")} call
     * creates and owns the underlying {@code jdk.jfr.Recording} itself (see async-profiler's {@code
     * JfrSync.start()}), wiring a {@code FlightRecorderListener} so that stopping the recording via
     * the normal {@code Recording.stop()} path (used by {@link #handleStopOrUpdate(HttpExchange,
     * long)}) also stops the profiler. Because of that, this method never calls the JDK's {@code
     * Recording} constructor itself - doing so would create a second, unrelated recording and leave
     * async-profiler's one dangling - it only locates the {@code Recording} that async-profiler
     * already created, by diffing the set of known recording IDs before and after issuing the
     * {@code execute(...)} call.
     */
    private SerializableRecordingDescriptor startJfrSyncRecording(StartRecordingRequest req)
            throws InvalidEventTemplateException, IOException {
        Path jfcFile = null;
        Path outputFile = null;
        try {
            String jfrSyncSetting;
            if (req.requestsCustomTemplate()) {
                jfcFile = writeJfcTempFile(req.template);
                jfrSyncSetting = jfcFile.toAbsolutePath().toString();
            } else if (predefinedJfrSyncProfileNames().contains(req.localTemplateName)) {
                jfrSyncSetting = req.localTemplateName;
            } else {
                Configuration configuration =
                        flightRecorder
                                .getTemplate(req.localTemplateName)
                                .orElseThrow(
                                        () ->
                                                new InvalidEventTemplateException(
                                                        req.localTemplateName));
                jfcFile = writeJfcTempFile(configuration.getContents());
                jfrSyncSetting = jfcFile.toAbsolutePath().toString();
            }

            // async-profiler's native argument parser requires file= to designate a destination;
            // the file it manages is distinct from the .jfc configuration file above. While the
            // recording is RUNNING, the normal Recording-API-based download path
            // (handleGetRecording) is used; once the backing Recording has stopped-and-closed
            // (see jfrSyncRecordings javadoc), this location is read directly instead. This does
            // not need to be - and must not be - the separate AsyncProfilerContext#repository
            // used by the standalone async-profiler feature. The file lives in a private
            // per-recording directory so that no other local user can pre-create or swap it.
            outputFile = Files.createTempDirectory("cryostat-jfrsync-").resolve("recording.jfr");

            String events =
                    req.asprofEvents.stream()
                            .map(e -> String.format("event=%s", e))
                            .collect(Collectors.joining(","));
            String cmd =
                    String.format(
                            "start,jfr,jfrsync=%s,%s,file=%s", jfrSyncSetting, events, outputFile);

            Set<Long> recordingIdsBeforeStart = currentRecordingIds();
            asyncProfilerExecutor.execute(cmd);
            Recording recording =
                    findNewRecording(recordingIdsBeforeStart, outputFile)
                            .orElseThrow(
                                    () -> {
                                        try {
                                            asyncProfilerExecutor.execute("stop");
                                        } catch (Exception e) {
                                            log.warn(
                                                    "Failed to stop async-profiler after failed"
                                                            + " jfrsync startup",
                                                    e);
                                        }
                                        return new IOException(
                                                "jfrsync start reported success but no new"
                                                        + " recording was found registered with"
                                                        + " the FlightRecorder");
                                    });

            recording.setName(req.name);
            applyRecordingOptions(recording, req);

            jfrSyncRecordings.put(
                    recording.getId(), new JfrSyncRecording(jfcFile, outputFile, recording));
            return new SerializableRecordingDescriptor(recording);
        } catch (InvalidEventTemplateException
                | IOException
                | IllegalArgumentException
                | IllegalStateException e) {
            deleteQuietly(jfcFile);
            deleteJfrSyncOutputQuietly(outputFile);
            throw e;
        }
    }

    private Set<String> predefinedJfrSyncProfileNames() {
        return Configuration.getConfigurations().stream()
                .map(Configuration::getName)
                .collect(Collectors.toSet());
    }

    private Path writeJfcTempFile(String contents) throws IOException {
        Path jfcFile = Files.createTempFile("cryostat-jfrsync-", ".jfc");
        Files.write(jfcFile, contents.getBytes(StandardCharsets.UTF_8));
        return jfcFile;
    }

    private Set<Long> currentRecordingIds() {
        return flightRecorder.getRecordings().stream()
                .map(Recording::getId)
                .collect(Collectors.toSet());
    }

    private Optional<Recording> findNewRecording(
            Set<Long> recordingIdsBeforeStart, Path expectedOutputFile) {
        return flightRecorder.getRecordings().stream()
                .filter(r -> !recordingIdsBeforeStart.contains(r.getId()))
                .filter(r -> expectedOutputFile.equals(r.getDestination()))
                .findFirst();
    }

    private void applyRecordingOptions(Recording recording, StartRecordingRequest req) {
        try {
            recording.setDuration(req.duration > 0 ? Duration.ofMillis(req.duration) : null);
            recording.setMaxSize(req.maxSize);
            recording.setMaxAge(Duration.ofMillis(req.maxAge));
        } catch (IllegalStateException e) {
            log.warn("Failed to apply recording options to jfrsync-driven recording", e);
        }
    }

    /**
     * Bookkeeping for a jfrsync-enabled recording. See {@link #jfrSyncRecordings} for why this is
     * needed: once the backing {@code Recording} stops-and-closes, none of this state is available
     * through the JFR {@code Recording} API any longer, so it is captured here instead while the
     * {@code Recording} is still live.
     */
    private static final class JfrSyncRecording {
        final Path jfcFile;
        final Path outputFile;
        final String name;
        final long startTime;
        final long duration;
        final boolean continuous;
        final long maxSize;
        final long maxAge;
        final boolean toDisk;

        JfrSyncRecording(Path jfcFile, Path outputFile, Recording recording) {
            this.jfcFile = jfcFile;
            this.outputFile = outputFile;
            this.name = recording.getName();
            this.startTime =
                    recording.getStartTime() == null ? 0 : recording.getStartTime().toEpochMilli();
            this.duration =
                    recording.getDuration() == null ? 0 : recording.getDuration().toMillis();
            this.continuous = recording.getDuration() == null;
            this.maxSize = recording.getMaxSize();
            this.maxAge = recording.getMaxAge() == null ? 0 : recording.getMaxAge().toMillis();
            this.toDisk = recording.isToDisk();
        }

        /** Returns an updated copy reflecting the current live state of {@code recording}. */
        JfrSyncRecording capture(Recording recording) {
            return new JfrSyncRecording(jfcFile, outputFile, recording);
        }

        SerializableRecordingDescriptor toStoppedDescriptor(long id) {
            return new SerializableRecordingDescriptor(
                    id,
                    name,
                    RecordingState.STOPPED,
                    startTime,
                    duration,
                    continuous,
                    toDisk,
                    maxSize,
                    maxAge);
        }
    }

    static class StartRecordingRequest {

        private static final Pattern ASPROF_EVENT_PATTERN = Pattern.compile("^[A-Za-z0-9_.:$/-]+$");

        public String name;
        public String localTemplateName;
        public String template;
        public long duration;
        public long maxSize;
        public long maxAge;
        public List<String> asprofEvents;

        boolean requestsCustomTemplate() {
            return !StringUtils.isBlank(template);
        }

        boolean requestsBundledTemplate() {
            return !StringUtils.isBlank(localTemplateName);
        }

        boolean requestsJfrSync() {
            return asprofEvents != null && !asprofEvents.isEmpty();
        }

        boolean hasValidAsprofEvents() {
            return !requestsJfrSync() || asprofEvents.stream().allMatch(this::isValidAsprofEvent);
        }

        /**
         * Rejects null entries and any entry containing the async-profiler argument delimiters
         * {@code ,} or {@code =}, which would allow injecting additional arguments (e.g. {@code
         * file=}) into the command, before applying the identifier allowlist.
         */
        private boolean isValidAsprofEvent(String event) {
            if (event == null || event.indexOf(',') >= 0 || event.indexOf('=') >= 0) {
                return false;
            }
            return ASPROF_EVENT_PATTERN.matcher(event).matches();
        }

        boolean requestSnapshot() {
            boolean snapshotName = name.equals("snapshot");
            boolean snapshotTemplate =
                    StringUtils.isBlank(template) && StringUtils.isBlank(localTemplateName);
            boolean snapshotFeatures = duration == 0 && maxSize == 0 && maxAge == 0;
            return snapshotName && snapshotTemplate && snapshotFeatures && !requestsJfrSync();
        }

        boolean isValid() {
            boolean requestsCustomTemplate = requestsCustomTemplate();
            boolean requestsBundledTemplate = requestsBundledTemplate();
            boolean requestsEither = requestsCustomTemplate || requestsBundledTemplate;
            boolean requestsBoth = requestsCustomTemplate && requestsBundledTemplate;
            return ((requestsEither && !requestsBoth) || requestSnapshot())
                    && hasValidAsprofEvents();
        }
    }
}
