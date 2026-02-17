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
package io.cryostat.agent.insights;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import io.cryostat.agent.ConfigModule;
import io.cryostat.agent.model.PluginInfo;

import com.redhat.insights.agent.AgentBasicReport;
import com.redhat.insights.agent.AgentConfiguration;
import com.redhat.insights.agent.AgentLogger;
import com.redhat.insights.agent.ClassNoticer;
import com.redhat.insights.agent.InsightsAgentHttpClient;
import com.redhat.insights.agent.shaded.InsightsReportController;
import com.redhat.insights.agent.shaded.http.InsightsHttpClient;
import io.smallrye.config.SmallRyeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class InsightsAgentHelperTest {

    @Mock Instrumentation instrumentation;
    @Mock PluginInfo pluginInfo;
    @Mock SmallRyeConfig config;
    @Mock AgentBasicReport report;
    @Mock InsightsReportController controller;
    @Captor ArgumentCaptor<AgentConfiguration> configCaptor;
    @Captor ArgumentCaptor<Supplier<InsightsHttpClient>> clientSupplierCaptor;
    MockedStatic<AgentBasicReport> reportStatic;
    MockedStatic<InsightsReportController> controllerStatic;
    InsightsAgentHelper helper;

    @BeforeEach
    void setupEach() {
        reportStatic = Mockito.mockStatic(AgentBasicReport.class);
        reportStatic.when(() -> AgentBasicReport.of(any())).thenReturn(report);

        controllerStatic = Mockito.mockStatic(InsightsReportController.class);
        controllerStatic
                .when(() -> InsightsReportController.of(any(), any(), any(), any(), any()))
                .thenReturn(controller);

        Map<String, String> env =
                Collections.singletonMap("INSIGHTS_SVC", "http://insights-proxy.example.com:8080");
        when(pluginInfo.getEnvAsMap()).thenReturn(env);

        this.helper = new InsightsAgentHelper(config, instrumentation);
    }

    @AfterEach
    void teardownEach() {
        reportStatic.close();
        controllerStatic.close();
        System.clearProperty(
                "com.redhat.insights.agent.shaded.org.slf4j.simpleLogger.defaultLogLevel");
    }

    @Test
    void testInsightsEnabled() {
        Assertions.assertTrue(helper.isInsightsEnabled(pluginInfo));
    }

    @Test
    void testInsightsDisabled() {
        when(pluginInfo.getEnvAsMap()).thenReturn(Collections.emptyMap());
        Assertions.assertFalse(helper.isInsightsEnabled(pluginInfo));
    }

    @Test
    void testInsightsOptingOut() {
        when(config.getOptionalValue("rht.insights.java.opt-out", boolean.class))
                .thenReturn(Optional.of(true));
        Assertions.assertFalse(helper.isInsightsEnabled(pluginInfo));
    }

    @Test
    void testInsightsNotOptingOut() {
        when(config.getOptionalValue("rht.insights.java.opt-out", boolean.class))
                .thenReturn(Optional.of(false));
        Assertions.assertTrue(helper.isInsightsEnabled(pluginInfo));
    }

    @Test
    void testRunInsightsAgent() {
        when(config.getValue(ConfigModule.CRYOSTAT_AGENT_APP_NAME, String.class))
                .thenReturn("test");

        helper.runInsightsAgent(pluginInfo);

        verify(instrumentation).addTransformer(any(ClassNoticer.class));

        reportStatic.verify(() -> AgentBasicReport.of(configCaptor.capture()));

        AgentConfiguration agentConfig = configCaptor.getValue();
        Assertions.assertEquals("test", agentConfig.getIdentificationName());
        Assertions.assertEquals(
                "http://insights-proxy.example.com:8080", agentConfig.getUploadBaseURL());
        Assertions.assertEquals(Optional.of("dummy"), agentConfig.getMaybeAuthToken());
        Assertions.assertEquals(true, agentConfig.isOCP());
        Assertions.assertEquals(false, agentConfig.shouldDefer());
        Assertions.assertEquals(false, agentConfig.isDebug());

        Assertions.assertNull(
                System.getProperty(
                        "com.redhat.insights.agent.shaded.org.slf4j.simpleLogger.defaultLogLevel"));

        controllerStatic.verify(
                () ->
                        InsightsReportController.of(
                                any(AgentLogger.class),
                                eq(agentConfig),
                                eq(report),
                                clientSupplierCaptor.capture(),
                                isNotNull()));

        InsightsHttpClient client = clientSupplierCaptor.getValue().get();
        Assertions.assertInstanceOf(InsightsAgentHttpClient.class, client);

        verify(controller).generate();
    }

    @Test
    void testInsightsDebugLogging() {
        when(config.getOptionalValue("rht.insights.java.debug", boolean.class))
                .thenReturn(Optional.of(true));

        helper.runInsightsAgent(pluginInfo);

        reportStatic.verify(() -> AgentBasicReport.of(configCaptor.capture()));

        AgentConfiguration agentConfig = configCaptor.getValue();
        Assertions.assertEquals(true, agentConfig.isDebug());

        Assertions.assertEquals(
                "debug",
                System.getProperty(
                        "com.redhat.insights.agent.shaded.org.slf4j.simpleLogger.defaultLogLevel"));
    }
}
