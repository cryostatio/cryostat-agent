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

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import io.cryostat.agent.ConfigModule.BytePass;
import io.cryostat.agent.ConfigModule.CallbackCandidate;
import io.cryostat.agent.harvest.HarvestModule;
import io.cryostat.agent.remote.RemoteContext;
import io.cryostat.agent.remote.RemoteModule;
import io.cryostat.agent.triggers.TriggerModule;
import io.cryostat.libcryostat.JvmIdentifier;
import io.cryostat.libcryostat.net.IDException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.socket.ConnectionSocketFactory;
import org.apache.hc.client5.http.socket.PlainConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.ConnectionClosedException;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;
import org.projectnessie.cel.tools.ScriptHost;
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
    public static final String CRYOSTAT_AGENT_FLEET_SAMPLE_VALUE =
            "CRYOSTAT_AGENT_FLEET_SAMPLE_VALUE";

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
    public static ScriptHost provideScriptHost() {
        return ScriptHost.newBuilder().build();
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
            Lazy<Registration> registration) {
        return new WebServer(
                remoteContexts, cryostat, http, digest, user, passLength, registration);
    }

    private static Optional<CharBuffer> readPass(
            Optional<String> pass, Optional<String> passFile, String passFileCharset)
            throws IOException {
        CharBuffer cb = null;
        if (passFile.isPresent()) {
            byte[] bytes = null;
            try {
                bytes = Files.readAllBytes(Path.of(passFile.get()));
                cb = Charset.forName(passFileCharset).decode(ByteBuffer.wrap(bytes));
            } finally {
                if (bytes != null) {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
        } else if (pass.isPresent()) {
            cb = CharBuffer.wrap(pass.get().toCharArray());
        }
        return Optional.ofNullable(cb);
    }

    private static void clearBuffer(Optional<CharBuffer> cb) {
        if (cb == null) {
            return;
        }
        cb.ifPresent(c -> Arrays.fill(c.array(), '\0'));
        cb.ifPresent(CharBuffer::clear);
    }

    private static byte[] buildPkcs8KeyFromPkcs1Key(byte[] innerKey) {
        var result = new byte[innerKey.length + 26];
        System.arraycopy(
                Base64.getDecoder().decode("MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKY="),
                0,
                result,
                0,
                26);
        System.arraycopy(BigInteger.valueOf(result.length - 4).toByteArray(), 0, result, 2, 2);
        System.arraycopy(BigInteger.valueOf(innerKey.length).toByteArray(), 0, result, 24, 2);
        System.arraycopy(innerKey, 0, result, 26, innerKey.length);
        return result;
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
                    List<TruststoreConfig> truststoreCerts,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_CERT_PATH)
                    Optional<String> clientAuthCertPath,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_CERT_TYPE)
                    String clientAuthCertType,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_CERT_ALIAS)
                    String clientAuthCertAlias,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PATH)
                    Optional<String> clientAuthKeyPath,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_TYPE)
                    String clientAuthKeyType,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_CHARSET)
                    String clientAuthKeyCharset,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_ENCODING)
                    String clientAuthKeyEncoding,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_TYPE)
                    String clientAuthKeystoreType,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_PASS)
                    Optional<String> clientAuthKeystorePass,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_PASS_FILE)
                    Optional<String> clientAuthKeystorePassFile,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_PASS_CHARSET)
                    String clientAuthKeystorePassFileCharset,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PASS)
                    Optional<String> clientAuthKeyPass,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PASS_FILE)
                    Optional<String> clientAuthKeyPassFile,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PASS_CHARSET)
                    String clientAuthKeyPassFileCharset,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_MANAGER_TYPE)
                    String clientAuthKeyManagerType,
            @Named(ConfigModule.CRYOSTAT_AGENT_BASEURI) URI baseUri,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_REQUIRED) boolean tlsRequired) {
        try {
            KeyManager[] keyManagers = null;
            if (tlsRequired && !baseUri.getScheme().equals("https")) {
                throw new IllegalArgumentException(
                        String.format(
                                "If TLS is enabled via the (%s) property, the base URI (%s)"
                                        + " must be an https connection.",
                                ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_REQUIRED,
                                ConfigModule.CRYOSTAT_AGENT_BASEURI));
            }
            if (clientAuthCertPath.isPresent() && clientAuthKeyPath.isPresent()) {
                KeyStore ks = KeyStore.getInstance(clientAuthKeystoreType);
                Optional<CharBuffer> keystorePass =
                        readPass(
                                clientAuthKeystorePass,
                                clientAuthKeystorePassFile,
                                clientAuthKeystorePassFileCharset);
                Optional<CharBuffer> keyPass =
                        readPass(
                                clientAuthKeyPass,
                                clientAuthKeyPassFile,
                                clientAuthKeyPassFileCharset);
                byte[] keyBytes = new byte[0];
                try (BufferedInputStream certIs =
                                new BufferedInputStream(
                                        new FileInputStream(
                                                Path.of(clientAuthCertPath.get()).toFile()));
                        BufferedInputStream keyIs =
                                new BufferedInputStream(
                                        new FileInputStream(
                                                Path.of(clientAuthKeyPath.get()).toFile()))) {
                    ks.load(null, keystorePass.map(CharBuffer::array).orElse(null));
                    CertificateFactory certFactory =
                            CertificateFactory.getInstance(clientAuthCertType);
                    Certificate[] certChain =
                            certFactory.generateCertificates(certIs).toArray(new Certificate[0]);
                    KeyFactory keyFactory = KeyFactory.getInstance(clientAuthKeyType);
                    KeySpec keySpec;
                    // FIXME avoid allocating a String that holds the encoded key. This
                    // String may sit around on the heap until the JVM decides to do a GC
                    // and release it. It would be better to handle this using mutable
                    // structures (ex. byte[] or char[]) so that it can be explicitly
                    // cleared ASAP, or else use off-heap memory.
                    String s =
                            new String(keyIs.readAllBytes(), Charset.forName(clientAuthKeyCharset));
                    String pem = s.replaceAll("-----.+KEY-----", "").replaceAll("\\s+", "");
                    switch (clientAuthKeyEncoding) {
                        case "PKCS1":
                            keyBytes = buildPkcs8KeyFromPkcs1Key(Base64.getDecoder().decode(pem));
                            keySpec = new PKCS8EncodedKeySpec(keyBytes, clientAuthKeyType);
                            break;
                        case "PKCS8":
                            keyBytes = Base64.getDecoder().decode(pem);
                            keySpec = new PKCS8EncodedKeySpec(keyBytes, clientAuthKeyType);
                            break;
                        default:
                            throw new IllegalArgumentException(
                                    "Unimplemented key encoding: " + clientAuthKeyEncoding);
                    }
                    PrivateKey key = keyFactory.generatePrivate(keySpec);
                    ks.setKeyEntry(
                            clientAuthCertAlias,
                            key,
                            keyPass.map(CharBuffer::array).orElse(null),
                            certChain);
                    KeyManagerFactory kmf = KeyManagerFactory.getInstance(clientAuthKeyManagerType);
                    kmf.init(ks, keystorePass.map(CharBuffer::array).orElse(null));
                    keyManagers = kmf.getKeyManagers();
                } finally {
                    Arrays.fill(keyBytes, (byte) 0);
                    clearBuffer(keystorePass);
                    clearBuffer(keyPass);
                }
            } else if (clientAuthCertPath.isPresent() || clientAuthKeyPath.isPresent()) {
                throw new IllegalArgumentException(
                        String.format(
                                "To use TLS client authentication, both the certificate (%s) and"
                                        + " private key (%s) properties must be set.",
                                ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_CERT_PATH,
                                ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PATH));
            }

            X509TrustManager trustManager = null;
            if (trustAll) {
                trustManager =
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
                        };
            } else {
                // set up trust manager factory
                TrustManagerFactory tmf =
                        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init((KeyStore) null);

                X509TrustManager defaultTrustManager = null;
                X509TrustManager customTrustManager = null;
                for (TrustManager tm : tmf.getTrustManagers()) {
                    if (tm instanceof X509TrustManager) {
                        defaultTrustManager = (X509TrustManager) tm;
                        break;
                    }
                }
                KeyStore ts = KeyStore.getInstance(truststoreType);
                ts.load(null, null);
                // initialize truststore with user provided path and pass
                if (truststorePath.isPresent() && truststorePass.isPresent()) {
                    Charset charset = Charset.forName(passCharset);
                    CharsetDecoder decoder = charset.newDecoder();
                    ByteBuffer byteBuffer = ByteBuffer.wrap(truststorePass.get().get());
                    CharBuffer charBuffer = decoder.decode(byteBuffer);
                    try (InputStream truststore = new FileInputStream(truststorePath.get())) {
                        ts.load(truststore, charBuffer.array());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    } finally {
                        Arrays.fill(byteBuffer.array(), (byte) 0);
                        Arrays.fill(charBuffer.array(), '\0');
                        truststorePass.get().clear();
                    }
                } else if (truststorePath.isPresent() || truststorePass.isPresent()) {
                    throw new IllegalArgumentException(
                            String.format(
                                    "To import a truststore, provide both the path to the"
                                        + " truststore (%s) and the pass (%s), or a path to a file"
                                        + " containing the pass (%s)",
                                    ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PATH,
                                    ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PASS,
                                    ConfigModule
                                            .CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PASS_FILE));
                }

                // initialize truststore with user provided certs
                for (TruststoreConfig truststore : truststoreCerts) {
                    // load truststore with certificatesCertificate
                    try (InputStream certFile = new FileInputStream(truststore.getPath())) {
                        CertificateFactory cf =
                                CertificateFactory.getInstance(truststore.getType());
                        Certificate cert = cf.generateCertificate(certFile);
                        if (ts.containsAlias(truststore.getType())) {
                            throw new IllegalStateException(
                                    String.format(
                                            "truststore already contains a certificate with"
                                                    + " alias \"%s\"",
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

                customTrustManager = null;
                for (TrustManager tm : tmf.getTrustManagers()) {
                    if (tm instanceof X509TrustManager) {
                        customTrustManager = (X509TrustManager) tm;
                        break;
                    }
                }

                final X509TrustManager finalDefaultTM = defaultTrustManager;
                final X509TrustManager finalCustomTM = customTrustManager;
                trustManager =
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
            }

            SSLContext sslCtx = SSLContext.getInstance(clientTlsVersion);
            sslCtx.init(keyManagers, new X509TrustManager[] {trustManager}, null);
            return sslCtx;
        } catch (NoSuchAlgorithmException
                | KeyManagementException
                | InvalidKeySpecException
                | UnrecoverableKeyException
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
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_ALIAS) String keyAlias,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PATH) Optional<String> keyFilePath,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PASS) Optional<String> keyPass,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PASS_FILE)
                    Optional<String> keyPassFile,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PASS_CHARSET)
                    String keyPassCharset,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PATH) Optional<String> keyPath,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_ENCODING) String keyEncoding,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_CHARSET) String keyCharset,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_TYPE) String keyType,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_TYPE) String keyStoreType,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_ALIAS) String certAlias,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_FILE)
                    Optional<String> certFilePath,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_TYPE) String certType) {
        boolean ssl =
                (keyStoreFilePath.isPresent() || keyFilePath.isPresent())
                        && certFilePath.isPresent();
        if (!ssl) {
            if (keyFilePath.isPresent()
                    || keyStoreFilePath.isPresent()
                    || certFilePath.isPresent()) {
                throw new IllegalArgumentException(
                        "The file paths for the keystore or key file, and certificate must ALL be"
                            + " provided to set up HTTPS connections. Otherwise, make sure they are"
                            + " all unset to use an HTTP server.");
            }
            return Optional.empty();
        }

        InputStream keystore = null;
        InputStream pass = null;
        try (InputStream certFile = new FileInputStream(certFilePath.get())) {
            SSLContext sslContext = SSLContext.getInstance(serverTlsVersion);
            if (keyStoreFilePath.isPresent()) {
                keystore = new FileInputStream(keyStoreFilePath.get());
            }
            char[] storePass = null;
            if (keyStorePassFile.isPresent()) {
                pass = new FileInputStream(keyStorePassFile.get());
                String password = IOUtils.toString(pass, Charset.forName(passFileCharset));
                password = password.substring(0, password.length() - 1);
                storePass = password.toCharArray();
            }

            // initialize keystore
            KeyStore ks = KeyStore.getInstance(keyStoreType);
            ks.load(keystore, storePass);

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

            if (keyFilePath.isPresent()) {
                Optional<CharBuffer> kp = readPass(keyPass, keyPassFile, keyPassCharset);
                byte[] keyBytes = new byte[0];
                try (BufferedInputStream keyIs =
                        new BufferedInputStream(
                                new FileInputStream(Path.of(keyPath.get()).toFile()))) {
                    KeyFactory keyFactory = KeyFactory.getInstance(keyType);
                    KeySpec keySpec;
                    // FIXME avoid allocating a String that holds the encoded key. This
                    // String may sit around on the heap until the JVM decides to do a GC
                    // and release it. It would be better to handle this using mutable
                    // structures (ex. byte[] or char[]) so that it can be explicitly
                    // cleared ASAP, or else use off-heap memory.
                    String s = new String(keyIs.readAllBytes(), Charset.forName(keyCharset));
                    String pem = s.replaceAll("-----.+KEY-----", "").replaceAll("\\s+", "");
                    switch (keyEncoding) {
                        case "PKCS1":
                            keyBytes = buildPkcs8KeyFromPkcs1Key(Base64.getDecoder().decode(pem));
                            keySpec = new PKCS8EncodedKeySpec(keyBytes, keyType);
                            break;
                        case "PKCS8":
                            keyBytes = Base64.getDecoder().decode(pem);
                            keySpec = new PKCS8EncodedKeySpec(keyBytes, keyType);
                            break;
                        default:
                            throw new IllegalArgumentException(
                                    "Unimplemented key encoding: " + keyType);
                    }
                    PrivateKey key = keyFactory.generatePrivate(keySpec);
                    ks.setKeyEntry(
                            keyAlias,
                            key,
                            kp.map(CharBuffer::array).orElse(null),
                            new Certificate[] {cert});
                } finally {
                    Arrays.fill(keyBytes, (byte) 0);
                    clearBuffer(kp);
                }
            }

            // set up key manager factory
            KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, storePass);

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
                | InvalidKeySpecException
                | KeyManagementException
                | IOException
                | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        } finally {
            if (keystore != null) {
                try {
                    keystore.close();
                } catch (IOException ioe) {
                    throw new RuntimeException(ioe);
                }
            }
            if (pass != null) {
                try {
                    pass.close();
                } catch (IOException ioe) {
                    throw new RuntimeException(ioe);
                }
            }
        }
    }

    @Provides
    @Singleton
    public static HttpClient provideHttpClient(
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_HTTP_USE_PREEMPTIVE_AUTHENTICATION)
                    boolean preemptiveAuth,
            AuthorizationType authorizationType,
            @Named(ConfigModule.CRYOSTAT_AGENT_AUTHORIZATION)
                    Supplier<Optional<String>> authorizationSupplier,
            @Named(HTTP_CLIENT_SSL_CTX) SSLContext sslContext,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_VERIFY_HOSTNAME)
                    boolean verifyHostname,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_CONNECT_TIMEOUT_MS) int connectTimeout,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_TIMEOUT_MS) int responseTimeout,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_RETRY_COUNT) int retryCount,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_RETRY_TIME) int retryTime,
            @Named(ConfigModule.CRYOSTAT_AGENT_WEBCLIENT_TLS_REQUIRED) boolean tlsRequired) {
        SSLConnectionSocketFactory sslSocketFactory =
                new SSLConnectionSocketFactory(
                        sslContext,
                        verifyHostname
                                ? new DefaultHostnameVerifier()
                                : NoopHostnameVerifier.INSTANCE);

        RegistryBuilder<ConnectionSocketFactory> socketFactoryRegistryBuilder =
                RegistryBuilder.<ConnectionSocketFactory>create()
                        .register("https", sslSocketFactory);
        if (!tlsRequired) {
            socketFactoryRegistryBuilder.register("http", new PlainConnectionSocketFactory());
        }
        HttpClientConnectionManager connMan =
                new BasicHttpClientConnectionManager(socketFactoryRegistryBuilder.build());
        HttpClientBuilder builder =
                HttpClients.custom()
                        .setConnectionManager(connMan)
                        .setDefaultRequestConfig(
                                RequestConfig.custom()
                                        .setAuthenticationEnabled(!preemptiveAuth)
                                        .setExpectContinueEnabled(true)
                                        .setConnectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                                        .setResponseTimeout(responseTimeout, TimeUnit.MILLISECONDS)
                                        .setRedirectsEnabled(true)
                                        .build())
                        .setRetryStrategy(
                                new AgentHttpRetryStrategy(
                                        authorizationType,
                                        retryTime,
                                        TimeValue.ofSeconds(retryTime)));
        if (preemptiveAuth) {
            builder =
                    builder.addRequestInterceptorLast(
                            new AgentPreemptiveAuthInterceptor(authorizationSupplier));
        }
        return builder.build();
    }

    private static class AgentPreemptiveAuthInterceptor implements HttpRequestInterceptor {
        private final Supplier<Optional<String>> authorizationSupplier;

        private AgentPreemptiveAuthInterceptor(Supplier<Optional<String>> authorizationSupplier) {
            this.authorizationSupplier = authorizationSupplier;
        }

        @Override
        public void process(HttpRequest request, EntityDetails entity, HttpContext context)
                throws HttpException, IOException {
            authorizationSupplier
                    .get()
                    .ifPresent(c -> request.setHeader(HttpHeaders.AUTHORIZATION, c));
        }
    }

    private static class AgentHttpRetryStrategy extends DefaultHttpRequestRetryStrategy {
        AgentHttpRetryStrategy(
                AuthorizationType authorizationType,
                int maxRetries,
                TimeValue defaultRetryInterval) {
            super(
                    maxRetries,
                    defaultRetryInterval,
                    Arrays.asList(
                            InterruptedIOException.class,
                            UnknownHostException.class,
                            ConnectException.class,
                            ConnectionClosedException.class,
                            NoRouteToHostException.class,
                            SSLException.class),
                    getRetriableCodes(authorizationType));
        }

        private static Set<Integer> getRetriableCodes(AuthorizationType authorizationType) {
            Set<Integer> codes = new HashSet<>();
            codes.addAll(
                    Set.of(HttpStatus.SC_TOO_MANY_REQUESTS, HttpStatus.SC_SERVICE_UNAVAILABLE));
            if (authorizationType.isDynamic()) {
                // if the Authorization header we should send may change over time, ex. we read a
                // Bearer token from a file, then it is possible that we get a 401 or 403 response
                // because the token expired in between the time that we read it from our filesystem
                // and when it was processed by the authenticator. So, in this set of conditions, we
                // should refresh our header value and try again
                codes.addAll(Set.of(HttpStatus.SC_UNAUTHORIZED, HttpStatus.SC_FORBIDDEN));
            }
            return codes;
        }
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
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .registerModule(new JavaTimeModule());
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
            @Named(ConfigModule.CRYOSTAT_AGENT_REALM) String realm,
            @Named(ConfigModule.CRYOSTAT_AGENT_PUBLISH_FILL_STRATEGY) String fillAlgorithm,
            @Named(ConfigModule.CRYOSTAT_AGENT_PUBLISH_CONTEXT)
                    Map<String, String> publishContext) {
        return new CryostatClient(
                executor,
                objectMapper,
                http,
                instanceId,
                jvmId,
                appName,
                baseUri,
                realm,
                new CryostatClient.DiscoveryPublication(fillAlgorithm, publishContext));
    }

    @Provides
    @Singleton
    public static CallbackResolver provideCallbackResolver(
            ScriptHost scriptHost,
            @Named(ConfigModule.CRYOSTAT_AGENT_CALLBACK_CANDIDATES)
                    List<CallbackCandidate> candidates) {
        return new CallbackResolver(scriptHost, candidates);
    }

    @Provides
    @Singleton
    public static Registration provideRegistration(
            ScheduledExecutorService executor,
            CryostatClient cryostat,
            CallbackResolver callbackResolver,
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
                callbackResolver,
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
    public static String provideJvmId(@Named(ConfigModule.CRYOSTAT_AGENT_INSTANCE_ID) String id) {
        try {
            return JvmIdentifier.getLocal(id).getHash();
        } catch (IDException e) {
            throw new RuntimeException(e);
        }
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_FLEET_SAMPLE_VALUE)
    public static double provideCryostatAgentFleetSampleValue() {
        return Math.random();
    }
}
