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

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import io.cryostat.agent.harvest.HarvestModule;
import io.cryostat.agent.remote.RemoteContext;
import io.cryostat.agent.remote.RemoteModule;
import io.cryostat.agent.triggers.TriggerModule;
import io.cryostat.core.JvmIdentifier;
import io.cryostat.core.net.IDException;
import io.cryostat.core.sys.FileSystem;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;
import org.apache.commons.io.IOUtils;
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
    private static final String HTTP_CLIENT_SSL_CTX = "HTTP_CLIENT_SSL_CTX";
    private static final String HTTP_SERVER_SSL_CTX = "HTTP_SERVER_SSL_CTX";

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
            HttpServer http,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_CREDENTIALS_PASS_HASH_FUNCTION)
                    MessageDigest digest,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_CREDENTIALS_USER) String user,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_CREDENTIALS_PASS_LENGTH) int passLength,
            @Named(ConfigModule.CRYOSTAT_AGENT_CALLBACK) URI callback,
            Lazy<Registration> registration,
            FileSystem fs) {
        return new WebServer(
                remoteContexts,
                cryostat,
                http,
                digest,
                user,
                passLength,
                callback,
                registration,
                fs);
    }

    @Provides
    @Singleton
    @Named(HTTP_CLIENT_SSL_CTX)
    public static SSLContext provideClientSslContext(
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
    @Named(HTTP_SERVER_SSL_CTX)
    public static Optional<SSLContext> provideServerSslContext(
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_VERSION) String tlsVersion,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_PASS)
                    Optional<String> keyStorePassFile,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_FILE)
                    Optional<String> keyStoreFilePath,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_TYPE) String keyStoreType,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_ALIAS) String certAlias,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_FILE)
                    Optional<String> certFilePath,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_TYPE) String certType) {
        try {
            // TODO check each of these individually. If none are provided use HTTP. If all are
            // provided use HTTPS. Otherwise, print an error message indicating that all or none
            // must be set, and throw an exception.
            boolean ssl =
                    keyStorePassFile.isPresent()
                            && keyStoreFilePath.isPresent()
                            && certFilePath.isPresent();
            if (!ssl) {
                return Optional.empty();
            }

            SSLContext sslContext = SSLContext.getInstance(tlsVersion);

            // initialize keystore
            try (InputStream pass = MainModule.class.getResourceAsStream(keyStorePassFile.get());
                    InputStream keystore =
                            MainModule.class.getResourceAsStream(keyStoreFilePath.get());
                    InputStream certFile =
                            MainModule.class.getResourceAsStream(certFilePath.get())) {
                String password = IOUtils.toString(pass, StandardCharsets.US_ASCII);
                password = password.substring(0, password.length() - 1);
                KeyStore ks = KeyStore.getInstance(keyStoreType);
                ks.load(keystore, password.toCharArray());

                // set up certificate factory
                CertificateFactory cf = CertificateFactory.getInstance(certType);
                Certificate cert = cf.generateCertificate(certFile);
                if (ks.containsAlias(certAlias)) {
                    throw new IllegalStateException(
                            String.format(
                                    "%s keystore already contains a certificate with alias"
                                            + " \"%s\"",
                                    keyStoreType, certAlias));
                }
                ks.setCertificateEntry(certAlias, cert);

                // set up key manager factory
                KeyManagerFactory kmf =
                        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, password.toCharArray());

                // set up trust manager factory
                TrustManagerFactory tmf =
                        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ks);

                // set up HTTPS context
                sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

                return Optional.of(sslContext);
            }
        } catch (KeyStoreException
                | CertificateException
                | UnrecoverableKeyException
                | KeyManagementException
                | IOException
                | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Provides
    @Singleton
    public static HttpClient provideHttpClient(
            @Named(HTTP_CLIENT_SSL_CTX) SSLContext sslContext,
            AuthorizationType authorizationType,
            @Named(ConfigModule.CRYOSTAT_AGENT_AUTHORIZATION) Optional<String> authorization,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_SSL_VERIFY_HOSTNAME)
                    boolean verifyHostname,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_CONNECT_TIMEOUT_MS) int connectTimeout,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_TIMEOUT_MS) int responseTimeout) {
        Set<Header> headers = new HashSet<>();
        authorization
                .filter(Objects::nonNull)
                .map(v -> new BasicHeader("Authorization", v))
                .ifPresent(headers::add);
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
    @Singleton
    public static HttpServer provideHttpServer(
            ScheduledExecutorService executor,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_HOST) String host,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_PORT) int port,
            @Named(HTTP_SERVER_SSL_CTX) Optional<SSLContext> sslContext) {
        try {
            HttpServer http;
            if (sslContext.isEmpty()) {
                http = HttpServer.create(new InetSocketAddress(host, port), 0);
                http.setExecutor(executor);
            } else {
                HttpsServer https = HttpsServer.create(new InetSocketAddress(host, port), 0);
                https.setHttpsConfigurator(
                        new HttpsConfigurator(sslContext.get()) {
                            public void configure(HttpsParameters params) {
                                try {
                                    SSLContext context = getSSLContext();
                                    SSLEngine engine = context.createSSLEngine();
                                    params.setNeedClientAuth(false);
                                    params.setCipherSuites(engine.getEnabledCipherSuites());
                                    params.setProtocols((engine.getEnabledProtocols()));
                                    params.setSSLParameters(context.getDefaultSSLParameters());
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        });
                http = https;
            }

            http.setExecutor(executor);

            return http;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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
            @Named(ConfigModule.CRYOSTAT_AGENT_REGISTRATION_CHECK_MS) int registrationCheckMs,
            @Named(ConfigModule.CRYOSTAT_AGENT_REGISTRATION_JMX_IGNORE)
                    boolean registrationJmxIgnore,
            @Named(ConfigModule.CRYOSTAT_AGENT_REGISTRATION_JMX_USE_CALLBACK_HOST)
                    boolean registrationJmxUseCallbackHost) {
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
                registrationCheckMs,
                registrationJmxIgnore,
                registrationJmxUseCallbackHost);
    }

    @Provides
    @Singleton
    public static FlightRecorderHelper provideFlightRecorderHelper() {
        return new FlightRecorderHelper();
    }

    @Provides
    @Singleton
    public static FileSystem provideFileSystem() {
        return new FileSystem();
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
