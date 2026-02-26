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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.cryostat.agent.util.ProcessTestHelper;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AgentDynamicAttachIT {

    Process dummyApp;
    Process agentLauncher;

    @AfterEach
    void setup() throws InterruptedException {
        if (agentLauncher != null) {
            agentLauncher.destroyForcibly();
            agentLauncher.waitFor(2, TimeUnit.SECONDS);
        }
        if (dummyApp != null) {
            dummyApp.destroyForcibly();
            dummyApp.waitFor(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void testAgentDynamicAttachToSeparateProcess() throws Exception {
        String jarPath = ProcessTestHelper.getAgentShadedJarPath();

        dummyApp = ProcessTestHelper.startDummyApp();

        StringBuilder dummyOutput = new StringBuilder();
        StringBuilder dummyStderrBuilder = new StringBuilder();

        Thread stdoutThread =
                ProcessTestHelper.captureStream(dummyApp.getInputStream(), dummyOutput);
        Thread stderrThread =
                ProcessTestHelper.captureStream(dummyApp.getErrorStream(), dummyStderrBuilder);

        boolean dummyReady =
                ProcessTestHelper.waitForOutput(dummyOutput, "Dummy app started", 50, 100);
        Assertions.assertTrue(dummyReady, "Dummy app should start and print PID");

        Map<String, String> properties = new HashMap<>();
        properties.put("cryostat.agent.baseuri", "http://localhost:8080");
        agentLauncher = ProcessTestHelper.startAgentProcess(jarPath, dummyApp.pid(), properties);

        boolean agentExited = agentLauncher.waitFor(10, TimeUnit.SECONDS);
        int agentExitCode = agentExited ? agentLauncher.exitValue() : -1;

        boolean agentFailed =
                ProcessTestHelper.waitForOutput(dummyOutput, "Agent startup failure", 50, 100);
        Assertions.assertTrue(agentFailed, "Agent should fail to start without Cryostat server");

        dummyApp.destroy();
        dummyApp.waitFor(2, TimeUnit.SECONDS);
        stderrThread.join(1000);
        stdoutThread.join(1000);

        // The agent should fail to fully start due to missing Cryostat config
        MatcherAssert.assertThat(
                dummyOutput.toString(), Matchers.containsString("Agent startup failure"));

        // but it should have logged its startup messages
        MatcherAssert.assertThat(
                dummyOutput.toString(),
                Matchers.containsString("DEBUG io.cryostat.agent.Agent - Cryostat Agent version"));

        // The agent launcher should exit successfully after injection
        MatcherAssert.assertThat(agentExitCode, Matchers.is(0));
    }
}
