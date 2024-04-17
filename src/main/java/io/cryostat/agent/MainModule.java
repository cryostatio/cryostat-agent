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

import java.net.URI;
import java.security.KeyManagementException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import io.cryostat.agent.harvest.HarvestModule;
import io.cryostat.agent.remote.RemoteContext;
import io.cryostat.agent.remote.RemoteModule;
import io.cryostat.agent.triggers.TriggerModule;
import io.cryostat.core.JvmIdentifier;
import io.cryostat.core.net.IDException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.http.Header;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Module(
        includes = {
            ConfigModule.class,
            RemoteModule.class,
            HarvestModule.class,
            TriggerModule.class,
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
            Lazy<Set<RemoteContext>> remoteContexts,
            Lazy<CryostatClient> cryostat,
            ScheduledExecutorService executor,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_HOST) String host,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_PORT) int port,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_CREDENTIALS_PASS_HASH_FUNCTION)
                    MessageDigest digest,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_CREDENTIALS_USER) String user,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_CREDENTIALS_PASS_LENGTH) int passLength,
            @Named(ConfigModule.CRYOSTAT_AGENT_CALLBACK) URI callback,
            Lazy<Registration> registration) {
        return new WebServer(
                remoteContexts,
                cryostat,
                executor,
                host,
                port,
                digest,
                user,
                passLength,
                callback,
                registration);
    }

    @Provides
    @Singleton
    public static SSLContext provideSslContext(
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_SSL_TRUST_ALL) boolean trustAll) {
        try {
            if (!trustAll) {
                return SSLContext.getDefault();
            }

            SSLContext sslCtx = SSLContext.getInstance("TLSv1.2");
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
            SSLContext sslContext,
            AuthorizationType authorizationType,
            @Named(ConfigModule.CRYOSTAT_AGENT_AUTHORIZATION) Optional<String> authorization,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_SSL_VERIFY_HOSTNAME)
                    boolean verifyHostname,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_CONNECT_TIMEOUT_MS) int connectTimeout,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_TIMEOUT_MS) int responseTimeout) {
        Set<Header> headers = new HashSet<>();
        authorization.map(v -> new BasicHeader("Authorization", v)).ifPresent(headers::add);
        HttpClientBuilder builder =
                HttpClients.custom()
                        .setDefaultHeaders(headers)
                        .setSSLContext(sslContext)
                        .setDefaultRequestConfig(
                                RequestConfig.custom()
                                        .setAuthenticationEnabled(true)
                                        .setExpectContinueEnabled(true)
                                        .setConnectTimeout(connectTimeout)
                                        .setSocketTimeout(responseTimeout)
                                        .build());

        if (!verifyHostname) {
            builder = builder.setSSLHostnameVerifier((hostname, session) -> true);
        }

        return builder.build();
    }

    @Provides
    public static ObjectMapper provideObjectMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Provides
    @Singleton
    public static CryostatClient provideCryostatClient(
            ScheduledExecutorService executor,
            ObjectMapper objectMapper,
            HttpClient http,
            @Named(ConfigModule.CRYOSTAT_AGENT_INSTANCE_ID) String instanceId,
            @Named(JVM_ID) String jvmId,
            @Named(ConfigModule.CRYOSTAT_AGENT_APP_NAME) String appName,
            @Named(ConfigModule.CRYOSTAT_AGENT_BASEURI) URI baseUri,
            @Named(ConfigModule.CRYOSTAT_AGENT_REALM) String realm) {
        return new CryostatClient(
                executor, objectMapper, http, instanceId, jvmId, appName, baseUri, realm);
    }

    @Provides
    @Singleton
    public static Registration provideRegistration(
            ScheduledExecutorService executor,
            CryostatClient cryostat,
            @Named(ConfigModule.CRYOSTAT_AGENT_CALLBACK) URI callback,
            WebServer webServer,
            @Named(ConfigModule.CRYOSTAT_AGENT_INSTANCE_ID) String instanceId,
            @Named(JVM_ID) String jvmId,
            @Named(ConfigModule.CRYOSTAT_AGENT_APP_NAME) String appName,
            @Named(ConfigModule.CRYOSTAT_AGENT_REALM) String realm,
            @Named(ConfigModule.CRYOSTAT_AGENT_HOSTNAME) String hostname,
            @Named(ConfigModule.CRYOSTAT_AGENT_APP_JMX_PORT) int jmxPort,
            @Named(ConfigModule.CRYOSTAT_AGENT_REGISTRATION_RETRY_MS) int registrationRetryMs,
            @Named(ConfigModule.CRYOSTAT_AGENT_REGISTRATION_CHECK_MS) int registrationCheckMs) {

        Logger log = LoggerFactory.getLogger(Registration.class);
        return new Registration(
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r);
                            t.setDaemon(true);
                            t.setName("cryostat-agent-registration");
                            t.setUncaughtExceptionHandler(
                                    (thread, err) ->
                                            log.error(
                                                    String.format(
                                                            "[%s] Uncaught exception: %s",
                                                            thread.getName(),
                                                            ExceptionUtils.getStackTrace(err))));
                            return t;
                        }),
                cryostat,
                callback,
                webServer,
                instanceId,
                jvmId,
                appName,
                realm,
                hostname,
                jmxPort,
                registrationRetryMs,
                registrationCheckMs);
    }

    @Provides
    @Singleton
    public static FlightRecorderHelper provideFlightRecorderHelper() {
        return new FlightRecorderHelper();
    }

    @Provides
    @Singleton
    @Named(JVM_ID)
    public static String provideJvmId() {
        try {
            return JvmIdentifier.getLocal().getHash();
        } catch (IDException e) {
            throw new RuntimeException(e);
        }
    }
}
