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
package io.cryostat.agent.harvest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import io.cryostat.agent.CryostatClient;
import io.cryostat.agent.FlightRecorderHelper;
import io.cryostat.agent.FlightRecorderHelper.TemplatedRecording;
import io.cryostat.agent.Registration;
import io.cryostat.agent.util.StringUtils;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jdk.jfr.FlightRecorder;
import jdk.jfr.FlightRecorderListener;
import jdk.jfr.Recording;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Harvester implements FlightRecorderListener {

    public static final String RECORDING_NAME_ON_EXIT = "onexit";
    public static final String RECORDING_NAME_HARVESTER_SNAPSHOT = "harvester_snapshot";

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final ScheduledExecutorService executor;
    private final ScheduledExecutorService workerPool;
    private final long period;
    private final String template;
    private final int maxFiles;
    private final RecordingSettings exitSettings;
    private final RecordingSettings periodicSettings;
    private final CryostatClient client;
    private final FlightRecorderHelper flightRecorderHelper;
    private final Set<TemplatedRecording> recordings = ConcurrentHashMap.newKeySet();
    private Optional<TemplatedRecording> sownRecording = Optional.empty();
    private final Map<TemplatedRecording, Path> exitPaths = new ConcurrentHashMap<>();
    private FlightRecorder flightRecorder;
    private Future<?> task;
    private boolean running;

    @SuppressFBWarnings(value = {"EI_EXPOSE_REP2", "CT_CONSTRUCTOR_THROW"})
    public Harvester(
            ScheduledExecutorService executor,
            ScheduledExecutorService workerPool,
            long period,
            String template,
            int maxFiles,
            RecordingSettings exitSettings,
            RecordingSettings periodicSettings,
            CryostatClient client,
            FlightRecorderHelper flightRecorderHelper,
            Registration registration) {
        this.executor = executor;
        this.workerPool = workerPool;
        this.period = period;
        this.template = template;
        this.maxFiles = maxFiles;
        this.exitSettings = exitSettings;
        this.periodicSettings = periodicSettings;
        this.client = client;
        this.flightRecorderHelper = flightRecorderHelper;

        registration.addRegistrationListener(
                evt -> {
                    switch (evt.state) {
                        case REGISTERED:
                            break;
                        case UNREGISTERED:
                            executor.submit(this::stop);
                            break;
                        case REFRESHED:
                            break;
                        case PUBLISHED:
                            executor.submit(this::start);
                            break;
                        default:
                            break;
                    }
                });
    }

    public void start() {
        executor.submit(
                () -> {
                    if (running) {
                        return;
                    }
                    this.running = true;
                    if (StringUtils.isBlank(template)) {
                        log.warn("Template not specified");
                    }
                    if (maxFiles <= 0) {
                        log.warn(
                                "Maximum number of files to keep within target is {} <= 0",
                                maxFiles);
                    }
                    if (!FlightRecorder.isAvailable()) {
                        log.error("FlightRecorder is unavailable");
                        return;
                    }
                    log.info("JFR Harvester starting");
                    try {
                        FlightRecorder.addListener(this);
                        this.flightRecorder = FlightRecorder.getFlightRecorder();
                        if (exitSettings.maxAge > 0) {
                            log.info(
                                    "On-stop uploads will contain approximately the most recent"
                                            + " {}ms ({}) of data",
                                    exitSettings.maxAge,
                                    Duration.ofMillis(exitSettings.maxAge));
                        }
                        if (exitSettings.maxSize > 0) {
                            log.info(
                                    "On-stop uploads will contain approximately the most recent {}"
                                            + " bytes ({}) of data",
                                    exitSettings.maxSize,
                                    FileUtils.byteCountToDisplaySize(exitSettings.maxSize));
                        }
                        if (periodicSettings.maxAge > 0) {
                            log.info(
                                    "Periodic uploads will contain approximately the most recent"
                                            + " {}ms ({}) of data",
                                    periodicSettings.maxAge,
                                    Duration.ofMillis(periodicSettings.maxAge));
                        }
                        if (periodicSettings.maxSize > 0) {
                            log.info(
                                    "On-stop uploads will contain approximately the most recent {}"
                                            + " bytes ({}) of data",
                                    periodicSettings.maxSize,
                                    FileUtils.byteCountToDisplaySize(periodicSettings.maxSize));
                        }
                    } catch (SecurityException | IllegalStateException e) {
                        log.error("Harvester could not start", e);
                        return;
                    }
                    startRecording(true);
                    if (this.task != null) {
                        this.task.cancel(true);
                    }
                    if (period > 0) {
                        log.info("JFR Harvester started with period {}", Duration.ofMillis(period));
                        this.task =
                                workerPool.scheduleAtFixedRate(
                                        this::uploadOngoing, period, period, TimeUnit.MILLISECONDS);
                    } else {
                        log.info(
                                "JFR Harvester started, periodic uploads disabled (period {} < 0)",
                                period);
                    }
                });
    }

    public void stop() {
        executor.submit(
                () -> {
                    if (!running) {
                        return;
                    }
                    log.info("Harvester stopping");
                    if (this.task != null) {
                        this.task.cancel(true);
                        this.task = null;
                    }
                    FlightRecorder.removeListener(this);
                    log.info("Harvester stopped");
                    running = false;
                });
    }

    @Override
    public void recordingStateChanged(Recording recording) {
        log.debug("{}({}) {}", recording.getName(), recording.getId(), recording.getState().name());
        getTrackedRecordingById(recording.getId())
                .ifPresent(
                        tr -> {
                            boolean isSownRecording =
                                    sownRecording
                                            .map(TemplatedRecording::getRecording)
                                            .map(Recording::getId)
                                            .map(id -> id == recording.getId())
                                            .orElse(false);
                            switch (recording.getState()) {
                                case NEW:
                                    break;
                                case DELAYED:
                                    break;
                                case RUNNING:
                                    break;
                                case STOPPED:
                                    try {
                                        tr.getRecording().dump(exitPaths.get(tr));
                                        uploadRecording(tr).get();
                                    } catch (IOException e) {
                                        log.error("Failed to dump recording to file", e);
                                    } catch (InterruptedException | ExecutionException e) {
                                        log.warn("Could not upload exit dump file", e);
                                    }
                                    if (isSownRecording) {
                                        safeCloseCurrentRecording();
                                    }
                                    // next
                                    break;
                                case CLOSED:
                                    executor.submit(
                                            () -> {
                                                try {
                                                    recordings.remove(tr);
                                                    Path exitPath = exitPaths.remove(tr);
                                                    Files.deleteIfExists(exitPath);
                                                    log.trace("Deleted temp file {}", exitPath);
                                                } catch (IOException e) {
                                                    log.warn("Could not delete temp file", e);
                                                } finally {
                                                    startRecording(false);
                                                }
                                            });
                                    break;
                                default:
                                    log.warn(
                                            "Unknown state {} for recording with ID {}",
                                            recording.getState(),
                                            recording.getId());
                                    break;
                            }
                        });
    }

    public Future<Void> exitUpload() {
        return CompletableFuture.supplyAsync(
                () -> {
                    running = false;
                    if (flightRecorder == null) {
                        return null;
                    }
                    try {
                        uploadOngoing(PushType.ON_STOP, exitSettings).get();
                    } catch (ExecutionException | InterruptedException e) {
                        log.error("Exit upload failed", e);
                        throw new CompletionException(e);
                    } finally {
                        safeCloseCurrentRecording();
                    }
                    log.info("Harvester stopped");
                    return null;
                },
                executor);
    }

    public void handleNewRecording(TemplatedRecording tr) {
        try {
            Recording recording = tr.getRecording();
            recording.setToDisk(true);
            recording.setDumpOnExit(true);
            recording = this.periodicSettings.apply(recording);
            Path path = Files.createTempFile(null, null);
            Files.write(path, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
            recording.setDestination(path);
            log.trace("{}({}) will dump to {}", recording.getName(), recording.getId(), path);
            this.recordings.add(tr);
            this.exitPaths.put(tr, path);
        } catch (IOException ioe) {
            log.error("Unable to handle recording", ioe);
            tr.getRecording().close();
        }
    }

    private void startRecording(boolean restart) {
        executor.submit(
                () -> {
                    if (StringUtils.isBlank(template)) {
                        return;
                    } else if (restart) {
                        safeCloseCurrentRecording();
                    } else if (sownRecording.isPresent()) {
                        return;
                    }
                    flightRecorderHelper
                            .createRecordingWithPredefinedTemplate(template)
                            .ifPresent(
                                    recording -> {
                                        recording
                                                .getRecording()
                                                .setName("cryostat-agent-harvester");
                                        handleNewRecording(recording);
                                        this.sownRecording = Optional.of(recording);
                                        recording.getRecording().start();
                                        log.info(
                                                "JFR Harvester started recording using template"
                                                        + " \"{}\"",
                                                template);
                                    });
                });
    }

    private void safeCloseCurrentRecording() {
        sownRecording.map(TemplatedRecording::getRecording).ifPresent(Recording::close);
        sownRecording = Optional.empty();
    }

    private Optional<TemplatedRecording> getTrackedRecordingById(long id) {
        if (id < 0) {
            return Optional.empty();
        }
        for (TemplatedRecording recording : this.recordings) {
            if (id == recording.getRecording().getId()) {
                return Optional.of(recording);
            }
        }
        return Optional.empty();
    }

    private Future<Void> uploadOngoing() {
        return uploadOngoing(PushType.SCHEDULED, periodicSettings);
    }

    private Future<Void> uploadOngoing(PushType pushType, RecordingSettings settings) {
        Recording recording = settings.apply(flightRecorder.takeSnapshot());
        try {
            Path exitPath = Files.createTempFile(null, null);
            Files.write(exitPath, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
            recording.dump(exitPath);
            if (Files.size(exitPath) < 1) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("No source recording data"));
            }
            log.trace("Dumping {}({}) to {}", recording.getName(), recording.getId(), exitPath);
            return client.upload(pushType, sownRecording, maxFiles, exitPath)
                    .thenRun(
                            () -> {
                                try {
                                    Files.deleteIfExists(exitPath);
                                    log.trace("Deleted temp file {}", exitPath);
                                } catch (IOException ioe) {
                                    log.error("Failed to clean up snapshot dump file", ioe);
                                }
                            });
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        } finally {
            recording.close();
        }
    }

    private Future<Void> uploadRecording(TemplatedRecording tr) throws IOException {
        Path exitPath = exitPaths.get(tr);
        return client.upload(PushType.EMERGENCY, Optional.of(tr), maxFiles, exitPath);
    }

    public enum PushType {
        SCHEDULED,
        ON_STOP,
        EMERGENCY,
    }

    public static class RecordingSettings implements UnaryOperator<Recording> {
        public String name;
        public long maxSize;
        public long maxAge;

        @Override
        public Recording apply(Recording r) {
            if (StringUtils.isNotBlank(name)) {
                r.setName(name);
            }
            if (maxSize > 0) {
                r.setMaxSize(maxSize);
            }
            if (maxAge > 0) {
                r.setMaxAge(Duration.ofMillis(maxAge));
            }
            return r;
        }
    }
}
