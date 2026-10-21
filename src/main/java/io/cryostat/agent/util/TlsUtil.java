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
     * Creates an {@link SSLConnectionSocketFactory}. When a minimum TLS version is specified,
     * connections are restricted to protocol versions at or above that minimum. When absent, the
     * {@link SSLContext}'s default protocols are used, preserving backward compatibility. An
     * optional list of cipher suites may also be supplied.
     *
     * @param sslContext the SSLContext for the client
     * @param minTlsVersion if present, the minimum TLS version to allow (e.g. {@code "TLSv1.2"})
     * @param ciphers the cipher suites to enable; if empty, the JVM defaults are used
     * @param verifyHostname whether to verify server hostnames against the certificate
     * @return a configured {@link SSLConnectionSocketFactory}
     */
    public static SSLConnectionSocketFactory createSSLConnectionSocketFactory(
            SSLContext sslContext,
            Optional<String> minTlsVersion,
            List<String> ciphers,
            boolean verifyHostname) {
        String[] protocols = resolveProtocols(sslContext, minTlsVersion);
        String[] cipherArray = resolveCiphers(sslContext, ciphers);
        return new SSLConnectionSocketFactory(
                sslContext,
                protocols,
                cipherArray,
                verifyHostname ? new DefaultHostnameVerifier() : NoopHostnameVerifier.INSTANCE);
    }

    /**
     * Creates an {@link HttpsConfigurator}. When a minimum TLS version is specified, the HTTPS
     * server is restricted to protocol versions at or above that minimum. When absent, the {@link
     * SSLContext}'s default protocols are used, preserving backward compatibility. An optional list
     * of cipher suites may also be supplied.
     *
     * @param sslContext the SSLContext for the server
     * @param minTlsVersion if present, the minimum TLS version to allow (e.g. {@code "TLSv1.2"})
     * @param ciphers the cipher suites to enable; if empty, the JVM defaults are used
     * @return a configured {@link HttpsConfigurator}
     */
    public static HttpsConfigurator createHttpsConfigurator(
            SSLContext sslContext, Optional<String> minTlsVersion, List<String> ciphers) {
        String[] protocols = resolveProtocols(sslContext, minTlsVersion);
        String[] cipherArray = resolveCiphers(sslContext, ciphers);
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
        log.info("TLS cipher suites: {}", ciphers);
        return ciphers.toArray(new String[0]);
    }

    private static String[] resolveProtocols(SSLContext ctx, Optional<String> minVersion) {
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
        log.info("TLS protocols (minimum {}): {}", min, Arrays.asList(protocols));
        return protocols;
    }
}
