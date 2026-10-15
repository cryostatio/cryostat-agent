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
package io.cryostat.agent.util;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import com.sun.net.httpserver.HttpsConfigurator;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TlsUtilTest {

    private SSLContext sslContext;

    @BeforeEach
    void setup() throws NoSuchAlgorithmException {
        sslContext = SSLContext.getInstance("TLS");
        try {
            sslContext.init(null, null, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- createHttpsConfigurator (server) tests ---

    @Test
    void testHttpsConfiguratorSetsProtocolsAtOrAboveMinimum() {
        HttpsConfigurator configurator =
                TlsUtil.createHttpsConfigurator(sslContext, Optional.of("TLSv1.2"), List.of());

        com.sun.net.httpserver.HttpsParameters params =
                mock(com.sun.net.httpserver.HttpsParameters.class);
        configurator.configure(params);

        ArgumentCaptor<SSLParameters> captor = ArgumentCaptor.forClass(SSLParameters.class);
        verify(params).setSSLParameters(captor.capture());
        List<String> protocols = List.of(captor.getValue().getProtocols());
        assertTrue(protocols.contains("TLSv1.2"));
        assertFalse(protocols.contains("TLSv1"));
        assertFalse(protocols.contains("TLSv1.1"));
    }

    @Test
    void testHttpsConfiguratorIncludesTlsv13WhenMinIsTlsv12() {
        String[] supported = sslContext.getSupportedSSLParameters().getProtocols();
        boolean jvmSupportsTls13 = List.of(supported).contains("TLSv1.3");

        HttpsConfigurator configurator =
                TlsUtil.createHttpsConfigurator(sslContext, Optional.of("TLSv1.2"), List.of());

        com.sun.net.httpserver.HttpsParameters params =
                mock(com.sun.net.httpserver.HttpsParameters.class);
        configurator.configure(params);

        ArgumentCaptor<SSLParameters> captor = ArgumentCaptor.forClass(SSLParameters.class);
        verify(params).setSSLParameters(captor.capture());
        List<String> protocols = List.of(captor.getValue().getProtocols());
        if (jvmSupportsTls13) {
            assertTrue(protocols.contains("TLSv1.3"));
        } else {
            assertFalse(protocols.contains("TLSv1.3"));
        }
    }

    @Test
    void testHttpsConfiguratorUsesEngineDefaultsWhenNoMinVersion() {
        HttpsConfigurator configurator =
                TlsUtil.createHttpsConfigurator(sslContext, Optional.empty(), List.of());

        com.sun.net.httpserver.HttpsParameters params =
                mock(com.sun.net.httpserver.HttpsParameters.class);
        configurator.configure(params);

        SSLEngine engine = sslContext.createSSLEngine();
        ArgumentCaptor<SSLParameters> captor = ArgumentCaptor.forClass(SSLParameters.class);
        verify(params).setSSLParameters(captor.capture());
        assertArrayEquals(engine.getEnabledProtocols(), captor.getValue().getProtocols());
    }

    @Test
    void testHttpsConfiguratorSetsCustomCiphers() {
        List<String> ciphers = List.of("TLS_AES_256_GCM_SHA384", "TLS_AES_128_GCM_SHA256");
        HttpsConfigurator configurator =
                TlsUtil.createHttpsConfigurator(sslContext, Optional.of("TLSv1.2"), ciphers);

        com.sun.net.httpserver.HttpsParameters params =
                mock(com.sun.net.httpserver.HttpsParameters.class);
        configurator.configure(params);

        ArgumentCaptor<SSLParameters> captor = ArgumentCaptor.forClass(SSLParameters.class);
        verify(params).setSSLParameters(captor.capture());
        assertArrayEquals(ciphers.toArray(new String[0]), captor.getValue().getCipherSuites());
    }

    @Test
    void testHttpsConfiguratorUsesDefaultCiphersWhenNoneSpecified() {
        HttpsConfigurator configurator =
                TlsUtil.createHttpsConfigurator(sslContext, Optional.of("TLSv1.2"), List.of());

        com.sun.net.httpserver.HttpsParameters params =
                mock(com.sun.net.httpserver.HttpsParameters.class);
        configurator.configure(params);

        SSLEngine engine = sslContext.createSSLEngine();
        ArgumentCaptor<SSLParameters> captor = ArgumentCaptor.forClass(SSLParameters.class);
        verify(params).setSSLParameters(captor.capture());
        assertArrayEquals(engine.getEnabledCipherSuites(), captor.getValue().getCipherSuites());
    }

    @Test
    void testHttpsConfiguratorExcludesTlsv12WhenMinIsTlsv13() {
        HttpsConfigurator configurator =
                TlsUtil.createHttpsConfigurator(sslContext, Optional.of("TLSv1.3"), List.of());

        com.sun.net.httpserver.HttpsParameters params =
                mock(com.sun.net.httpserver.HttpsParameters.class);
        configurator.configure(params);

        ArgumentCaptor<SSLParameters> captor = ArgumentCaptor.forClass(SSLParameters.class);
        verify(params).setSSLParameters(captor.capture());
        List<String> protocols = List.of(captor.getValue().getProtocols());
        assertTrue(protocols.contains("TLSv1.3"));
        assertFalse(protocols.contains("TLSv1.2"));
        assertFalse(protocols.contains("TLSv1.1"));
        assertFalse(protocols.contains("TLSv1"));
    }

    @Test
    void testHttpsConfiguratorMinTlsv13WithTls13Ciphers() {
        List<String> ciphers =
                List.of(
                        "TLS_AES_128_GCM_SHA256",
                        "TLS_AES_256_GCM_SHA384",
                        "TLS_CHACHA20_POLY1305_SHA256");
        HttpsConfigurator configurator =
                TlsUtil.createHttpsConfigurator(sslContext, Optional.of("TLSv1.3"), ciphers);

        com.sun.net.httpserver.HttpsParameters params =
                mock(com.sun.net.httpserver.HttpsParameters.class);
        configurator.configure(params);

        ArgumentCaptor<SSLParameters> captor = ArgumentCaptor.forClass(SSLParameters.class);
        verify(params).setSSLParameters(captor.capture());
        SSLParameters captured = captor.getValue();
        assertArrayEquals(new String[] {"TLSv1.3"}, captured.getProtocols());
        assertArrayEquals(ciphers.toArray(new String[0]), captured.getCipherSuites());
    }

    @Test
    void testHttpsConfiguratorSetsNeedClientAuthFalse() {
        HttpsConfigurator configurator =
                TlsUtil.createHttpsConfigurator(sslContext, Optional.of("TLSv1.2"), List.of());

        com.sun.net.httpserver.HttpsParameters params =
                mock(com.sun.net.httpserver.HttpsParameters.class);
        configurator.configure(params);

        ArgumentCaptor<SSLParameters> captor = ArgumentCaptor.forClass(SSLParameters.class);
        verify(params).setSSLParameters(captor.capture());
        assertFalse(captor.getValue().getNeedClientAuth());
    }

    @Test
    void testHttpsConfiguratorRejectsInvalidMinVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TlsUtil.createHttpsConfigurator(sslContext, Optional.of("SSLv3"), List.of()));
    }

    @Test
    void testHttpsConfiguratorRejectsGarbageMinVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TlsUtil.createHttpsConfigurator(
                                sslContext, Optional.of("not-a-protocol"), List.of()));
    }

    @Test
    void testHttpsConfiguratorThrowsWhenNoProtocolsAvailable() {
        SSLContext mockCtx = mock(SSLContext.class);
        SSLParameters mockParams = new SSLParameters();
        mockParams.setProtocols(new String[] {"SSLv3"});
        when(mockCtx.getSupportedSSLParameters()).thenReturn(mockParams);

        assertThrows(
                IllegalStateException.class,
                () -> TlsUtil.createHttpsConfigurator(mockCtx, Optional.of("TLSv1.2"), List.of()));
    }

    // --- createSSLConnectionSocketFactory (client) tests ---

    @Test
    void testSocketFactoryCreatedWithMinVersion() {
        SSLConnectionSocketFactory factory =
                TlsUtil.createSSLConnectionSocketFactory(
                        sslContext, Optional.of("TLSv1.2"), List.of(), true);

        assertNotNull(factory);
    }

    @Test
    void testSocketFactoryCreatedWithoutMinVersion() {
        SSLConnectionSocketFactory factory =
                TlsUtil.createSSLConnectionSocketFactory(
                        sslContext, Optional.empty(), List.of(), true);

        assertNotNull(factory);
    }

    @Test
    void testSocketFactoryCreatedWithCustomCiphers() {
        String[] supported = sslContext.getSupportedSSLParameters().getCipherSuites();
        List<String> ciphers = List.of(supported[0]);

        SSLConnectionSocketFactory factory =
                TlsUtil.createSSLConnectionSocketFactory(
                        sslContext, Optional.of("TLSv1.2"), ciphers, true);

        assertNotNull(factory);
    }

    @Test
    void testSocketFactoryRejectsInvalidMinVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TlsUtil.createSSLConnectionSocketFactory(
                                sslContext, Optional.of("SSLv3"), List.of(), true));
    }

    @Test
    void testSocketFactoryThrowsWhenNoProtocolsAvailable() {
        SSLContext mockCtx = mock(SSLContext.class);
        SSLParameters mockParams = new SSLParameters();
        mockParams.setProtocols(new String[] {"SSLv3"});
        when(mockCtx.getSupportedSSLParameters()).thenReturn(mockParams);

        assertThrows(
                IllegalStateException.class,
                () ->
                        TlsUtil.createSSLConnectionSocketFactory(
                                mockCtx, Optional.of("TLSv1.2"), List.of(), true));
    }

    // --- SSLEngine protocol/cipher acceptance tests ---

    @Test
    void testSslEngineAcceptsProtocolsFromConfigurator() {
        com.sun.net.httpserver.HttpsParameters params =
                mock(com.sun.net.httpserver.HttpsParameters.class);
        TlsUtil.createHttpsConfigurator(sslContext, Optional.of("TLSv1.2"), List.of())
                .configure(params);

        ArgumentCaptor<SSLParameters> captor = ArgumentCaptor.forClass(SSLParameters.class);
        verify(params).setSSLParameters(captor.capture());
        String[] protocols = captor.getValue().getProtocols();

        SSLEngine engine = sslContext.createSSLEngine();
        engine.setEnabledProtocols(protocols);
        assertTrue(engine.getEnabledProtocols().length > 0);
    }

    @Test
    void testSslEngineAcceptsProtocolsAndCiphers() {
        String[] supportedCiphers = sslContext.getSupportedSSLParameters().getCipherSuites();
        List<String> ciphers = List.of(supportedCiphers[0]);

        com.sun.net.httpserver.HttpsParameters params =
                mock(com.sun.net.httpserver.HttpsParameters.class);
        TlsUtil.createHttpsConfigurator(sslContext, Optional.of("TLSv1.2"), ciphers)
                .configure(params);

        ArgumentCaptor<SSLParameters> captor = ArgumentCaptor.forClass(SSLParameters.class);
        verify(params).setSSLParameters(captor.capture());
        SSLParameters captured = captor.getValue();

        SSLEngine engine = sslContext.createSSLEngine();
        engine.setEnabledProtocols(captured.getProtocols());
        engine.setEnabledCipherSuites(captured.getCipherSuites());
        assertTrue(engine.getEnabledProtocols().length > 0);
        assertEquals(1, engine.getEnabledCipherSuites().length);
    }

    @Test
    void testSslEngineRejectsUnsupportedCipher() {
        SSLEngine engine = sslContext.createSSLEngine();

        assertThrows(
                IllegalArgumentException.class,
                () -> engine.setEnabledCipherSuites(new String[] {"NOT_A_REAL_CIPHER"}));
    }
}
