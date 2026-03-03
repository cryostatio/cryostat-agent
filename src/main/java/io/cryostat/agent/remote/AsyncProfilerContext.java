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
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.management.MBeanServerInvocationHandler;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import io.cryostat.agent.ConfigModule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.smallrye.config.SmallRyeConfig;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class AsyncProfilerContext extends MutatingRemoteContext {

    private static final String PATH = "/async-profiler/";
    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern PATH_ID_PATTERN =
            Pattern.compile(
                    "^" + PATH + "(" + UUID_PATTERN + "|status)$",
                    Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    private final Path repository;
    private final ProfileIdGenerator idGenerator = () -> UUID.randomUUID().toString();
    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;
    private final Logger log = LoggerFactory.getLogger(getClass());
    private volatile StartProfileRequest currentProfile = null;

    @Inject
    AsyncProfilerContext(
            SmallRyeConfig config,
            ObjectMapper mapper,
            ScheduledExecutorService scheduler,
            @Named(ConfigModule.CRYOSTAT_AGENT_ASYNC_PROFILER_REPOSITORY_PATH) Path repository) {
        super(config);
        this.mapper = mapper;
        this.scheduler = scheduler;
        this.repository = repository;
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
            if (!ensureRepositoryExists(exchange)) {
                return;
            }
            if (!ensureProfilerAvailable(exchange)) {
                return;
            }
            String mtd = exchange.getRequestMethod();
            String id = "";
            switch (mtd) {
                case "GET":
                    id = extractId(exchange);
                    if (StringUtils.isBlank(id)) {
                        handleList(exchange);
                    } else if ("status".equals(id)) {
                        handleStatus(exchange);
                    } else {
                        handleGet(exchange, id);
                    }
                    break;
                case "POST":
                    handleStart(exchange);
                    break;
                case "DELETE":
                    id = extractId(exchange);
                    if (StringUtils.isBlank(id) || "status".equals(id)) {
                        sendHeader(exchange, HttpStatus.SC_BAD_REQUEST);
                        return;
                    }
                    handleDelete(exchange, id);
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

    private Optional<AsyncProfilerMXBean> profilerInstance() {
        try {
            // TODO cache instance once we get one
            return Optional.of(
                    MBeanServerInvocationHandler.newProxyInstance(
                            ManagementFactory.getPlatformMBeanServer(),
                            new ObjectName(AsyncProfilerMXBean.OBJECT_NAME),
                            AsyncProfilerMXBean.class,
                            false));
        } catch (MalformedObjectNameException e) {
            log.error("AsyncProfilerMXBean lookup failure", e);
            return Optional.empty();
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
            sendHeader(exchange, HttpStatus.SC_FORBIDDEN);
        }
        return passed;
    }

    private boolean ensureRepositoryExists(HttpExchange exchange) {
        try {
            if (!Files.isDirectory(repository)) {
                Files.createDirectories(repository);
            }
            return true;
        } catch (IOException e1) {
            log.error("Failed to check or create async-profiler repository", e1);
            sendHeader(exchange, HttpStatus.SC_INTERNAL_SERVER_ERROR);
            return false;
        }
    }

    private boolean ensureProfilerAvailable(HttpExchange exchange) {
        try {
            return profilerInstance()
                    .map(AsyncProfilerMXBean::getVersion)
                    .map(StringUtils::isNotBlank)
                    .orElse(false);
        } catch (Exception e) {
            log.error("async-profiler failure", e);
            return false;
        }
    }

    private static String extractId(HttpExchange exchange) throws IOException {
        Matcher m = PATH_ID_PATTERN.matcher(exchange.getRequestURI().getPath());
        if (!m.find()) {
            return "";
        }
        return m.group(1);
    }

    private Optional<Path> getProfile(String id) {
        Path p = repository.resolve(id);
        if (Files.isRegularFile(p)) {
            return Optional.of(p);
        }
        return Optional.empty();
    }

    private void handleStatus(HttpExchange exchange) {
        try (OutputStream response = exchange.getResponseBody()) {
            Map<String, Object> status = new HashMap<>();
            status.put("status", getProfilerStatus());
            status.put("availableEvents", getAvailableEvents());
            status.put("currentProfile", this.currentProfile);
            exchange.sendResponseHeaders(HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
            mapper.writeValue(response, status);
        } catch (Exception e) {
            log.error("status serialization failure", e);
        }
    }

    private String asyncProfilerExec(String cmd) throws IOException {
        log.debug("async-profiler execute {} ...", cmd);
        String out = profilerInstance().orElseThrow().execute(cmd).strip();
        log.debug("async-profiler execute {}: \"{}\"", cmd, out);
        return out;
    }

    private ProfilerStatus getProfilerStatus() throws IOException {
        String status = asyncProfilerExec("status");
        log.debug("async-profiler status: \"{}\"", status);
        return ProfilerStatus.fromExecOutput(status);
    }

    private Map<String, List<String>> getAvailableEvents() throws IOException {
        HashMap<String, List<String>> map = new HashMap<>();
        List<String> parts =
                Arrays.asList(asyncProfilerExec("list").split("\n")).stream()
                        .filter(StringUtils::isNotBlank)
                        .collect(Collectors.toList());
        String currentSection = null;
        for (String part : parts) {
            part = part.strip();
            if (part.endsWith(":")) {
                String partName =
                        part.substring(0, part.length() - 1).replaceAll("\\s", "_").toLowerCase();
                currentSection = partName;
                map.put(currentSection, new ArrayList<>());
            } else {
                map.get(currentSection).add(part);
            }
        }
        return map;
    }

    private void handleList(HttpExchange exchange) {
        try (OutputStream response = exchange.getResponseBody()) {
            List<AsyncProfile> profiles =
                    Files.list(repository)
                            .map(
                                    p -> {
                                        String name = p.getFileName().toString();
                                        // exclude file being written to by the active session
                                        // since that file content is not yet ready to be exported
                                        if (this.currentProfile != null
                                                && name.equals(this.currentProfile.id)) {
                                            return null;
                                        }
                                        try {
                                            BasicFileAttributes bfa =
                                                    Files.readAttributes(
                                                            p, BasicFileAttributes.class);
                                            return new AsyncProfile(name, bfa);
                                        } catch (IOException ioe) {
                                            log.error("failed to read file attributes", ioe);
                                            return new AsyncProfile(
                                                    name,
                                                    Instant.EPOCH.toEpochMilli(),
                                                    Instant.EPOCH.toEpochMilli(),
                                                    0);
                                        }
                                    })
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
            profiles.sort(Comparator.<AsyncProfile>comparingLong(p -> p.startTime).reversed());
            exchange.sendResponseHeaders(HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
            mapper.writeValue(response, profiles);
        } catch (Exception e) {
            log.error("profiles serialization failure", e);
        }
    }

    private void handleGet(HttpExchange exchange, String profileId) {
        getProfile(profileId)
                .ifPresentOrElse(
                        profile -> {
                            try (InputStream stream = Files.newInputStream(profile);
                                    OutputStream response = exchange.getResponseBody()) {
                                exchange.sendResponseHeaders(HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
                                stream.transferTo(response);
                            } catch (IOException ioe) {
                                log.error("I/O error", ioe);
                                sendHeader(exchange, HttpStatus.SC_INTERNAL_SERVER_ERROR);
                            }
                        },
                        () -> sendHeader(exchange, HttpStatus.SC_NOT_FOUND));
    }

    @SuppressFBWarnings("NP_UNWRITTEN_PUBLIC_OR_PROTECTED_FIELD")
    private void handleStart(HttpExchange exchange) throws IOException {
        if (getProfilerStatus().equals(ProfilerStatus.RUNNING)) {
            sendHeader(exchange, HttpStatus.SC_BAD_REQUEST);
            return;
        }
        try (InputStream body = exchange.getRequestBody()) {
            StartProfileRequest req = mapper.readValue(body, StartProfileRequest.class);
            if (!req.isValid()) {
                log.warn("Invalid profile start request: {}", req);
                sendHeader(exchange, HttpStatus.SC_BAD_REQUEST);
                return;
            }
            String id = idGenerator.next();
            long durationSeconds = req.duration;
            this.currentProfile = req;
            this.currentProfile.id = id;
            // incoming request expects duration specified in seconds, but we use epoch millis for
            // start timestamp and completed file content duration
            this.currentProfile.duration =
                    TimeUnit.MILLISECONDS.convert(durationSeconds, TimeUnit.SECONDS);
            Path profile = repository.resolve(this.currentProfile.id);
            Files.deleteIfExists(profile);
            String events =
                    req.events.stream()
                            .map(s -> String.format("event=%s", s))
                            .collect(Collectors.joining(","));
            this.scheduler.schedule(
                    () -> AsyncProfilerContext.this.currentProfile = null,
                    durationSeconds,
                    TimeUnit.SECONDS);
            this.currentProfile.startTime = Instant.now().toEpochMilli();
            asyncProfilerExec(
                    String.format(
                            "start,jfr,%s,timeout=%d,file=%s", events, durationSeconds, profile));
            exchange.sendResponseHeaders(HttpStatus.SC_CREATED, BODY_LENGTH_UNKNOWN);
            try (OutputStream response = exchange.getResponseBody()) {
                mapper.writeValue(response, id);
            }
        } catch (IOException e) {
            log.error("I/O failure", e);
            sendHeader(exchange, HttpStatus.SC_INTERNAL_SERVER_ERROR);
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.error("Failed to start profile", e);
            this.currentProfile = null;
            sendHeader(exchange, HttpStatus.SC_BAD_REQUEST);
        }
    }

    private void handleDelete(HttpExchange exchange, String profileId) throws IOException {
        try {
            getProfile(profileId)
                    .ifPresentOrElse(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                    sendHeader(exchange, HttpStatus.SC_NO_CONTENT);
                                } catch (IOException ioe) {
                                    log.error("Deletion failed", ioe);
                                    sendHeader(exchange, HttpStatus.SC_INTERNAL_SERVER_ERROR);
                                }
                            },
                            () -> sendHeader(exchange, HttpStatus.SC_NOT_FOUND));
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

    @SuppressFBWarnings("NP_UNWRITTEN_PUBLIC_OR_PROTECTED_FIELD")
    static class StartProfileRequest {
        public String id;
        public long startTime;
        public List<String> events;
        public long duration;

        boolean isValid() {
            return !events.isEmpty();
        }
    }

    @SuppressFBWarnings("URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD")
    static class AsyncProfile {
        public String id;
        public long startTime;
        public long duration;
        public long size;

        AsyncProfile(String id, long startTime, long duration, long size) {
            this.id = id;
            this.startTime = startTime;
            this.duration = duration;
            this.size = size;
        }

        AsyncProfile(String id, BasicFileAttributes bfa) {
            this(
                    id,
                    bfa.creationTime().toInstant().toEpochMilli(),
                    bfa.lastModifiedTime().toInstant().toEpochMilli()
                            - bfa.creationTime().toInstant().toEpochMilli(),
                    bfa.size());
        }
    }

    enum ProfilerStatus {
        STOPPED,
        RUNNING,
        UNKNOWN,
        ;

        public static ProfilerStatus fromExecOutput(String s) {
            if (s.toLowerCase().contains("is not active")) {
                return STOPPED;
            }
            if (s.toLowerCase().contains("is running")) {
                return RUNNING;
            }
            return UNKNOWN;
        }
    }

    interface ProfileIdGenerator {
        String next();
    }

    // https://github.com/async-profiler/async-profiler/blob/2a4f329cbae8f849642d4cd74b766ced79ed3557/src/api/one/profiler/AsyncProfilerMXBean.java#L19
    public interface AsyncProfilerMXBean {
        public String OBJECT_NAME = "one.profiler:type=AsyncProfiler";

        String getVersion();

        String execute(String command)
                throws IllegalArgumentException, IllegalStateException, java.io.IOException;
    }
}
