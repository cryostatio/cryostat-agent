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
package io.cryostat.agent.publish;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.ParseException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.cryostat.agent.CryostatClient;
import jdk.jfr.Configuration;
import jdk.jfr.FlightRecorder;
import jdk.jfr.FlightRecorderListener;
import jdk.jfr.Recording;

public class Harvester implements FlightRecorderListener {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final ScheduledExecutorService executor;
    private final long period;
    private final String template;
    private final CryostatClient client;
    private final AtomicLong recordingId = new AtomicLong(-1L);
    private volatile Path exitPath;
    private FlightRecorder flightRecorder;
    private Future<?> task;

    Harvester(ScheduledExecutorService executor, long period, String template, CryostatClient client) {
        this.executor = executor;
        this.period = period;
        this.template = template;
        this.client = client;
    }

    public void start() {
        if (period <= 0) {
            log.info("Harvester disabled, period {} < 0", period);
            return;
        }
        if (!FlightRecorder.isAvailable()) {
            log.error("FlightRecorder is unavailable");
            return;
        }
        log.info("JFR Harvester starting");
        try {
            FlightRecorder.addListener(this);
            this.flightRecorder = FlightRecorder.getFlightRecorder();
            log.info("JFR Harvester started");
        } catch (SecurityException | IllegalStateException e) {
            log.error("Harvester could not start", e);
            return;
        }

        executor.submit(() -> {
            Recording recording = null;
            try {
                Configuration config = Configuration.getConfiguration(template);
                recording = new Recording(config);
                recording.setName("cryostat-agent");
                recording.setToDisk(true);
                recording.setMaxAge(Duration.ofMillis(period));
                recording.setDumpOnExit(true);
                this.exitPath = Files.createTempFile(null, null);
                Files.write(
                        exitPath,
                        new byte[0],
                        StandardOpenOption.TRUNCATE_EXISTING);
                recording.setDestination(this.exitPath);
                recording.start();
                this.recordingId.set(recording.getId());
                startPeriodic();
            } catch (ParseException | IOException e) {
                if (recording != null) {
                    recording.close();
                }
                log.error("Unable to start recording", e);
            }
        });
    }

    private void startPeriodic() {
        if (this.task != null) {
            this.task.cancel(true);
        }
        this.task = executor.scheduleAtFixedRate(this::upload, period, period, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        log.info("Harvester stopping");
        this.task.cancel(true);
        FlightRecorder.removeListener(this);

        long id = recordingId.get();
        // TODO on stop, should we upload a smaller emergency dump recording?
        try {
            upload().get();
        } catch (ExecutionException | InterruptedException e) {
            log.warn("Exit upload failed", e);
        }
        if (flightRecorder != null) {
            for (Recording r : flightRecorder.getRecordings()) {
                if (id == r.getId()) {
                    r.close();
                    break;
                }
            }
        }
        log.info("Harvester stopped");
    }

    private Future<Void> upload() {
        long id = this.recordingId.get();
        if (id < 0) {
            return CompletableFuture.failedFuture(new IllegalStateException("No source recording"));
        }
        try {
            for (Recording recording : this.flightRecorder.getRecordings()) {
                if (id != recording.getId()) {
                    continue;
                }
                Files.write(exitPath, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
                recording.dump(exitPath);
                return client.upload(exitPath);
            }
            return CompletableFuture.failedFuture(new FileNotFoundException("Could not locate source recording"));
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public void recordingStateChanged(Recording recording) {
        if (this.recordingId.get() == recording.getId()) {
            switch (recording.getState()) {
                case NEW:
                    // do nothing
                    break;
                case DELAYED:
                    // do nothing
                    break;
                case RUNNING:
                    // do nothing
                    break;
                case STOPPED:
                    // TODO handle by uploading current recording data to server and restarting
                    // recording
                    break;
                case CLOSED:
                    // TODO handle by checking if file was dumped to disk and uploading that
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
}
