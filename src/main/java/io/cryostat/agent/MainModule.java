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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.ReflectionException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import io.cryostat.agent.Harvester.RecordingSettings;
import io.cryostat.core.net.JFRConnection;
import io.cryostat.core.net.JFRConnectionToolkit;
import io.cryostat.core.sys.Environment;
import io.cryostat.core.sys.FileSystem;
import io.cryostat.core.tui.ClientWriter;

import dagger.Lazy;
import dagger.Module;
import dagger.Provides;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Module(
        includes = {
            ConfigModule.class,
        })
public abstract class MainModule {

    // one for outbound HTTP requests, one for incoming HTTP requests, and one as a general worker
    private static final int NUM_WORKER_THREADS = 3;
    private static final String JVM_ID = "JVM_ID";

    @Provides
    @Singleton
    public static AtomicInteger provideThreadId() {
        return new AtomicInteger(0);
    }

    @Provides
    @Singleton
    public static ScheduledExecutorService provideExecutor(AtomicInteger threadId) {
        return Executors.newScheduledThreadPool(
                NUM_WORKER_THREADS,
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
    public static HttpClient provideHttpClient(
            ScheduledExecutorService executor,
            SSLContext sslContext,
            @Named(ConfigModule.CRYOSTAT_AGENT_SSL_VERIFY_HOSTNAME) boolean verifyHostname) {
        System.getProperties()
                .setProperty(
                        "jdk.internal.httpclient.disableHostnameVerification",
                        Boolean.toString(!verifyHostname));
        return HttpClient.newBuilder()
                .executor(executor)
                .connectTimeout(Duration.ofSeconds(1))
                .sslContext(sslContext)
                .build();
    }

    @Provides
    @Singleton
    public static CryostatClient provideCryostatClient(
            HttpClient http,
            @Named(JVM_ID) String jvmId,
            @Named(ConfigModule.CRYOSTAT_AGENT_APP_NAME) String appName,
            @Named(ConfigModule.CRYOSTAT_AGENT_BASEURI) URI baseUri,
            @Named(ConfigModule.CRYOSTAT_AGENT_CALLBACK) URI callback,
            @Named(ConfigModule.CRYOSTAT_AGENT_REALM) String realm,
            @Named(ConfigModule.CRYOSTAT_AGENT_AUTHORIZATION) String authorization) {
        return new CryostatClient(http, jvmId, appName, baseUri, callback, realm, authorization);
    }

    @Provides
    @Singleton
    public static Registration provideRegistration(
            ScheduledExecutorService executor,
            CryostatClient cryostat,
            @Named(JVM_ID) String jvmId,
            @Named(ConfigModule.CRYOSTAT_AGENT_APP_NAME) String appName,
            @Named(ConfigModule.CRYOSTAT_AGENT_REALM) String realm,
            @Named(ConfigModule.CRYOSTAT_AGENT_HOSTNAME) String hostname,
            @Named(ConfigModule.CRYOSTAT_AGENT_CALLBACK) URI callback,
            @Named(ConfigModule.CRYOSTAT_AGENT_APP_JMX_PORT) int jmxPort,
            @Named(ConfigModule.CRYOSTAT_AGENT_REGISTRATION_RETRY_MS) int registrationRetryMs) {
        return new Registration(
                executor,
                cryostat,
                jvmId,
                appName,
                realm,
                hostname,
                callback,
                jmxPort,
                registrationRetryMs);
    }

    @Provides
    @Singleton
    public static Harvester provideHarvester(
            ScheduledExecutorService executor,
            @Named(ConfigModule.CRYOSTAT_AGENT_HARVESTER_PERIOD_MS) long period,
            @Named(ConfigModule.CRYOSTAT_AGENT_HARVESTER_TEMPLATE) String template,
            @Named(ConfigModule.CRYOSTAT_AGENT_HARVESTER_EXIT_MAX_AGE_MS) long maxAge,
            @Named(ConfigModule.CRYOSTAT_AGENT_HARVESTER_EXIT_MAX_SIZE_B) long maxSize,
            CryostatClient client) {
        RecordingSettings settings = new RecordingSettings();
        settings.maxAge = maxAge;
        settings.maxSize = maxSize;
        return new Harvester(executor, period, template, settings, client);
    }

    @Provides
    @Singleton
    @Named(JVM_ID)
    public static String provideJvmId() {
        Logger log = LoggerFactory.getLogger(JFRConnectionToolkit.class);
        JFRConnectionToolkit tk =
                new JFRConnectionToolkit(
                        new ClientWriter() {
                            @Override
                            public void print(String msg) {
                                log.warn(msg);
                            }
                        },
                        new FileSystem(),
                        new Environment());
        try (JFRConnection connection = tk.connect(tk.createServiceURL("localhost", 0))) {
            String id = connection.getJvmId();
            log.info("Computed self JVM ID: {}", id);
            return id;
        } catch (IOException
                | ReflectionException
                | MBeanException
                | InstanceNotFoundException
                | AttributeNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
