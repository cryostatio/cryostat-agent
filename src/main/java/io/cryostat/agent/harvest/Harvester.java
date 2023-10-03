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
import java.text.ParseException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import io.cryostat.agent.CryostatClient;
import io.cryostat.agent.Registration;
import io.cryostat.agent.util.StringUtils;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jdk.jfr.Configuration;
import jdk.jfr.FlightRecorder;
import jdk.jfr.FlightRecorderListener;
import jdk.jfr.Recording;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Harvester implements FlightRecorderListener {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final ScheduledExecutorService executor;
    private final ScheduledExecutorService workerPool;
    private final long period;
    private final String template;
    private final int maxFiles;
    private final RecordingSettings exitSettings;
    private final RecordingSettings periodicSettings;
    private final CryostatClient client;
    private final AtomicLong recordingId = new AtomicLong(-1L);
    private volatile Path exitPath;
    private FlightRecorder flightRecorder;
    private Future<?> task;
    private boolean running;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public Harvester(
            ScheduledExecutorService executor,
            ScheduledExecutorService workerPool,
            long period,
            String template,
            int maxFiles,
            RecordingSettings exitSettings,
            RecordingSettings periodicSettings,
            CryostatClient client,
            Registration registration) {
        this.executor = executor;
        this.workerPool = workerPool;
        this.period = period;
        this.template = template;
        this.maxFiles = maxFiles;
        this.exitSettings = exitSettings;
        this.periodicSettings = periodicSettings;
        this.client = client;

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
                    if (period <= 0) {
                        log.info("Harvester disabled, period {} < 0", period);
                        return;
                    }
                    if (StringUtils.isBlank(template)) {
                        log.info("Template not specified");
                    }
                    if (maxFiles <= 0) {
                        log.info(
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
                        log.info(
                                "JFR Harvester started using template \"{}\" with period {}",
                                template,
                                Duration.ofMillis(period));
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
                    running = true;
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
        log.info("{}({}) {}", recording.getName(), recording.getId(), recording.getState().name());
        if (this.recordingId.get() == recording.getId()) {
            switch (recording.getState()) {
                case NEW:
                    break;
                case DELAYED:
                    break;
                case RUNNING:
                    break;
                case STOPPED:
                    recording.close(); // we should get notified for the CLOSED state next
                    break;
                case CLOSED:
                    executor.submit(
                            () -> {
                                if (running) {
                                    try {
                                        uploadDumpedFile().get();
                                    } catch (ExecutionException
                                            | InterruptedException
                                            | IOException e) {
                                        log.warn("Could not upload exit dump file", e);
                                    } finally {
                                        startRecording(false);
                                    }
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
        }
    }

    public Future<Void> exitUpload() {
        return CompletableFuture.supplyAsync(
                () -> {
                    running = false;
                    if (flightRecorder == null || period <= 0) {
                        return null;
                    }
                    try {
                        uploadOngoing(PushType.ON_STOP, exitSettings).get();
                    } catch (ExecutionException | InterruptedException e) {
                        log.warn("Exit upload failed", e);
                        throw new CompletionException(e);
                    } finally {
                        safeCloseCurrentRecording();
                    }
                    log.info("Harvester stopped");
                    return null;
                },
                executor);
    }

    private void startRecording(boolean restart) {
        executor.submit(
                () -> {
                    if (restart) {
                        safeCloseCurrentRecording();
                    } else if (getById(this.recordingId.get()).isPresent()) {
                        return;
                    }
                    Recording recording = null;
                    try {
                        Configuration config = Configuration.getConfiguration(template);
                        recording = new Recording(config);
                        recording.setName("cryostat-agent");
                        recording.setToDisk(true);
                        recording.setMaxAge(Duration.ofMillis(period));
                        recording.setDumpOnExit(true);
                        this.exitPath = Files.createTempFile(null, null);
                        Files.write(exitPath, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
                        recording.setDestination(this.exitPath);
                        recording.start();
                        this.recordingId.set(recording.getId());
                        startPeriodic();
                    } catch (ParseException | IOException e) {
                        log.error("Unable to start recording", e);
                        if (recording != null) {
                            recording.close();
                        }
                    }
                });
    }

    private void startPeriodic() {
        if (this.task != null) {
            this.task.cancel(true);
        }
        this.task =
                workerPool.scheduleAtFixedRate(
                        this::uploadOngoing, period, period, TimeUnit.MILLISECONDS);
    }

    private void safeCloseCurrentRecording() {
        getById(recordingId.get()).ifPresent(Recording::close);
    }

    private Optional<Recording> getById(long id) {
        if (id < 0) {
            return Optional.empty();
        }
        for (Recording recording : this.flightRecorder.getRecordings()) {
            if (id == recording.getId()) {
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
        if (recording.getSize() < 1) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No source recording data"));
        }
        try {
            Files.write(exitPath, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
            recording.dump(exitPath);
            return client.upload(pushType, template, maxFiles, exitPath);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        } finally {
            recording.close();
        }
    }

    private Future<Void> uploadDumpedFile() throws IOException {
        return client.upload(PushType.EMERGENCY, template, maxFiles, exitPath);
    }

    public enum PushType {
        SCHEDULED,
        ON_STOP,
        EMERGENCY,
    }

    public static class RecordingSettings implements UnaryOperator<Recording> {
        public long maxSize;
        public long maxAge;

        @Override
        public Recording apply(Recording r) {
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
