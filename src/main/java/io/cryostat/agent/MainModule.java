/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.agent;

import java.net.URI;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import dagger.Lazy;
import dagger.Module;
import dagger.Provides;

@Module(includes = {ConfigModule.class})
public abstract class MainModule {

    @Provides
    @Singleton
    public static AtomicInteger provideThreadId() {
        return new AtomicInteger(0);
    }

    @Provides
    @Singleton
    public static ScheduledExecutorService provideExecutor(AtomicInteger threadId) {
        return Executors.newScheduledThreadPool(
                2,
                r -> {
                    Thread thread = new Thread(r);
                    thread.setName("cryostat-agent-worker-" + threadId.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Provides
    @Singleton
    public static WebServer provideWebServer(
            ScheduledExecutorService executor,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_HOST) String host,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_PORT) int port,
            Lazy<Registration> registration) {
        return new WebServer(executor, host, port, registration);
    }

    @Provides
    @Singleton
    public static SSLContext provideSslContext(
            @Named(ConfigModule.CRYOSTAT_AGENT_SSL_TRUST_ALL) boolean trustAll) {
        try {
            if (!trustAll) {
                return SSLContext.getDefault();
            }

            SSLContext sslCtx = SSLContext.getInstance("TLS");
            sslCtx.init(
                    null,
                    new TrustManager[] {
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(X509Certificate[] chain, String authType)
                                    throws CertificateException {}

                            @Override
                            public void checkServerTrusted(X509Certificate[] chain, String authType)
                                    throws CertificateException {}

                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                    },
                    new SecureRandom());
            return sslCtx;
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException(e);
        }
    }

    @Provides
    @Singleton
    public static CryostatClient provideCryostatClient(
            ScheduledExecutorService executor,
            SSLContext sslCtx,
            UUID instanceId,
            @Named(ConfigModule.CRYOSTAT_AGENT_BASEURI) URI baseUri,
            @Named(ConfigModule.CRYOSTAT_AGENT_CALLBACK) URI callback,
            @Named(ConfigModule.CRYOSTAT_AGENT_REALM) String realm,
            @Named(ConfigModule.CRYOSTAT_AGENT_AUTHORIZATION) String authorization,
            @Named(ConfigModule.CRYOSTAT_AGENT_SSL_VERIFY_HOSTNAME) boolean verifyHostname) {
        return new CryostatClient(
                executor,
                sslCtx,
                instanceId,
                baseUri,
                callback,
                realm,
                authorization,
                verifyHostname);
    }

    @Provides
    @Singleton
    public static Registration provideRegistration(
            ScheduledExecutorService executor,
            Lazy<WebServer> webServer,
            CryostatClient cryostat,
            UUID instanceId,
            @Named(ConfigModule.CRYOSTAT_AGENT_APP_NAME) String appName,
            @Named(ConfigModule.CRYOSTAT_AGENT_REALM) String realm,
            @Named(ConfigModule.CRYOSTAT_AGENT_HOSTNAME) String hostname,
            @Named(ConfigModule.CRYOSTAT_AGENT_APP_JMX_PORT) int jmxPort,
            @Named(ConfigModule.CRYOSTAT_AGENT_REGISTRATION_RETRY_MS) int registrationRetryMs) {
        return new Registration(
                executor,
                webServer,
                cryostat,
                instanceId,
                appName,
                realm,
                hostname,
                jmxPort,
                registrationRetryMs);
    }

    @Provides
    @Singleton
    public static UUID provideInstanceID() {
        return UUID.randomUUID();
    }
}
