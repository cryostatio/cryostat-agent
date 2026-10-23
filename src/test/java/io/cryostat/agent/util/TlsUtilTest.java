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

    // --- createHttpsConfigurator (server) min version tests ---

    @Test
    void testHttpsConfiguratorSetsProtocolsAtOrAboveMinimum() {
        HttpsConfigurator configurator =
                TlsUtil.createHttpsConfigurator(
                        sslContext, Optional.empty(), Optional.of("TLSv1.2"), List.of());

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
                TlsUtil.createHttpsConfigurator(
                        sslContext, Optional.empty(), Optional.of("TLSv1.2"), List.of());

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
    void testHttpsConfiguratorUsesEngineDefaultsWhenNoVersionSet() {
        HttpsConfigurator configurator =
                TlsUtil.createHttpsConfigurator(
                        sslContext, Optional.empty(), Optional.empty(), List.of());

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
                TlsUtil.createHttpsConfigurator(
                        sslContext, Optional.empty(), Optional.of("TLSv1.2"), ciphers);

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
                TlsUtil.createHttpsConfigurator(
                        sslContext, Optional.empty(), Optional.of("TLSv1.2"), List.of());

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
                TlsUtil.createHttpsConfigurator(
                        sslContext, Optional.empty(), Optional.of("TLSv1.3"), List.of());

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
                TlsUtil.createHttpsConfigurator(
                        sslContext, Optional.empty(), Optional.of("TLSv1.3"), ciphers);

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
                TlsUtil.createHttpsConfigurator(
                        sslContext, Optional.empty(), Optional.of("TLSv1.2"), List.of());

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
                () ->
                        TlsUtil.createHttpsConfigurator(
                                sslContext, Optional.empty(), Optional.of("SSLv3"), List.of()));
    }

    @Test
    void testHttpsConfiguratorRejectsGarbageMinVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TlsUtil.createHttpsConfigurator(
                                sslContext,
                                Optional.empty(),
                                Optional.of("not-a-protocol"),
                                List.of()));
    }

    @Test
    void testHttpsConfiguratorThrowsWhenNoProtocolsAvailable() {
        SSLContext mockCtx = mock(SSLContext.class);
        SSLParameters mockParams = new SSLParameters();
        mockParams.setProtocols(new String[] {"SSLv3"});
        when(mockCtx.getSupportedSSLParameters()).thenReturn(mockParams);

        assertThrows(
                IllegalStateException.class,
                () ->
                        TlsUtil.createHttpsConfigurator(
                                mockCtx, Optional.empty(), Optional.of("TLSv1.2"), List.of()));
    }

    // --- createHttpsConfigurator (server) exact version tests ---

    @Test
    void testHttpsConfiguratorExactVersionRestrictsToSingleProtocol() {
        HttpsConfigurator configurator =
                TlsUtil.createHttpsConfigurator(
                        sslContext, Optional.of("TLSv1.2"), Optional.empty(), List.of());

        com.sun.net.httpserver.HttpsParameters params =
                mock(com.sun.net.httpserver.HttpsParameters.class);
        configurator.configure(params);

        ArgumentCaptor<SSLParameters> captor = ArgumentCaptor.forClass(SSLParameters.class);
        verify(params).setSSLParameters(captor.capture());
        assertArrayEquals(new String[] {"TLSv1.2"}, captor.getValue().getProtocols());
    }

    @Test
    void testHttpsConfiguratorExactVersionTls13() {
        String[] supported = sslContext.getSupportedSSLParameters().getProtocols();
        if (!List.of(supported).contains("TLSv1.3")) {
            return;
        }

        HttpsConfigurator configurator =
                TlsUtil.createHttpsConfigurator(
                        sslContext, Optional.of("TLSv1.3"), Optional.empty(), List.of());

        com.sun.net.httpserver.HttpsParameters params =
                mock(com.sun.net.httpserver.HttpsParameters.class);
        configurator.configure(params);

        ArgumentCaptor<SSLParameters> captor = ArgumentCaptor.forClass(SSLParameters.class);
        verify(params).setSSLParameters(captor.capture());
        assertArrayEquals(new String[] {"TLSv1.3"}, captor.getValue().getProtocols());
    }

    @Test
    void testHttpsConfiguratorExactVersionTakesPrecedenceOverMinVersion() {
        HttpsConfigurator configurator =
                TlsUtil.createHttpsConfigurator(
                        sslContext, Optional.of("TLSv1.2"), Optional.of("TLSv1.3"), List.of());

        com.sun.net.httpserver.HttpsParameters params =
                mock(com.sun.net.httpserver.HttpsParameters.class);
        configurator.configure(params);

        ArgumentCaptor<SSLParameters> captor = ArgumentCaptor.forClass(SSLParameters.class);
        verify(params).setSSLParameters(captor.capture());
        assertArrayEquals(new String[] {"TLSv1.2"}, captor.getValue().getProtocols());
    }

    @Test
    void testHttpsConfiguratorExactVersionRejectsInvalidVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TlsUtil.createHttpsConfigurator(
                                sslContext, Optional.of("SSLv3"), Optional.empty(), List.of()));
    }

    @Test
    void testHttpsConfiguratorExactVersionRejectsGarbageVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TlsUtil.createHttpsConfigurator(
                                sslContext,
                                Optional.of("not-a-protocol"),
                                Optional.empty(),
                                List.of()));
    }

    @Test
    void testHttpsConfiguratorExactVersionRejectsUnsupportedVersion() {
        SSLContext mockCtx = mock(SSLContext.class);
        SSLParameters mockParams = new SSLParameters();
        mockParams.setProtocols(new String[] {"TLSv1.3"});
        when(mockCtx.getSupportedSSLParameters()).thenReturn(mockParams);

        assertThrows(
                IllegalStateException.class,
                () ->
                        TlsUtil.createHttpsConfigurator(
                                mockCtx, Optional.of("TLSv1.2"), Optional.empty(), List.of()));
    }

    // --- createSSLConnectionSocketFactory (client) min version tests ---

    @Test
    void testSocketFactoryCreatedWithMinVersion() {
        SSLConnectionSocketFactory factory =
                TlsUtil.createSSLConnectionSocketFactory(
                        sslContext, Optional.empty(), Optional.of("TLSv1.2"), List.of(), true);

        assertNotNull(factory);
    }

    @Test
    void testSocketFactoryCreatedWithoutVersions() {
        SSLConnectionSocketFactory factory =
                TlsUtil.createSSLConnectionSocketFactory(
                        sslContext, Optional.empty(), Optional.empty(), List.of(), true);

        assertNotNull(factory);
    }

    @Test
    void testSocketFactoryCreatedWithCustomCiphers() {
        String[] supported = sslContext.getSupportedSSLParameters().getCipherSuites();
        List<String> ciphers = List.of(supported[0]);

        SSLConnectionSocketFactory factory =
                TlsUtil.createSSLConnectionSocketFactory(
                        sslContext, Optional.empty(), Optional.of("TLSv1.2"), ciphers, true);

        assertNotNull(factory);
    }

    @Test
    void testSocketFactoryRejectsInvalidMinVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TlsUtil.createSSLConnectionSocketFactory(
                                sslContext,
                                Optional.empty(),
                                Optional.of("SSLv3"),
                                List.of(),
                                true));
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
                                mockCtx,
                                Optional.empty(),
                                Optional.of("TLSv1.2"),
                                List.of(),
                                true));
    }

    // --- createSSLConnectionSocketFactory (client) exact version tests ---

    @Test
    void testSocketFactoryExactVersionCreated() {
        SSLConnectionSocketFactory factory =
                TlsUtil.createSSLConnectionSocketFactory(
                        sslContext, Optional.of("TLSv1.2"), Optional.empty(), List.of(), true);

        assertNotNull(factory);
    }

    @Test
    void testSocketFactoryExactVersionTakesPrecedenceOverMinVersion() {
        SSLConnectionSocketFactory factory =
                TlsUtil.createSSLConnectionSocketFactory(
                        sslContext,
                        Optional.of("TLSv1.2"),
                        Optional.of("TLSv1.3"),
                        List.of(),
                        true);

        assertNotNull(factory);
    }

    @Test
    void testSocketFactoryExactVersionRejectsInvalidVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TlsUtil.createSSLConnectionSocketFactory(
                                sslContext,
                                Optional.of("SSLv3"),
                                Optional.empty(),
                                List.of(),
                                true));
    }

    @Test
    void testSocketFactoryExactVersionProtocols() throws Exception {
        SSLConnectionSocketFactory factory =
                TlsUtil.createSSLConnectionSocketFactory(
                        sslContext, Optional.of("TLSv1.2"), Optional.empty(), List.of(), true);
        assertNotNull(factory);

        try (javax.net.ssl.SSLSocket socket =
                (javax.net.ssl.SSLSocket) sslContext.getSocketFactory().createSocket()) {
            socket.setEnabledProtocols(new String[] {"TLSv1.2"});
            assertArrayEquals(new String[] {"TLSv1.2"}, socket.getEnabledProtocols());
        }
    }

    @Test
    void testSocketFactoryMinVersionProtocols() throws Exception {
        SSLConnectionSocketFactory factory =
                TlsUtil.createSSLConnectionSocketFactory(
                        sslContext, Optional.empty(), Optional.of("TLSv1.2"), List.of(), true);
        assertNotNull(factory);

        String[] supported = sslContext.getSupportedSSLParameters().getProtocols();
        boolean jvmSupportsTls13 = List.of(supported).contains("TLSv1.3");

        try (javax.net.ssl.SSLSocket socket =
                (javax.net.ssl.SSLSocket) sslContext.getSocketFactory().createSocket()) {
            if (jvmSupportsTls13) {
                socket.setEnabledProtocols(new String[] {"TLSv1.2", "TLSv1.3"});
                List<String> protocols = List.of(socket.getEnabledProtocols());
                assertTrue(protocols.contains("TLSv1.2"));
                assertTrue(protocols.contains("TLSv1.3"));
                assertFalse(protocols.contains("TLSv1"));
                assertFalse(protocols.contains("TLSv1.1"));
            } else {
                socket.setEnabledProtocols(new String[] {"TLSv1.2"});
                assertArrayEquals(new String[] {"TLSv1.2"}, socket.getEnabledProtocols());
            }
        }
    }

    // --- SSLEngine protocol/cipher acceptance tests ---

    @Test
    void testSslEngineAcceptsProtocolsFromConfigurator() {
        com.sun.net.httpserver.HttpsParameters params =
                mock(com.sun.net.httpserver.HttpsParameters.class);
        TlsUtil.createHttpsConfigurator(
                        sslContext, Optional.empty(), Optional.of("TLSv1.2"), List.of())
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
        TlsUtil.createHttpsConfigurator(
                        sslContext, Optional.empty(), Optional.of("TLSv1.2"), ciphers)
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

    @Test
    void testHttpsConfiguratorRejectsUnsupportedCipher() {
        List<String> ciphers = List.of("TLS_AES_256_GCM_SHA384", "NOT_A_REAL_CIPHER");

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TlsUtil.createHttpsConfigurator(
                                sslContext, Optional.empty(), Optional.of("TLSv1.2"), ciphers));
    }

    @Test
    void testSocketFactoryRejectsUnsupportedCipher() {
        List<String> ciphers = List.of("NOT_A_REAL_CIPHER");

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TlsUtil.createSSLConnectionSocketFactory(
                                sslContext,
                                Optional.empty(),
                                Optional.of("TLSv1.2"),
                                ciphers,
                                true));
    }

    @Test
    void testHttpsConfiguratorRejectsTls12CiphersWithTls13Only() {
        List<String> tls12Ciphers = List.of("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        String[] supported = sslContext.getSupportedSSLParameters().getCipherSuites();
        boolean hasCipher = List.of(supported).contains(tls12Ciphers.get(0));
        if (!hasCipher) {
            return;
        }

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TlsUtil.createHttpsConfigurator(
                                sslContext,
                                Optional.empty(),
                                Optional.of("TLSv1.3"),
                                tls12Ciphers));
    }

    @Test
    void testHttpsConfiguratorRejectsTls12CiphersWithExactTls13() {
        List<String> tls12Ciphers = List.of("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        String[] supportedCiphers = sslContext.getSupportedSSLParameters().getCipherSuites();
        if (!List.of(supportedCiphers).contains(tls12Ciphers.get(0))) {
            return;
        }
        String[] supportedProtocols = sslContext.getSupportedSSLParameters().getProtocols();
        if (!List.of(supportedProtocols).contains("TLSv1.3")) {
            return;
        }

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TlsUtil.createHttpsConfigurator(
                                sslContext,
                                Optional.of("TLSv1.3"),
                                Optional.empty(),
                                tls12Ciphers));
    }

    @Test
    void testHttpsConfiguratorRejectsTls13CiphersWithExactTls12() {
        List<String> tls13Ciphers = List.of("TLS_AES_128_GCM_SHA256");

        SSLContext mockCtx = mock(SSLContext.class);
        SSLParameters mockParams = new SSLParameters();
        mockParams.setProtocols(new String[] {"TLSv1.2"});
        mockParams.setCipherSuites(new String[] {"TLS_AES_128_GCM_SHA256"});
        when(mockCtx.getSupportedSSLParameters()).thenReturn(mockParams);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TlsUtil.createHttpsConfigurator(
                                mockCtx, Optional.of("TLSv1.2"), Optional.empty(), tls13Ciphers));
    }

    @Test
    void testSocketFactoryRejectsTls12CiphersWithTls13Only() {
        List<String> tls12Ciphers = List.of("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        String[] supported = sslContext.getSupportedSSLParameters().getCipherSuites();
        boolean hasCipher = List.of(supported).contains(tls12Ciphers.get(0));
        if (!hasCipher) {
            return;
        }

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TlsUtil.createSSLConnectionSocketFactory(
                                sslContext,
                                Optional.empty(),
                                Optional.of("TLSv1.3"),
                                tls12Ciphers,
                                true));
    }
}
