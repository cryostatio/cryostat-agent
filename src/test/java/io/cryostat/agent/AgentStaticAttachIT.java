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

class AgentStaticAttachIT {

    Process dummyAppWithAgent;

    @AfterEach
    void cleanup() throws InterruptedException {
        if (dummyAppWithAgent != null) {
            dummyAppWithAgent.destroyForcibly();
            dummyAppWithAgent.waitFor(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void testAgentStaticAttachWithSystemProperties() throws Exception {
        String jarPath = ProcessTestHelper.getAgentShadedJarPath();

        Map<String, String> properties = new HashMap<>();
        properties.put("cryostat.agent.baseuri", "http://localhost:8081");
        properties.put("cryostat.agent.webclient.tls.required", "false");
        properties.put("cryostat.agent.callback", "http://localhost:8081/");

        dummyAppWithAgent =
                ProcessTestHelper.startDummyAppWithAgentSystemProperties(jarPath, properties);

        StringBuilder dummyOutput = new StringBuilder();
        StringBuilder dummyStderrBuilder = new StringBuilder();

        Thread stdoutThread =
                ProcessTestHelper.captureStream(dummyAppWithAgent.getInputStream(), dummyOutput);
        Thread stderrThread =
                ProcessTestHelper.captureStream(
                        dummyAppWithAgent.getErrorStream(), dummyStderrBuilder);

        boolean dummyReady =
                ProcessTestHelper.waitForOutput(dummyOutput, "Dummy app started", 50, 100);
        Assertions.assertTrue(dummyReady, "Dummy app should start and print PID");

        boolean registrationFailed =
                ProcessTestHelper.waitForOutput(
                        dummyOutput, "Failed to generate credentials", 100, 100);

        dummyAppWithAgent.destroy();
        dummyAppWithAgent.waitFor(2, TimeUnit.SECONDS);
        stderrThread.join(1000);
        stdoutThread.join(1000);

        Assertions.assertTrue(
                registrationFailed, "Agent should fail to register without Cryostat server");

        MatcherAssert.assertThat(
                dummyStderrBuilder.toString(),
                Matchers.not(Matchers.containsString("dynamically")));

        MatcherAssert.assertThat(
                dummyOutput.toString(), Matchers.containsString("http://localhost:8081"));
    }

    @Test
    void testAgentStaticAttachWithAgentArguments() throws Exception {
        String jarPath = ProcessTestHelper.getAgentShadedJarPath();

        Map<String, String> properties = new HashMap<>();
        properties.put("cryostat.agent.baseuri", "http://localhost:8082");
        properties.put("cryostat.agent.webclient.tls.required", "false");
        properties.put("cryostat.agent.callback", "http://localhost:8082/");

        dummyAppWithAgent = ProcessTestHelper.startDummyAppWithAgentArguments(jarPath, properties);

        StringBuilder dummyOutput = new StringBuilder();
        StringBuilder dummyStderrBuilder = new StringBuilder();

        Thread stdoutThread =
                ProcessTestHelper.captureStream(dummyAppWithAgent.getInputStream(), dummyOutput);
        Thread stderrThread =
                ProcessTestHelper.captureStream(
                        dummyAppWithAgent.getErrorStream(), dummyStderrBuilder);

        boolean dummyReady =
                ProcessTestHelper.waitForOutput(dummyOutput, "Dummy app started", 50, 100);
        Assertions.assertTrue(dummyReady, "Dummy app should start and print PID");

        boolean registrationFailed =
                ProcessTestHelper.waitForOutput(
                        dummyOutput, "Failed to generate credentials", 100, 100);
        Assertions.assertTrue(
                registrationFailed, "Agent should fail to register without Cryostat server");

        dummyAppWithAgent.destroy();
        dummyAppWithAgent.waitFor(2, TimeUnit.SECONDS);
        stderrThread.join(1000);
        stdoutThread.join(1000);

        MatcherAssert.assertThat(
                dummyStderrBuilder.toString(),
                Matchers.not(Matchers.containsString("dynamically")));

        MatcherAssert.assertThat(
                dummyOutput.toString(), Matchers.containsString("http://localhost:8082"));
    }
}
