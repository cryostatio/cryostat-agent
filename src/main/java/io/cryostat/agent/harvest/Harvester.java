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
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import io.cryostat.agent.CryostatClient;
import io.cryostat.agent.FlightRecorderHelper;
import io.cryostat.agent.FlightRecorderHelper.TemplatedRecording;
import io.cryostat.agent.Registration;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jdk.jfr.FlightRecorder;
import jdk.jfr.FlightRecorderListener;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Harvester implements FlightRecorderListener {

    public static final String RECORDING_NAME_ON_EXIT = "onexit";
    public static final String RECORDING_NAME_PERIODIC = "cryostat-agent-harvester";
    private static final String AUTOANALYZE_LABEL = "autoanalyze";

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final ScheduledExecutorService executor;
    private final ScheduledExecutorService workerPool;
    private final long period;
    private final String template;
    private final int maxFiles;
    private final RecordingSettings exitSettings;
    private final long exitTimeout;
    private final RecordingSettings periodicSettings;
    private final boolean autoanalyze;
    private final CryostatClient client;
    private final FlightRecorderHelper flightRecorderHelper;
    private final Set<TemplatedRecording> recordings = ConcurrentHashMap.newKeySet();
    private Optional<TemplatedRecording> sownRecording = Optional.empty();
    private final Map<TemplatedRecording, Path> exitPaths = new ConcurrentHashMap<>();
    private final AtomicBoolean exitUploadInitiated = new AtomicBoolean(false);
    // Set synchronously as soon as an exit upload is requested, before any asynchronous work is
    // submitted. Once set, the JVM is on its way down and the Harvester must not destroy any
    // recording or dump file that the exit upload may still need: the JDK's own
    // "JFR Shutdown Hook" runs concurrently with ours and will stop the sown recording out from
    // under us, and that stop is what writes the tail-end data to its destination file.
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    // Counted down once the sown recording's dump-on-exit destination file has been written and is
    // safe to read, whether that write was performed by our own exit upload or by the JDK's JFR
    // shutdown hook.
    private volatile CountDownLatch exitDumpWritten = new CountDownLatch(1);
    private final AtomicBoolean scheduledUploadInProgress = new AtomicBoolean(false);
    // Tracks whether this instance is currently registered with the FlightRecorder, so that the
    // registration can be restored on the exit path: the countdown of exitDumpWritten is only
    // delivered to a registered listener, and the Harvester may have been stopped (and therefore
    // deregistered) before the JVM began shutting down.
    private final AtomicBoolean listenerRegistered = new AtomicBoolean(false);
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
            long exitTimeout,
            RecordingSettings periodicSettings,
            boolean autoanalyze,
            CryostatClient client,
            FlightRecorderHelper flightRecorderHelper,
            Registration registration) {
        this.executor = executor;
        this.workerPool = workerPool;
        this.period = period;
        this.template = template;
        this.maxFiles = maxFiles;
        this.exitSettings = exitSettings;
        this.exitTimeout = exitTimeout;
        this.periodicSettings = periodicSettings;
        this.autoanalyze = autoanalyze;
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
                    log.debug("JFR Harvester starting");
                    try {
                        addFlightRecorderListener();
                        this.flightRecorder = FlightRecorder.getFlightRecorder();
                        if (exitSettings.maxAge > 0) {
                            log.debug(
                                    "On-stop uploads will contain approximately the most recent"
                                            + " {}ms ({}) of data",
                                    exitSettings.maxAge,
                                    Duration.ofMillis(exitSettings.maxAge));
                        }
                        if (exitSettings.maxSize > 0) {
                            log.debug(
                                    "On-stop uploads will contain approximately the most recent {}"
                                            + " bytes ({}) of data",
                                    exitSettings.maxSize,
                                    FileUtils.byteCountToDisplaySize(exitSettings.maxSize));
                        }
                        if (periodicSettings.maxAge > 0) {
                            log.debug(
                                    "Periodic uploads will contain approximately the most recent"
                                            + " {}ms ({}) of data",
                                    periodicSettings.maxAge,
                                    Duration.ofMillis(periodicSettings.maxAge));
                        }
                        if (periodicSettings.maxSize > 0) {
                            log.debug(
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
                        log.debug(
                                "JFR Harvester started with period {}", Duration.ofMillis(period));
                        this.task =
                                workerPool.scheduleAtFixedRate(
                                        this::uploadOngoingScheduled,
                                        period,
                                        period,
                                        TimeUnit.MILLISECONDS);
                    } else {
                        log.debug(
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
                    log.trace("Harvester stopping");
                    if (this.task != null) {
                        this.task.cancel(true);
                        this.task = null;
                    }
                    if (listenerRegistered.compareAndSet(true, false)) {
                        FlightRecorder.removeListener(this);
                    }
                    log.trace("Harvester stopped");
                    running = false;
                });
    }

    /**
     * Registers this instance as a {@link FlightRecorderListener}, unless it is already registered.
     * Registering twice would cause every recording state change to be handled twice.
     */
    private void addFlightRecorderListener() {
        if (listenerRegistered.compareAndSet(false, true)) {
            FlightRecorder.addListener(this);
        }
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
                                    if (isSownRecording) {
                                        // PlatformRecording#stop flips the state to STOPPED
                                        // *before* writing the destination file, and only notifies
                                        // listeners once that write has completed. Observing the
                                        // STOPPED state is therefore not proof that the dump file
                                        // is readable, but arriving here is. This is the signal the
                                        // exit upload fallback waits on.
                                        exitDumpWritten.countDown();
                                    }
                                    try {
                                        if (!exitUploadInitiated.get()
                                                && !(isSownRecording && shuttingDown.get())) {
                                            tr.getRecording().dump(exitPaths.get(tr));
                                            // This callback can be invoked on the JDK's
                                            // "JFR Shutdown Hook" thread, which the JVM must join
                                            // before it can exit, so it must never block
                                            // indefinitely.
                                            if (exitTimeout > 0) {
                                                uploadRecording(tr)
                                                        .get(exitTimeout, TimeUnit.MILLISECONDS);
                                            } else {
                                                uploadRecording(tr).get();
                                            }
                                        }
                                    } catch (IOException e) {
                                        log.error("Failed to dump recording to file", e);
                                    } catch (InterruptedException
                                            | ExecutionException
                                            | TimeoutException e) {
                                        log.warn("Could not upload exit dump file", e);
                                    }
                                    if (isSownRecording && !shuttingDown.get()) {
                                        // During shutdown the in-flight exit upload owns the
                                        // lifecycle of this recording and closes it in its own
                                        // finally block. Closing it here would drop the recording's
                                        // chunks (PlatformRecording#close does not write the
                                        // destination) while the exit upload is still reading them.
                                        safeCloseCurrentRecording();
                                    }
                                    // next
                                    break;
                                case CLOSED:
                                    if (shuttingDown.get()) {
                                        // Keep the tracking entry and the dump file. The stop that
                                        // preceded this close wrote the tail-end data to
                                        // exitPaths.get(tr), and that file is the exit upload's
                                        // fallback source. It is a temp file in the container's
                                        // ephemeral storage, so leaving it behind on the way out
                                        // costs nothing.
                                        log.trace(
                                                "Shutting down, retaining temp file {} for exit"
                                                        + " upload",
                                                exitPaths.get(tr));
                                        break;
                                    }
                                    executor.submit(
                                            () -> {
                                                try {
                                                    recordings.remove(tr);
                                                    Path exitPath = exitPaths.remove(tr);
                                                    if (exitPath != null) {
                                                        Files.deleteIfExists(exitPath);
                                                        log.trace("Deleted temp file {}", exitPath);
                                                    }
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
        // Must be set on the calling (shutdown hook) thread rather than inside the async task:
        // otherwise the JDK's JFR shutdown hook can stop and close the sown recording during the
        // window between submission and execution, and our own listener callback would tear down
        // the data this upload is about to read.
        shuttingDown.set(true);
        // Also restored on the calling thread, and before the deadline starts running: the only
        // signal that the sown recording's dump-on-exit file has been written is this listener's
        // STOPPED callback, so if the Harvester was stopped (and deregistered) earlier, the
        // fallback below would otherwise wait out its whole budget for a countdown that can never
        // arrive. Re-registering is a no-op while still registered.
        if (flightRecorder != null) {
            try {
                addFlightRecorderListener();
            } catch (SecurityException | IllegalStateException e) {
                log.warn("Could not restore FlightRecorder listener for exit upload", e);
            }
        }
        // The deadline is taken here, on the shutdown hook thread, rather than inside the task.
        // The task is submitted to a single-threaded executor that may still be busy with a
        // periodic upload, and that queueing delay has to count against the budget too - otherwise
        // the caller can block for the queue wait *plus* the full budget.
        final long deadline = deadlineFrom(exitTimeout);
        CompletableFuture<Void> f =
                CompletableFuture.supplyAsync(
                        () -> {
                            running = false;
                            if (flightRecorder == null) {
                                return null;
                            }
                            try {
                                if (!exitUploadInitiated.getAndSet(true)) {
                                    doExitUpload(deadline);
                                }
                            } finally {
                                safeCloseCurrentRecording();
                            }
                            log.trace("Harvester stopped");
                            return null;
                        },
                        executor);
        if (exitTimeout > 0) {
            // Hard backstop. The task runs on a daemon thread, so abandoning it cannot keep the
            // JVM alive; what matters is that the shutdown hook waiting on this future is released
            // and orderly shutdown continues.
            f = f.orTimeout(exitTimeout, TimeUnit.MILLISECONDS);
        }
        return f;
    }

    private void doExitUpload(long deadline) {
        try {
            long remaining = remainingMillis(deadline);
            if (remaining <= 0) {
                throw new TimeoutException(
                        "Exit upload budget of " + exitTimeout + "ms elapsed before starting");
            }
            uploadOngoing(PushType.ON_EXIT, exitSettings).get(remaining, TimeUnit.MILLISECONDS);
        } catch (ExecutionException
                | InterruptedException
                | TimeoutException
                | RuntimeException e) {
            // The snapshot path is the only one that can honour exit-max-age-ms/exit-max-size-b,
            // so it is always tried first. It is also the most fragile: it has to create a
            // snapshot, open a new file and copy chunks out, all while the JDK's JFR shutdown hook
            // may already be tearing the recorder down and while the heap may be exhausted. Fall
            // back to the dump-on-exit file, which the recording writes to its pre-assigned
            // destination as a side effect of being stopped by whoever gets there first.
            log.warn(
                    "Exit snapshot upload failed, falling back to the recording's dump-on-exit"
                            + " file",
                    e);
            // Deliberately not restoring the interrupt flag before the fallback runs: throwing
            // InterruptedException already cleared it, and re-setting it here would make the
            // fallback's await() throw immediately. It is restored once the fallback has had its
            // turn.
            boolean fellBack = exitUploadFallback(deadline);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (!fellBack) {
                log.error("Exit upload failed", e);
                throw new CompletionException(e);
            }
        }
    }

    /**
     * @param budgetMillis the total budget, or a non-positive value for no budget
     * @return a {@link System#nanoTime()}-based deadline, or {@link Long#MAX_VALUE} if unbounded
     */
    private static long deadlineFrom(long budgetMillis) {
        if (budgetMillis <= 0) {
            return Long.MAX_VALUE;
        }
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
    }

    private static long remainingMillis(long deadline) {
        if (deadline == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    }

    /**
     * Last-resort attempt to push the tail-end of the sown recording after the exit snapshot upload
     * has failed.
     *
     * <p>Every recording the Harvester tracks is given a destination file up front (see {@link
     * #handleNewRecording(TemplatedRecording, RecordingSettings)}), and the JDK writes that file
     * synchronously as part of stopping the recording. That makes the file available on two
     * independent paths: we can stop the recording ourselves, or the JDK's own JFR shutdown hook -
     * which runs concurrently with the Agent's and calls {@code PlatformRecorder#destroy} - will
     * have stopped it for us. Either way the bytes are already on disk and only need uploading.
     *
     * @return true if tail-end data was successfully uploaded
     */
    private boolean exitUploadFallback(long deadline) {
        Optional<TemplatedRecording> opt = sownRecording;
        if (opt.isEmpty()) {
            log.warn("No sown recording available for exit upload fallback");
            return false;
        }
        TemplatedRecording tr = opt.get();
        Path exitPath = exitPaths.get(tr);
        if (exitPath == null) {
            log.warn("Sown recording has no dump-on-exit file to fall back to");
            return false;
        }
        try {
            if (!awaitExitDump(tr, deadline)) {
                log.warn(
                        "Timed out waiting for the dump-on-exit file to be written; releasing the"
                                + " shutdown hook");
                return false;
            }
            if (!Files.exists(exitPath) || Files.size(exitPath) < 1) {
                log.warn("Dump-on-exit file {} holds no recording data", exitPath);
                return false;
            }
            log.debug(
                    "Uploading dump-on-exit file {} ({} bytes on disk)",
                    exitPath,
                    Files.size(exitPath));
            long remaining = remainingMillis(deadline);
            if (remaining <= 0) {
                log.warn(
                        "Exit upload budget of {}ms elapsed before the dump-on-exit file could be"
                                + " sent; releasing the shutdown hook",
                        exitTimeout);
                return false;
            }
            client.upload(
                            PushType.ON_EXIT,
                            Optional.of(tr),
                            maxFiles,
                            additionalLabels(tr.getRecording(), false),
                            exitPath)
                    .get(remaining, TimeUnit.MILLISECONDS);
            log.debug("Exit upload fallback succeeded");
            return true;
        } catch (TimeoutException e) {
            log.warn("Exit upload fallback exceeded the remaining shutdown budget", e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted during exit upload fallback", e);
            return false;
        } catch (IOException | ExecutionException | RuntimeException e) {
            log.error("Exit upload fallback failed", e);
            return false;
        }
    }

    /**
     * Blocks until the sown recording's destination file has been written, stopping the recording
     * ourselves if nobody else has yet.
     *
     * <p>The recording's state is not a usable signal here: {@code PlatformRecording#stop} sets
     * STOPPED inside the recorder lock and only writes the destination afterwards, notifying
     * listeners once the write has completed. So a recording can read as STOPPED while its dump
     * file is still empty or partial. {@link #exitDumpWritten} is counted down from the listener
     * callback, which is the first point at which the file is known to be complete.
     */
    private boolean awaitExitDump(TemplatedRecording tr, long deadline)
            throws InterruptedException {
        if (RecordingState.RUNNING.equals(tr.getRecording().getState())) {
            try {
                // Writes the destination file before returning, so no waiting is required.
                tr.getRecording().stop();
                return true;
            } catch (IllegalStateException e) {
                // Lost the race to the JDK's JFR shutdown hook, which is already performing the
                // stop (and therefore the write) on our behalf. Wait for it below.
                log.trace("Sown recording was stopped concurrently", e);
            }
        }
        return exitDumpWritten.await(remainingMillis(deadline), TimeUnit.MILLISECONDS);
    }

    public void handleNewNamedRecording(TemplatedRecording tr, String filename) {
        RecordingSettings settings = new RecordingSettings(periodicSettings);
        settings.name = filename;
        this.handleNewRecording(tr, settings);
    }

    public void handleNewRecording(TemplatedRecording tr) {
        this.handleNewRecording(tr, this.periodicSettings);
    }

    public void handleNewRecording(TemplatedRecording tr, RecordingSettings settings) {
        try {
            Recording recording = tr.getRecording();
            recording.setToDisk(true);
            recording.setDumpOnExit(true);
            recording = settings.apply(recording);
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
                                        handleNewRecording(recording, this.periodicSettings);
                                        this.exitDumpWritten = new CountDownLatch(1);
                                        this.sownRecording = Optional.of(recording);
                                        recording.getRecording().start();
                                        log.debug(
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

    /**
     * Entry point for the periodic upload task. If the previous scheduled upload has not yet
     * completed, this tick is skipped rather than queued, so a slow network cannot cause an
     * unbounded backlog of snapshots. The next tick after the in-flight upload completes will
     * capture the then-latest data. Any failure is logged here, since an exception escaping a task
     * submitted with {@code scheduleAtFixedRate} would otherwise silently cancel all subsequent
     * executions.
     *
     * <p>The in-progress guard is released when the upload future completes, and that completion is
     * what {@code cryostat.agent.harvester.upload.timeout-ms} guarantees: it aborts the underlying
     * request, including a request body write that has stalled against an unresponsive server,
     * which the HTTP client's response timeout does not cover. Configuring that property as zero or
     * less disables the abort, so a single stalled upload can block all subsequent scheduled
     * uploads for as long as the connection hangs.
     */
    private void uploadOngoingScheduled() {
        if (!scheduledUploadInProgress.compareAndSet(false, true)) {
            log.debug(
                    "Skipping {} snapshot upload, previous upload still in progress",
                    PushType.SCHEDULED);
            return;
        }
        log.debug("Starting {} snapshot upload", PushType.SCHEDULED);
        try {
            uploadOngoing(PushType.SCHEDULED, periodicSettings)
                    .whenComplete(
                            (v, t) -> {
                                scheduledUploadInProgress.set(false);
                                if (t instanceof NoRecordingDataException) {
                                    log.debug("{} snapshot upload skipped", PushType.SCHEDULED);
                                } else if (t != null) {
                                    log.warn("{} snapshot upload failed", PushType.SCHEDULED, t);
                                }
                            });
        } catch (Throwable t) {
            scheduledUploadInProgress.set(false);
            log.warn("{} snapshot upload could not be started", PushType.SCHEDULED, t);
            if (t instanceof Error) {
                throw (Error) t;
            }
        }
    }

    private CompletableFuture<Void> uploadOngoing(PushType pushType, RecordingSettings settings) {
        Recording recording = settings.apply(flightRecorder.takeSnapshot());
        log.debug(
                "{} snapshot {}({}) taken, state={}, maxAge={}, maxSize={}",
                pushType,
                recording.getName(),
                recording.getId(),
                recording.getState(),
                recording.getMaxAge(),
                recording.getMaxSize());
        Path exitPath = null;
        boolean uploadStarted = false;
        try {
            exitPath = Files.createTempFile(null, null);
            Files.write(exitPath, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
            recording.dump(exitPath);
            long size = Files.size(exitPath);
            log.debug(
                    "{} snapshot {}({}) dumped {} bytes to {}",
                    pushType,
                    recording.getName(),
                    recording.getId(),
                    size,
                    exitPath);
            if (size < 1) {
                log.debug(
                        "{} snapshot {}({}) dump {} is empty, skipping upload",
                        pushType,
                        recording.getName(),
                        recording.getId(),
                        exitPath);
                return CompletableFuture.failedFuture(new NoRecordingDataException());
            }
            log.trace("Dumping {}({}) to {}", recording.getName(), recording.getId(), exitPath);
            Path dump = exitPath;
            CompletableFuture<Void> upload =
                    client.upload(
                            pushType,
                            sownRecording,
                            maxFiles,
                            additionalLabels(recording, true),
                            dump);
            uploadStarted = true;
            return upload.whenComplete(
                    (v, t) -> {
                        if (t != null) {
                            log.warn("{} upload of {} failed", pushType, dump, t);
                        } else {
                            log.debug("{} upload of {} completed", pushType, dump);
                        }
                        deleteDump(dump);
                    });
        } catch (IOException e) {
            log.warn(
                    "{} snapshot {}({}) could not be dumped",
                    pushType,
                    recording.getName(),
                    recording.getId(),
                    e);
            return CompletableFuture.failedFuture(e);
        } finally {
            recording.close();
            if (!uploadStarted && exitPath != null) {
                deleteDump(exitPath);
            }
        }
    }

    private void deleteDump(Path path) {
        try {
            Files.deleteIfExists(path);
            log.trace("Deleted temp file {}", path);
        } catch (IOException ioe) {
            log.error("Failed to clean up snapshot dump file {}", path, ioe);
        }
    }

    private Future<Void> uploadRecording(TemplatedRecording tr) throws IOException {
        Path exitPath = exitPaths.get(tr);
        return client.upload(
                PushType.ON_STOP,
                Optional.of(tr),
                maxFiles,
                additionalLabels(tr.getRecording(), false),
                exitPath);
    }

    Map<String, String> additionalLabels(Recording recording, boolean isSnapshot) {
        var map = new HashMap<String, String>();
        if (autoanalyze) {
            map.put(AUTOANALYZE_LABEL, String.valueOf(true));
        }
        long startTime;
        long duration;
        if (isSnapshot) {
            Duration maxAge = recording.getMaxAge();
            if (maxAge != null && maxAge.toMillis() > 0) {
                duration = maxAge.toMillis();
                startTime = Instant.now().toEpochMilli() - duration;
            } else {
                log.warn("Snapshot recording has no maxAge set; duration label will be 0");
                duration = 0L;
                startTime = 0L;
            }
        } else {
            Duration dur = recording.getDuration();
            if (dur != null && dur.toMillis() != 0) {
                duration = dur.toMillis();
            } else if (recording.getMaxAge() != null && recording.getMaxAge().toMillis() > 0) {
                duration = recording.getMaxAge().toMillis();
            } else {
                log.warn("Recording has no duration or maxAge set; duration label will be 0");
                duration = 0L;
            }
            startTime =
                    recording.getStartTime() != null ? recording.getStartTime().toEpochMilli() : 0L;
        }
        map.put("startTime", String.valueOf(startTime));
        map.put("duration", String.valueOf(duration));
        map.put("sourceRecordingId", String.valueOf(recording.getId()));
        return map;
    }

    /** Signals that a snapshot contained no data to upload. */
    private static class NoRecordingDataException extends IllegalStateException {
        NoRecordingDataException() {
            super("No source recording data");
        }
    }

    public enum PushType {
        SCHEDULED,
        ON_STOP,
        ON_EXIT,
    }

    public static class RecordingSettings implements UnaryOperator<Recording> {
        public String name;
        public long maxSize;
        public long maxAge;

        public RecordingSettings() {}

        public RecordingSettings(RecordingSettings r) {
            this.name = r.name;
            this.maxSize = r.maxSize;
            this.maxAge = r.maxAge;
        }

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
