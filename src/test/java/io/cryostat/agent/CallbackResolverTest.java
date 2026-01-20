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
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import io.cryostat.agent.ConfigModule.CallbackCandidate;

import io.smallrye.config.SmallRyeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.projectnessie.cel.tools.ScriptHost;

@ExtendWith(MockitoExtension.class)
public class CallbackResolverTest {

    @Mock SmallRyeConfig config;
    @Mock InetAddress addr;
    MockedStatic<InetAddress> addrMock;
    CallbackResolver resolver;

    @BeforeEach
    void setupEach() throws Exception {
        addrMock = Mockito.mockStatic(InetAddress.class);
        addr = Mockito.mock(InetAddress.class);
    }

    @AfterEach
    void teardownEach() {
        addrMock.close();
    }

    @Test
    void testCallback() throws Exception {
        when(config.getValue(ConfigModule.CRYOSTAT_AGENT_CALLBACK, URI.class))
                .thenReturn(new URI("https://callback.example.com:9977"));

        List<CallbackCandidate> result = ConfigModule.provideCryostatAgentCallback(config);
        assertEquals(
                List.of(new CallbackCandidate("https", "callback", "example.com", 9977)), result);
    }

    @Test
    void testCallbackComponentsHostname() throws Exception {
        setupCallbackComponents();

        when(addr.getHostAddress()).thenReturn("10.2.3.4");
        addrMock.when(() -> InetAddress.getByName("foo.headless.svc.example.com")).thenReturn(addr);

        URI result = resolver.determineSelfCallback();
        assertEquals("https://foo.headless.svc.example.com:9977", result.toASCIIString());
    }

    @Test
    void testCallbackComponentsDashedIP() throws Exception {
        setupCallbackComponents();

        when(addr.getHostAddress()).thenReturn("10.2.3.4");
        addrMock.when(() -> InetAddress.getByName("foo.headless.svc.example.com"))
                .thenThrow(new UnknownHostException("TEST"));
        addrMock.when(() -> InetAddress.getByName("10-2-3-4.headless.svc.example.com"))
                .thenReturn(addr);

        URI result = resolver.determineSelfCallback();
        assertEquals("https://10-2-3-4.headless.svc.example.com:9977", result.toASCIIString());
    }

    @Test
    void testCallbackComponentsNoMatch() throws Exception {
        setupCallbackComponents();

        addrMock.when(() -> InetAddress.getByName("foo.headless.svc.example.com"))
                .thenThrow(new UnknownHostException("TEST"));
        addrMock.when(() -> InetAddress.getByName("10-2-3-4.headless.svc.example.com"))
                .thenThrow(new UnknownHostException("TEST"));

        assertThrows(
                RuntimeException.class, () -> ConfigModule.provideCryostatAgentCallback(config));
    }

    @Test
    void testCallbackComponentsWithSpace() throws Exception {
        setupCallbackComponents(new String[] {"foo", " 10.2.3.4[replace(\".\", \"-\")]"});

        when(addr.getHostAddress()).thenReturn("10.2.3.4");
        addrMock.when(() -> InetAddress.getByName("foo.headless.svc.example.com"))
                .thenThrow(new UnknownHostException("TEST"));
        addrMock.when(() -> InetAddress.getByName("10-2-3-4.headless.svc.example.com"))
                .thenReturn(addr);

        URI result = resolver.determineSelfCallback();
        assertEquals("https://10-2-3-4.headless.svc.example.com:9977", result.toASCIIString());
    }

    private void setupCallbackComponents() {
        setupCallbackComponents(new String[] {"foo", "10.2.3.4[replace(\".\", \"-\")]"});
    }

    private void setupCallbackComponents(String[] hostnames) {
        resolver =
                new CallbackResolver(
                        ScriptHost.newBuilder().build(),
                        Arrays.asList(hostnames).stream()
                                .map(
                                        hostname ->
                                                new CallbackCandidate(
                                                        "https",
                                                        hostname,
                                                        "headless.svc.example.com",
                                                        9977))
                                .collect(Collectors.toList()));
    }
}
