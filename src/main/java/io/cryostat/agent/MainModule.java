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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import io.cryostat.agent.ConfigModule.BytePass;
import io.cryostat.agent.harvest.HarvestModule;
import io.cryostat.agent.remote.RemoteContext;
import io.cryostat.agent.remote.RemoteModule;
import io.cryostat.agent.triggers.TriggerModule;
import io.cryostat.libcryostat.JvmIdentifier;
import io.cryostat.libcryostat.net.IDException;

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
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.StandardHttpRequestRetryHandler;
import org.apache.http.protocol.HttpContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Module(
        includes = {
            RemoteModule.class,
            HarvestModule.class,
            TriggerModule.class,
            ConfigModule.class,
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
            Lazy<Registration> registration) {
        return new WebServer(
                remoteContexts, cryostat, http, digest, user, passLength, callback, registration);
    }

    @Provides
    @Singleton
    @Named(HTTP_CLIENT_SSL_CTX)
    public static SSLContext provideClientSslContext(
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_VERSION) String clientTlsVersion,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUST_ALL) boolean trustAll,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PATH)
                    Optional<String> truststorePath,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PASS)
                    Optional<BytePass> truststorePass,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PASS_CHARSET)
                    String passCharset,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_TYPE) String truststoreType,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_CERTS)
                    List<TruststoreConfig> truststoreCerts) {
        try {
            if (trustAll) {
                SSLContext sslCtx = SSLContext.getInstance(clientTlsVersion);
                sslCtx.init(
                        null,
                        new TrustManager[] {
                            new X509TrustManager() {
                                @Override
                                public void checkClientTrusted(
                                        X509Certificate[] chain, String authType)
                                        throws CertificateException {}

                                @Override
                                public void checkServerTrusted(
                                        X509Certificate[] chain, String authType)
                                        throws CertificateException {}

                                @Override
                                public X509Certificate[] getAcceptedIssuers() {
                                    return new X509Certificate[0];
                                }
                            }
                        },
                        null);
                return sslCtx;
            }

            SSLContext sslCtx = SSLContext.getInstance(clientTlsVersion);

            // set up trust manager factory
            TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);

            X509TrustManager defaultTrustManager = null;
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    defaultTrustManager = (X509TrustManager) tm;
                    break;
                }
            }

            KeyStore ts = KeyStore.getInstance(truststoreType);
            ts.load(null, null);

            // initialize truststore with user provided path and pass
            if (!truststorePath.isEmpty() && !truststorePass.isEmpty()) {
                Charset charset = Charset.forName(passCharset);
                CharsetDecoder decoder = charset.newDecoder();
                ByteBuffer byteBuffer = ByteBuffer.wrap(truststorePass.get().get());
                CharBuffer charBuffer = decoder.decode(byteBuffer);
                try (InputStream truststore = new FileInputStream(truststorePath.get())) {
                    ts.load(truststore, charBuffer.array());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } finally {
                    byteBuffer.clear();
                    charBuffer.clear();
                    truststorePass.get().clear();
                }
            } else if (!truststorePath.isEmpty() || !truststorePass.isEmpty()) {
                throw new IllegalArgumentException(
                        String.format(
                                "To import a truststore, provide both the path to the truststore"
                                    + " and the pass, or a path to a file containing the pass"));
            }

            // initialize truststore with user provided certs
            for (TruststoreConfig truststore : truststoreCerts) {
                // load truststore with certificatesCertificate
                try (InputStream certFile = new FileInputStream(truststore.getPath())) {
                    CertificateFactory cf = CertificateFactory.getInstance(truststore.getType());
                    Certificate cert = cf.generateCertificate(certFile);
                    if (ts.containsAlias(truststore.getType())) {
                        throw new IllegalStateException(
                                String.format(
                                        "truststore already contains a certificate with alias"
                                                + " \"%s\"",
                                        truststore.getAlias()));
                    }
                    ts.setCertificateEntry(truststore.getAlias(), cert);
                } catch (CertificateException e) {
                    throw new RuntimeException(e);
                }
            }

            // set up trust manager factory
            tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);

            X509TrustManager customTrustManager = null;
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    customTrustManager = (X509TrustManager) tm;
                    break;
                }
            }

            final X509TrustManager finalDefaultTM = defaultTrustManager;
            final X509TrustManager finalCustomTM = customTrustManager;
            X509TrustManager mergedTrustManager =
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType)
                                throws CertificateException {
                            try {
                                finalCustomTM.checkClientTrusted(chain, authType);
                            } catch (CertificateException e) {
                                finalDefaultTM.checkClientTrusted(chain, authType);
                            }
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType)
                                throws CertificateException {
                            try {
                                finalCustomTM.checkServerTrusted(chain, authType);
                            } catch (CertificateException e) {
                                finalDefaultTM.checkServerTrusted(chain, authType);
                            }
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return finalDefaultTM.getAcceptedIssuers();
                        }
                    };

            // set up HTTPS context
            sslCtx.init(null, new TrustManager[] {mergedTrustManager}, null);
            return sslCtx;
        } catch (NoSuchAlgorithmException
                | KeyManagementException
                | KeyStoreException
                | CertificateException
                | IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Provides
    @Singleton
    @Named(HTTP_SERVER_SSL_CTX)
    public static Optional<SSLContext> provideServerSslContext(
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_VERSION) String serverTlsVersion,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_PASS)
                    Optional<String> keyStorePassFile,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_PASS_CHARSET)
                    String passFileCharset,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_FILE)
                    Optional<String> keyStoreFilePath,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_TYPE) String keyStoreType,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_ALIAS) String certAlias,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_FILE)
                    Optional<String> certFilePath,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_TYPE) String certType) {
        boolean ssl =
                keyStorePassFile.isPresent()
                        && keyStoreFilePath.isPresent()
                        && certFilePath.isPresent();
        if (!ssl) {
            if (keyStorePassFile.isPresent()
                    || keyStoreFilePath.isPresent()
                    || certFilePath.isPresent()) {
                throw new IllegalArgumentException(
                        "The file paths for the keystore, keystore password, and certificate must"
                            + " ALL be provided to set up HTTPS connections. Otherwise, make sure"
                            + " they are all unset to use an HTTP server.");
            }
            return Optional.empty();
        }

        try (InputStream pass = new FileInputStream(keyStorePassFile.get());
                InputStream keystore = new FileInputStream(keyStoreFilePath.get());
                InputStream certFile = new FileInputStream(certFilePath.get())) {
            SSLContext sslContext = SSLContext.getInstance(serverTlsVersion);

            // initialize keystore
            String password = IOUtils.toString(pass, Charset.forName(passFileCharset));
            password = password.substring(0, password.length() - 1);
            KeyStore ks = KeyStore.getInstance(keyStoreType);
            ks.load(keystore, password.toCharArray());

            // set up certificate factory
            CertificateFactory cf = CertificateFactory.getInstance(certType);
            Certificate cert = cf.generateCertificate(certFile);
            if (ks.containsAlias(certAlias)) {
                throw new IllegalStateException(
                        String.format(
                                "%s keystore at %s already contains a certificate with alias"
                                        + " \"%s\"",
                                keyStoreType, keyStoreFilePath, certAlias));
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
            AuthorizationType authorizationType,
            @Named(HTTP_CLIENT_SSL_CTX) SSLContext sslContext,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_VERIFY_HOSTNAME)
                    boolean verifyHostname,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_CONNECT_TIMEOUT_MS) int connectTimeout,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_TIMEOUT_MS) int responseTimeout,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_RETRY_COUNT) int retryCount) {
        HttpClientBuilder builder =
                HttpClients.custom()
                        .setSSLContext(sslContext)
                        .setDefaultRequestConfig(
                                RequestConfig.custom()
                                        .setAuthenticationEnabled(true)
                                        .setExpectContinueEnabled(true)
                                        .setConnectTimeout(connectTimeout)
                                        .setSocketTimeout(responseTimeout)
                                        .setRedirectsEnabled(true)
                                        .build())
                        .setRetryHandler(
                                new StandardHttpRequestRetryHandler(retryCount, true) {
                                    @Override
                                    public boolean retryRequest(
                                            IOException exception,
                                            int executionCount,
                                            HttpContext context) {
                                        // if the Authorization header we should send may change
                                        // over time, ex. we read a Bearer token from a file, then
                                        // it is possible that we get a 401 or 403 response because
                                        // the token expired in between the time that we read it
                                        // from our filesystem and when it was received by the
                                        // authenticator. So, in this set of conditions, we should
                                        // refresh our header value and try again right away
                                        if (authorizationType.isDynamic()) {
                                            HttpClientContext clientCtx =
                                                    HttpClientContext.adapt(context);
                                            if (clientCtx.isRequestSent()) {
                                                HttpResponse resp = clientCtx.getResponse();
                                                if (resp != null && resp.getStatusLine() != null) {
                                                    int sc = resp.getStatusLine().getStatusCode();
                                                    if (executionCount < 2
                                                            && (sc == 401 || sc == 403)) {
                                                        return true;
                                                    }
                                                }
                                            }
                                        }
                                        return super.retryRequest(
                                                exception, executionCount, context);
                                    }
                                });

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
            } else {
                http = HttpsServer.create(new InetSocketAddress(host, port), 0);
                ((HttpsServer) http)
                        .setHttpsConfigurator(
                                new HttpsConfigurator(sslContext.get()) {
                                    public void configure(HttpsParameters params) {
                                        try {
                                            SSLContext context = getSSLContext();
                                            SSLEngine engine = context.createSSLEngine();
                                            params.setNeedClientAuth(false);
                                            params.setCipherSuites(engine.getEnabledCipherSuites());
                                            params.setProtocols((engine.getEnabledProtocols()));
                                            params.setSSLParameters(
                                                    context.getDefaultSSLParameters());
                                        } catch (Exception e) {
                                            throw new RuntimeException(e);
                                        }
                                    }
                                });
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
            @Named(ConfigModule.CRYOSTAT_AGENT_AUTHORIZATION)
                    Supplier<Optional<String>> authorizationSupplier,
            @Named(ConfigModule.CRYOSTAT_AGENT_INSTANCE_ID) String instanceId,
            @Named(JVM_ID) String jvmId,
            @Named(ConfigModule.CRYOSTAT_AGENT_APP_NAME) String appName,
            @Named(ConfigModule.CRYOSTAT_AGENT_BASEURI) URI baseUri,
            @Named(ConfigModule.CRYOSTAT_AGENT_REALM) String realm) {
        return new CryostatClient(
                executor,
                objectMapper,
                http,
                authorizationSupplier,
                instanceId,
                jvmId,
                appName,
                baseUri,
                realm);
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
                                                    "[{}] Uncaught exception: {}",
                                                    thread.getName(),
                                                    ExceptionUtils.getStackTrace(err)));
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
    @Named(JVM_ID)
    public static String provideJvmId() {
        try {
            return JvmIdentifier.getLocal().getHash();
        } catch (IDException e) {
            throw new RuntimeException(e);
        }
    }
}
