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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.inject.Inject;

import io.cryostat.agent.ConfigModule;
import io.cryostat.agent.FlightRecorderHelper;
import io.cryostat.libcryostat.serialization.SerializableRecordingDescriptor;
import io.cryostat.libcryostat.templates.InvalidEventTemplateException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import io.smallrye.config.SmallRyeConfig;
import jdk.jfr.Configuration;
import jdk.jfr.FlightRecorder;
import jdk.jfr.FlightRecorderListener;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RecordingsContext implements RemoteContext {

    private static final String PATH = "/recordings/";
    private static final Duration CHECK_GRACE = Duration.ofSeconds(1);
    private static final Pattern PATH_ID_PATTERN =
            Pattern.compile("^" + PATH + "(\\d+)$", Pattern.MULTILINE);

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final SmallRyeConfig config;
    private final ObjectMapper mapper;
    private final FlightRecorderHelper flightRecorder;
    private final AsyncProfilerExecutor asyncProfilerExecutor;
    private final ScheduledExecutorService scheduler;

    /**
     * Tracks every recording visible to {@code FlightRecorder}, keyed by agent-local recording ID,
     * whether started through this context or found already present (for example one started with
     * {@code -XX:StartFlightRecording}). Newer JDKs close a recording bound to a destination file
     * as soon as it stops, including when its duration elapses, after which it is no longer visible
     * to {@code FlightRecorder} or JMX; older JDKs leave it visible in the STOPPED state. Neither
     * behaviour is assumed. When a tracked recording is found to have disappeared and had an
     * on-disk destination, it is frozen into an immutable "ghost": a read-only STOPPED recording
     * backed by that file, so that newer JDKs behave like older ones. The only mutation a ghost
     * permits is deletion. A recording that disappeared without a destination, or whose destination
     * file is gone, is forgotten as if it had never existed. The state is reconciled with the JDK
     * once a recording's expected duration has elapsed, after every update request, and whenever a
     * request arrives.
     */
    private final Map<Long, TrackedRecording> trackedRecordings = new ConcurrentHashMap<>();

    @Inject
    RecordingsContext(
            SmallRyeConfig config,
            ObjectMapper mapper,
            FlightRecorderHelper flightRecorder,
            AsyncProfilerExecutor asyncProfilerExecutor,
            ScheduledExecutorService scheduler) {
        this.config = config;
        this.mapper = mapper;
        this.flightRecorder = flightRecorder;
        this.asyncProfilerExecutor = asyncProfilerExecutor;
        this.scheduler = scheduler;
    }

    @Override
    public String path() {
        return PATH;
    }

    /**
     * Begins tracking recordings when the agent's web server starts, rather than when the first
     * request arrives, and independently of whether the agent has registered with Cryostat. A
     * listener is registered at once so that recordings changing state from then on are tracked.
     * Recordings that already exist are swept after the delay configured by {@link
     * ConfigModule#CRYOSTAT_AGENT_RECORDINGS_INITIAL_SCAN_DELAY_MS}, because a recording started by
     * {@code -XX:StartFlightRecording} may not yet be registered when the agent starts, depending
     * on the machine, its load, and the JVM version. On newer JDKs a recording bound to a
     * destination is closed as soon as it stops, so it must be known beforehand for its dump file
     * to be found afterwards. A delay of zero or less sweeps synchronously.
     */
    @Override
    public void start() {
        if (!FlightRecorder.isAvailable()) {
            return;
        }
        try {
            FlightRecorder.addListener(
                    new FlightRecorderListener() {
                        @Override
                        public void recordingStateChanged(Recording recording) {
                            onRecordingStateChanged(recording);
                        }
                    });
        } catch (Exception e) {
            log.warn("Failed to register recording listener", e);
        }
        long delayMillis =
                config.getValue(
                        ConfigModule.CRYOSTAT_AGENT_RECORDINGS_INITIAL_SCAN_DELAY_MS, long.class);
        if (delayMillis <= 0) {
            reconcileTrackedRecordings();
        } else {
            scheduler.schedule(
                    this::reconcileTrackedRecordings, delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void onRecordingStateChanged(Recording recording) {
        try {
            long id = recording.getId();
            if (!trackedRecordings.containsKey(id)) {
                track(recording, null, null);
            }
            reconcileTrackedRecording(id, false);
        } catch (Exception e) {
            log.warn("Failed to process recording state change", e);
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!ensureMethodAccepted(exchange)) {
                return;
            }
            reconcileTrackedRecordings();
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
            trackedRecordings.forEach(
                    (id, info) -> {
                        SerializableRecordingDescriptor ghost = info.ghost();
                        if (ghost != null) {
                            recordings.add(ghost);
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
        TrackedRecording info = trackedRecordings.get(recordingId);
        if (info != null && info.ghost() != null) {
            streamDumpFile(exchange, info.ghostFile());
            return;
        }
        sendHeader(exchange, HttpStatus.SC_NOT_FOUND);
    }

    private void streamDumpFile(HttpExchange exchange, Path outputFile) {
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
                handleUpdateOfGhost(exchange, recordingId, jsonMap);
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
                                    .orElseGet(() -> describeAsStopped(recordingId, recording)));
                }
            }
        } catch (Exception e) {
            log.error("Failed to update recording", e);
            sendHeader(exchange, HttpStatus.SC_INTERNAL_SERVER_ERROR);
        } finally {
            reconcileTrackedRecording(recordingId, true);
            exchange.close();
        }
    }

    /**
     * A ghost recording is read-only: reporting it as STOPPED again is accepted, since a client
     * acting on stale state may legitimately repeat that request, but any other update conflicts
     * with the ghost's immutability.
     */
    private void handleUpdateOfGhost(HttpExchange exchange, long recordingId, JsonNode jsonMap)
            throws IOException {
        TrackedRecording info = trackedRecordings.get(recordingId);
        SerializableRecordingDescriptor ghost = info == null ? null : info.ghost();
        if (ghost == null) {
            sendHeader(exchange, HttpStatus.SC_NOT_FOUND);
            return;
        }
        Iterator<Entry<String, JsonNode>> fields = jsonMap.fields();
        while (fields.hasNext()) {
            Entry<String, JsonNode> field = fields.next();
            if (!"state".equals(field.getKey())
                    || !"stopped".equalsIgnoreCase(field.getValue().textValue())) {
                sendHeader(exchange, HttpStatus.SC_CONFLICT);
                return;
            }
        }
        exchange.sendResponseHeaders(HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
        try (OutputStream response = exchange.getResponseBody()) {
            mapper.writeValue(response, ghost);
        }
    }

    /**
     * Describes a recording as STOPPED. The getters of a {@code Recording} closed by the JDK remain
     * usable, but its state would read as CLOSED.
     */
    private static SerializableRecordingDescriptor describeAsStopped(
            long recordingId, Recording recording) {
        return new SerializableRecordingDescriptor(
                recordingId,
                recording.getName(),
                RecordingState.STOPPED,
                recording.getStartTime() == null ? 0 : recording.getStartTime().toEpochMilli(),
                recording.getDuration() == null ? 0 : recording.getDuration().toMillis(),
                recording.getDuration() == null,
                recording.isToDisk(),
                recording.getMaxSize(),
                recording.getMaxAge() == null ? 0 : recording.getMaxAge().toMillis());
    }

    private void handleDelete(HttpExchange exchange, long recordingId) throws IOException {
        // untrack first so that a concurrent reconcile does not turn the recording being deleted
        // into a ghost
        TrackedRecording info = trackedRecordings.remove(recordingId);
        try {
            Optional<Recording> opt = flightRecorder.getRecording(recordingId);
            if (opt.isEmpty()) {
                if (info == null || info.ghost() == null) {
                    sendHeader(exchange, HttpStatus.SC_NOT_FOUND);
                    return;
                }
            } else {
                Recording recording = opt.get();
                // for a jfrsync-enabled recording, call stop() first to stop the async-profiler
                // via the bound FlightRecorderListener, before its files are removed
                if (info != null && info.isJfrSync()) {
                    try {
                        recording.stop();
                    } catch (IllegalStateException e) {
                        log.warn("Recording already stopped", e);
                    }
                } else {
                    recording.close();
                }
            }
            sendHeader(exchange, HttpStatus.SC_NO_CONTENT);
        } catch (Exception e) {
            log.error("Operation failed", e);
            sendHeader(exchange, HttpStatus.SC_INTERNAL_SERVER_ERROR);
        } finally {
            if (info != null) {
                release(info);
            }
        }
    }

    private void reconcileTrackedRecordings() {
        try {
            for (Recording recording : flightRecorder.getRecordings()) {
                if (!trackedRecordings.containsKey(recording.getId())) {
                    track(recording, null, null);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to discover recordings", e);
        }
        for (Long id : new ArrayList<>(trackedRecordings.keySet())) {
            reconcileTrackedRecording(id, false);
        }
    }

    private void track(Recording recording, Path jfcFile, Path ownedOutputFile) {
        TrackedRecording previous =
                trackedRecordings.put(
                        recording.getId(),
                        new TrackedRecording(jfcFile, ownedOutputFile, recording));
        if (previous != null) {
            previous.cancelCheck();
        }
        scheduleCheck(recording.getId(), recording);
    }

    /**
     * Re-reads the state of a tracked recording from the JDK. If it is still visible and running, a
     * check is scheduled for when its (possibly just updated) duration elapses. If it has
     * disappeared, it is frozen into a read-only ghost backed by its on-disk destination, or, if it
     * had none, forgotten as if it had never existed. A ghost whose file has gone away is forgotten
     * likewise. Does nothing for an untracked recording.
     *
     * @param reschedule replace a still pending check, because the duration may have changed
     */
    private void reconcileTrackedRecording(long recordingId, boolean reschedule) {
        try {
            TrackedRecording info = trackedRecordings.get(recordingId);
            if (info == null) {
                return;
            }
            if (!info.isFrozen()) {
                Optional<Recording> recording = flightRecorder.getRecording(recordingId);
                if (recording.isPresent()) {
                    if (reschedule || !info.hasPendingCheck()) {
                        scheduleCheck(recordingId, recording.get());
                    }
                    return;
                }
                info.freeze(recordingId, flightRecorder::findStartupDumpFile);
            }
            if (info.ghost() == null || !Files.isRegularFile(info.ghostFile())) {
                forget(recordingId);
            }
        } catch (Exception e) {
            log.warn("Failed to reconcile recording {}", recordingId, e);
        }
    }

    private void scheduleCheck(long recordingId, Recording recording) {
        TrackedRecording info = trackedRecordings.get(recordingId);
        if (info == null) {
            return;
        }
        info.cancelCheck();
        Duration duration = recording.getDuration();
        Instant start = recording.getStartTime();
        if (duration == null || start == null || recording.getState() != RecordingState.RUNNING) {
            return;
        }
        long delayMillis =
                Math.max(0, Duration.between(Instant.now(), start.plus(duration)).toMillis())
                        + CHECK_GRACE.toMillis();
        info.setCheck(
                scheduler.schedule(
                        () -> reconcileTrackedRecording(recordingId, false),
                        delayMillis,
                        TimeUnit.MILLISECONDS));
    }

    private void forget(long recordingId) {
        TrackedRecording info = trackedRecordings.remove(recordingId);
        if (info != null) {
            release(info);
        }
    }

    /**
     * Cancels the pending check and deletes only the files this context created. The destination
     * file of a recording that was not started here belongs to whoever configured it, and is never
     * deleted.
     */
    private void release(TrackedRecording info) {
        info.cancelCheck();
        deleteQuietly(info.jfcFile);
        deleteJfrSyncOutputQuietly(info.ownedOutputFile);
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
            log.warn("Failed to delete temporary file {}", path, e);
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
        track(recording, null, null);
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
            // (see TrackedRecording), this location is read directly instead. This does
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

            track(recording, jfcFile, outputFile);
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
     * Bookkeeping for a recording: the temporary files this context created for it (if any), the
     * pending check that reconciles it with the JDK once its duration has elapsed, and the {@code
     * Recording} itself until it disappears from {@code FlightRecorder}. At that point it is frozen
     * into an immutable ghost descriptor, if it had an on-disk destination, and the {@code
     * Recording} reference is released. Reading the closed {@code Recording} is safe, and picks up
     * any update applied before it closed.
     */
    private static final class TrackedRecording {
        final Path jfcFile;
        final Path ownedOutputFile;
        private Recording recording;
        private SerializableRecordingDescriptor ghost;
        private Path ghostFile;
        private ScheduledFuture<?> check;

        TrackedRecording(Path jfcFile, Path ownedOutputFile, Recording recording) {
            this.jfcFile = jfcFile;
            this.ownedOutputFile = ownedOutputFile;
            this.recording = recording;
        }

        boolean isJfrSync() {
            return ownedOutputFile != null;
        }

        synchronized boolean isFrozen() {
            return recording == null;
        }

        /** Returns the read-only descriptor of the ghost, or null if there is no ghost. */
        synchronized SerializableRecordingDescriptor ghost() {
            return ghost;
        }

        /** Returns the file backing the ghost, or null if there is no ghost. */
        synchronized Path ghostFile() {
            return ghostFile;
        }

        /**
         * Freezes the recording into a ghost if it has an on-disk destination. A recording started
         * by {@code -XX:StartFlightRecording} with a directory as its filename reports none, so the
         * file the JVM wrote into that directory is looked up by its generated name instead.
         */
        synchronized void freeze(long recordingId, LongFunction<Optional<Path>> dumpFileFinder) {
            if (recording == null) {
                return;
            }
            Path destination = recording.getDestination();
            if (destination == null) {
                destination = dumpFileFinder.apply(recordingId).orElse(null);
            }
            if (destination != null) {
                ghost = describeAsStopped(recordingId, recording);
                ghostFile = destination;
            }
            recording = null;
        }

        synchronized boolean hasPendingCheck() {
            return check != null && !check.isDone();
        }

        synchronized void setCheck(ScheduledFuture<?> check) {
            this.check = check;
        }

        synchronized void cancelCheck() {
            if (check != null) {
                check.cancel(false);
                check = null;
            }
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
