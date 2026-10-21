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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TlsUtil {

    private static final Logger log = LoggerFactory.getLogger(TlsUtil.class);

    private static final List<String> ORDERED_PROTOCOLS =
            List.of("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3");

    private TlsUtil() {}

    /**
     * Creates an {@link SSLConnectionSocketFactory}. When an exact TLS version is specified,
     * connections are restricted to that single protocol version. When only a minimum TLS version
     * is specified, connections are restricted to protocol versions at or above that minimum. When
     * neither is set, the {@link SSLContext}'s default protocols are used.
     *
     * @param sslContext the SSLContext for the client
     * @param exactTlsVersion if present, the exact TLS version to use (takes precedence over
     *     minTlsVersion)
     * @param minTlsVersion if present and exactTlsVersion is absent, the minimum TLS version to
     *     allow (e.g. {@code "TLSv1.2"})
     * @param ciphers the cipher suites to enable; if empty, the JVM defaults are used
     * @param verifyHostname whether to verify server hostnames against the certificate
     * @return a configured {@link SSLConnectionSocketFactory}
     */
    public static SSLConnectionSocketFactory createSSLConnectionSocketFactory(
            SSLContext sslContext,
            Optional<String> exactTlsVersion,
            Optional<String> minTlsVersion,
            List<String> ciphers,
            boolean verifyHostname) {
        String[] protocols = resolveProtocols(sslContext, exactTlsVersion, minTlsVersion);
        String[] cipherArray = resolveCiphers(sslContext, ciphers);
        validateCipherProtocolCompatibility(sslContext, protocols, cipherArray);
        return new SSLConnectionSocketFactory(
                sslContext,
                protocols,
                cipherArray,
                verifyHostname ? new DefaultHostnameVerifier() : NoopHostnameVerifier.INSTANCE);
    }

    /**
     * Creates an {@link HttpsConfigurator}. When an exact TLS version is specified, the HTTPS
     * server is restricted to that single protocol version. When only a minimum TLS version is
     * specified, the server is restricted to protocol versions at or above that minimum. When
     * neither is set, the {@link SSLContext}'s default protocols are used.
     *
     * @param sslContext the SSLContext for the server
     * @param exactTlsVersion if present, the exact TLS version to use (takes precedence over
     *     minTlsVersion)
     * @param minTlsVersion if present and exactTlsVersion is absent, the minimum TLS version to
     *     allow (e.g. {@code "TLSv1.2"})
     * @param ciphers the cipher suites to enable; if empty, the JVM defaults are used
     * @return a configured {@link HttpsConfigurator}
     */
    public static HttpsConfigurator createHttpsConfigurator(
            SSLContext sslContext,
            Optional<String> exactTlsVersion,
            Optional<String> minTlsVersion,
            List<String> ciphers) {
        String[] protocols = resolveProtocols(sslContext, exactTlsVersion, minTlsVersion);
        String[] cipherArray = resolveCiphers(sslContext, ciphers);
        validateCipherProtocolCompatibility(sslContext, protocols, cipherArray);
        return new HttpsConfigurator(sslContext) {
            @Override
            public void configure(HttpsParameters params) {
                try {
                    SSLContext context = getSSLContext();
                    SSLEngine engine = context.createSSLEngine();
                    SSLParameters sslParams = context.getDefaultSSLParameters();
                    sslParams.setNeedClientAuth(false);
                    sslParams.setCipherSuites(
                            cipherArray != null ? cipherArray : engine.getEnabledCipherSuites());
                    sslParams.setProtocols(
                            protocols != null ? protocols : engine.getEnabledProtocols());
                    params.setSSLParameters(sslParams);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    /**
     * Validates that the configured cipher suites are compatible with the effective TLS protocols.
     *
     * <p>Uses the IANA naming convention to distinguish TLS versions: TLS 1.2 IANA names follow the
     * pattern {@code TLS_<KX>_WITH_<Cipher>_<MAC>} (RFC 5246), while TLS 1.3 names follow {@code
     * TLS_<AEAD>_<Hash>} with no {@code _WITH_} (RFC 8446). This is the same heuristic used by the
     * cryostat-operator's {@code filterTLS12Ciphers}.
     *
     * <p>When {@code protocols} is null (no minimum version configured), the SSLContext's default
     * enabled protocols are used for the compatibility check. Note that {@code
     * SSLContext.getInstance()} returns a context that supports "at least" the requested protocol
     * (see the <a
     * href="https://docs.oracle.com/en/java/javase/21/security/java-secure-socket-extension-jsse-reference-guide.html">JSSE
     * Reference Guide</a>). In OpenJDK's SunJSSE provider, all TLS context types share the same
     * server-side defaults (TLSv1.3 + TLSv1.2), so this fallback may report more protocols than the
     * context algorithm name suggests.
     */
    private static void validateCipherProtocolCompatibility(
            SSLContext ctx, String[] protocols, String[] ciphers) {
        if (ciphers == null) {
            return;
        }
        String[] effectiveProtocols =
                protocols != null ? protocols : ctx.createSSLEngine().getEnabledProtocols();
        Set<String> protoSet = new HashSet<>(Arrays.asList(effectiveProtocols));
        boolean hasTls13 = protoSet.contains("TLSv1.3");
        boolean hasTls12OrBelow = protoSet.stream().anyMatch(p -> !p.equals("TLSv1.3"));
        boolean hasAnyTls13Cipher = Arrays.stream(ciphers).anyMatch(c -> !c.contains("_WITH_"));
        boolean hasAnyTls12Cipher = Arrays.stream(ciphers).anyMatch(c -> c.contains("_WITH_"));

        if (hasTls13 && !hasTls12OrBelow && !hasAnyTls13Cipher) {
            throw new IllegalArgumentException(
                    "None of the configured cipher suites are compatible with TLSv1.3: "
                            + Arrays.asList(ciphers)
                            + ". TLS 1.3 cipher suites do not contain '_WITH_' in their names"
                            + " (e.g. TLS_AES_128_GCM_SHA256).");
        }
        if (hasTls12OrBelow && !hasTls13 && !hasAnyTls12Cipher) {
            throw new IllegalArgumentException(
                    "None of the configured cipher suites are compatible with the enabled"
                            + " protocols "
                            + Arrays.asList(effectiveProtocols)
                            + ": "
                            + Arrays.asList(ciphers)
                            + ". TLS 1.2 and below require cipher suites containing '_WITH_'"
                            + " (e.g. TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256).");
        }
    }

    private static String[] resolveCiphers(SSLContext ctx, List<String> ciphers) {
        if (ciphers.isEmpty()) {
            return null;
        }
        Set<String> supported =
                new HashSet<>(Arrays.asList(ctx.getSupportedSSLParameters().getCipherSuites()));
        List<String> unknown =
                ciphers.stream().filter(c -> !supported.contains(c)).collect(Collectors.toList());
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unsupported TLS cipher suites: " + unknown);
        }
        log.debug("TLS cipher suites: {}", ciphers);
        return ciphers.toArray(new String[0]);
    }

    private static String[] resolveProtocols(
            SSLContext ctx, Optional<String> exactVersion, Optional<String> minVersion) {
        if (exactVersion.isPresent()) {
            String exact = exactVersion.get();
            if (!ORDERED_PROTOCOLS.contains(exact)) {
                throw new IllegalArgumentException(
                        "Unknown TLS version: "
                                + exact
                                + ". Supported values: "
                                + ORDERED_PROTOCOLS);
            }
            Set<String> supported =
                    new HashSet<>(Arrays.asList(ctx.getSupportedSSLParameters().getProtocols()));
            if (!supported.contains(exact)) {
                throw new IllegalStateException(
                        "TLS version " + exact + " is not supported by this JVM");
            }
            log.debug("TLS protocol (exact): {}", exact);
            return new String[] {exact};
        }
        if (minVersion.isEmpty()) {
            return null;
        }
        String min = minVersion.get();
        int minIndex = ORDERED_PROTOCOLS.indexOf(min);
        if (minIndex < 0) {
            throw new IllegalArgumentException(
                    "Unknown TLS version: " + min + ". Supported values: " + ORDERED_PROTOCOLS);
        }
        Set<String> supported =
                new HashSet<>(Arrays.asList(ctx.getSupportedSSLParameters().getProtocols()));
        String[] protocols =
                ORDERED_PROTOCOLS.stream()
                        .skip(minIndex)
                        .filter(supported::contains)
                        .toArray(String[]::new);
        if (protocols.length == 0) {
            throw new IllegalStateException(
                    "No TLS protocols available at or above minimum version " + min);
        }
        log.debug("TLS protocols (minimum {}): {}", min, Arrays.asList(protocols));
        return protocols;
    }
}
