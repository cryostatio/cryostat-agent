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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Optional;

import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ConfigModuleTest {

    @Mock Config config;
    @Mock InetAddress addr;
    MockedStatic<InetAddress> addrMock;

    @BeforeEach
    void setupEach() throws Exception {
        addrMock = Mockito.mockStatic(InetAddress.class);
    }

    @AfterEach
    void teardownEach() {
        addrMock.close();
    }

    @Test
    void testCallback() throws Exception {
        when(config.getValue(ConfigModule.CRYOSTAT_AGENT_CALLBACK, URI.class))
                .thenReturn(new URI("https://callback.example.com:9977"));

        URI result = ConfigModule.provideCryostatAgentCallback(config);
        assertEquals("https://callback.example.com:9977", result.toASCIIString());
    }

    @Test
    void testCallbackKubernetesPodName() throws Exception {
        setupKubernetesCallback();

        when(addr.getHostAddress()).thenReturn("10.2.3.4");
        addrMock.when(() -> InetAddress.getByName("foo.headless.svc.example.com")).thenReturn(addr);

        URI result = ConfigModule.provideCryostatAgentCallback(config);
        assertEquals("https://foo.headless.svc.example.com:9977", result.toASCIIString());
    }

    @Test
    void testCallbackKubernetesPodIP() throws Exception {
        setupKubernetesCallback();

        when(addr.getHostAddress()).thenReturn("10.2.3.4");
        addrMock.when(() -> InetAddress.getByName("foo.headless.svc.example.com"))
                .thenThrow(new UnknownHostException("TEST"));
        addrMock.when(() -> InetAddress.getByName("10-2-3-4.headless.svc.example.com"))
                .thenReturn(addr);

        URI result = ConfigModule.provideCryostatAgentCallback(config);
        assertEquals("https://10-2-3-4.headless.svc.example.com:9977", result.toASCIIString());
    }

    @Test
    void testCallbackKubernetesNoMatch() throws Exception {
        setupKubernetesCallback();

        addrMock.when(() -> InetAddress.getByName("foo.headless.svc.example.com"))
                .thenThrow(new UnknownHostException("TEST"));
        addrMock.when(() -> InetAddress.getByName("10-2-3-4.headless.svc.example.com"))
                .thenThrow(new UnknownHostException("TEST"));

        assertThrows(
                RuntimeException.class, () -> ConfigModule.provideCryostatAgentCallback(config));
    }

    private void setupKubernetesCallback() {
        when(config.getOptionalValue(
                        ConfigModule.CRYOSTAT_AGENT_KUBERNETES_CALLBACK_DOMAIN, String.class))
                .thenReturn(Optional.of("headless.svc.example.com"));
        when(config.getOptionalValue(
                        ConfigModule.CRYOSTAT_AGENT_KUBERNETES_CALLBACK_IP, String.class))
                .thenReturn(Optional.of("10.2.3.4"));
        when(config.getOptionalValue(
                        ConfigModule.CRYOSTAT_AGENT_KUBERNETES_CALLBACK_POD_NAME, String.class))
                .thenReturn(Optional.of("foo"));
        when(config.getOptionalValue(
                        ConfigModule.CRYOSTAT_AGENT_KUBERNETES_CALLBACK_PORT, Integer.class))
                .thenReturn(Optional.of(9977));
        when(config.getOptionalValue(
                        ConfigModule.CRYOSTAT_AGENT_KUBERNETES_CALLBACK_SCHEME, String.class))
                .thenReturn(Optional.of("https"));
    }
}
