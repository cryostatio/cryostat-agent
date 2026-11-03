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
package io.cryostat.agent;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.cryostat.agent.util.FakeCryostatServer;
import io.cryostat.agent.util.ProcessTestHelper;

import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs an Agent with the periodic JFR harvester enabled against a {@link FakeCryostatServer} */
class HarvesterUploadIT {

    private static final long HARVEST_PERIOD_MS = 1000;
    private static final int STARTUP_ATTEMPTS = 50;
    private static final int LOG_ATTEMPTS = 100;
    private static final long LOG_POLL_MS = 100;

    ScriptedCryostatServer cryostat;
    Process dummyAppWithAgent;
    StringBuilder dummyStdout = new StringBuilder();
    StringBuilder dummyStderr = new StringBuilder();

    @BeforeEach
    void setup() throws IOException {
        cryostat = new ScriptedCryostatServer();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dummyAppWithAgent != null) {
            dummyAppWithAgent.destroyForcibly();
            dummyAppWithAgent.waitFor(2, TimeUnit.SECONDS);
        }
        cryostat.close();
        ProcessTestHelper.printStreams(getClass(), dummyStdout, dummyStderr);
    }

    @Test
    void testSuccessfulScheduledUploadIsLogged() throws Exception {
        startAgent();

        Assertions.assertTrue(
                cryostat.awaitUploadCount(1, 30, TimeUnit.SECONDS),
                "Cryostat should receive a scheduled upload");
        assertLogged("SCHEDULED upload of ");
        assertLogged(" completed");
        assertNotLogged("snapshot upload failed");
    }

    @Test
    void testRejectedUploadIsLoggedAsFailure() throws Exception {
        cryostat.respondToUploadsWith(500);
        startAgent();

        Assertions.assertTrue(
                cryostat.awaitUploadCount(1, 30, TimeUnit.SECONDS),
                "Cryostat should receive a scheduled upload");
        assertLogged("Non-OK response (500)");
        assertLogged("SCHEDULED upload of ");
        assertLogged(" failed");
        assertLogged("SCHEDULED snapshot upload failed");
    }

    @Test
    void testScheduleRecoversAfterFailedUpload() throws Exception {
        cryostat.respondToUploadsWith(500);
        startAgent();

        assertLogged("SCHEDULED snapshot upload failed");
        int failedUploads = cryostat.uploadCount();
        cryostat.respondToUploadsWith(201);

        Assertions.assertTrue(
                cryostat.awaitUploadCount(failedUploads + 1, 30, TimeUnit.SECONDS),
                "Harvester should keep uploading after a failure");
        assertLogged(" completed");
    }

    @Test
    void testSlowUploadCausesLaterTicksToBeSkippedWithoutOverlap() throws Exception {
        cryostat.holdUploads();
        startAgent();

        Assertions.assertTrue(
                cryostat.awaitUploadCount(1, 30, TimeUnit.SECONDS),
                "Cryostat should receive a scheduled upload");
        assertLogged("Skipping SCHEDULED snapshot upload, previous upload still in progress");
        Assertions.assertEquals(
                1,
                cryostat.uploadCount(),
                "no further uploads should start while the first is still in flight");

        cryostat.releaseUploads();

        Assertions.assertTrue(
                cryostat.awaitUploadCount(2, 30, TimeUnit.SECONDS),
                "Harvester should upload again once the slow upload has finished");
        Assertions.assertEquals(
                1, cryostat.maxConcurrentUploads(), "scheduled uploads should never overlap");
    }

    @Test
    void testUploadExceedingTimeoutIsAbortedAndScheduleContinues() throws Exception {
        cryostat.holdUploads();
        startAgent(Map.of("cryostat.agent.harvester.upload.timeout-ms", "2000"));

        Assertions.assertTrue(
                cryostat.awaitUploadCount(1, 30, TimeUnit.SECONDS),
                "Cryostat should receive a scheduled upload");
        assertLogged("Skipping SCHEDULED snapshot upload, previous upload still in progress");
        assertLogged("exceeded 2000ms");
        assertLogged("SCHEDULED snapshot upload failed");

        Assertions.assertTrue(
                cryostat.awaitUploadCount(2, 30, TimeUnit.SECONDS),
                "Harvester should start a new upload once the timed-out one has been aborted");
    }

    private void startAgent() throws Exception {
        startAgent(Map.of());
    }

    private void startAgent(Map<String, String> additionalProperties) throws Exception {
        int agentPort = freePort();
        Map<String, String> properties = new HashMap<>();
        properties.put("cryostat.agent.baseuri", "http://localhost:" + cryostat.port());
        properties.put("cryostat.agent.callback", "http://localhost:" + agentPort + "/");
        properties.put("cryostat.agent.webserver.host", "127.0.0.1");
        properties.put("cryostat.agent.webserver.port", Integer.toString(agentPort));
        properties.put("cryostat.agent.webclient.tls.required", "false");
        properties.put("cryostat.agent.harvester.template", "Continuous");
        properties.put("cryostat.agent.harvester.period-ms", Long.toString(HARVEST_PERIOD_MS));
        properties.putAll(additionalProperties);

        dummyAppWithAgent =
                ProcessTestHelper.startDummyAppWithAgentAndJvmArguments(
                        ProcessTestHelper.getAgentShadedJarPath(), List.of(), properties);
        ProcessTestHelper.captureStream(dummyAppWithAgent.getInputStream(), dummyStdout);
        ProcessTestHelper.captureStream(dummyAppWithAgent.getErrorStream(), dummyStderr);
        Assertions.assertTrue(
                ProcessTestHelper.waitForOutput(
                        dummyStdout, "Dummy app started", STARTUP_ATTEMPTS, LOG_POLL_MS),
                "Dummy app should start");
    }

    private void assertLogged(String text) throws InterruptedException {
        Assertions.assertTrue(
                ProcessTestHelper.waitForOutput(dummyStdout, text, LOG_ATTEMPTS, LOG_POLL_MS),
                "Agent output should contain: " + text);
    }

    private void assertNotLogged(String text) {
        synchronized (dummyStdout) {
            Assertions.assertFalse(
                    dummyStdout.toString().contains(text),
                    "Agent output should not contain: " + text);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    /**
     * Extends the stub server so that the recording upload endpoint can respond with a chosen
     * status or hold requests open, and so that uploads can be counted.
     */
    static class ScriptedCryostatServer extends FakeCryostatServer {

        private static final long UPLOAD_GATE_TIMEOUT_SECONDS = 60;

        private final AtomicInteger uploads = new AtomicInteger();
        private final AtomicInteger uploadsInFlight = new AtomicInteger();
        private final AtomicInteger maxUploadsInFlight = new AtomicInteger();
        private volatile int uploadStatus = 201;
        private volatile CountDownLatch uploadGate;

        ScriptedCryostatServer() throws IOException {
            super();
        }

        void respondToUploadsWith(int status) {
            this.uploadStatus = status;
        }

        void holdUploads() {
            this.uploadGate = new CountDownLatch(1);
        }

        void releaseUploads() {
            CountDownLatch gate = this.uploadGate;
            this.uploadGate = null;
            if (gate != null) {
                gate.countDown();
            }
        }

        int uploadCount() {
            return uploads.get();
        }

        int maxConcurrentUploads() {
            return maxUploadsInFlight.get();
        }

        boolean awaitUploadCount(int count, long timeout, TimeUnit unit)
                throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (uploads.get() < count) {
                if (System.nanoTime() >= deadline) {
                    return false;
                }
                Thread.sleep(100);
            }
            return true;
        }

        @Override
        protected void upload(HttpExchange ex) throws IOException {
            uploads.incrementAndGet();
            int inFlight = uploadsInFlight.incrementAndGet();
            maxUploadsInFlight.accumulateAndGet(inFlight, Math::max);
            try {
                try (InputStream is = ex.getRequestBody()) {
                    is.readAllBytes();
                }
                CountDownLatch gate = uploadGate;
                if (gate != null) {
                    try {
                        gate.await(UPLOAD_GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                respond(ex, uploadStatus, "{}");
            } catch (IOException ioe) {
                ex.close();
            } finally {
                uploadsInFlight.decrementAndGet();
            }
        }

        @Override
        public void close() {
            releaseUploads();
            super.close();
        }
    }
}
