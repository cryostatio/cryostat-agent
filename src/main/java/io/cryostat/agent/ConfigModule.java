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
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.security.AccessController;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.inject.Named;
import javax.inject.Singleton;

import io.cryostat.agent.util.ResourcesUtil;
import io.cryostat.agent.util.StringUtils;

import dagger.Module;
import dagger.Provides;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.client.utils.URIBuilder;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.projectnessie.cel.extension.StringsLib;
import org.projectnessie.cel.tools.Script;
import org.projectnessie.cel.tools.ScriptException;
import org.projectnessie.cel.tools.ScriptHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Module
public abstract class ConfigModule {

    private static final Logger log = LoggerFactory.getLogger(ConfigModule.class);

    public static final String CRYOSTAT_AGENT_INSTANCE_ID = "cryostat.agent.instance-id";
    public static final String CRYOSTAT_AGENT_BASEURI_RANGE = "cryostat.agent.baseuri-range";
    public static final String CRYOSTAT_AGENT_BASEURI = "cryostat.agent.baseuri";
    public static final String CRYOSTAT_AGENT_CALLBACK = "cryostat.agent.callback";
    public static final String CRYOSTAT_AGENT_REALM = "cryostat.agent.realm";
    public static final String CRYOSTAT_AGENT_AUTHORIZATION = "cryostat.agent.authorization";
    public static final String CRYOSTAT_AGENT_AUTHORIZATION_TYPE =
            "cryostat.agent.authorization.type";
    public static final String CRYOSTAT_AGENT_AUTHORIZATION_VALUE =
            "cryostat.agent.authorization.value";

    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_VERSION =
            "cryostat.agent.webclient.tls.version";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUST_ALL =
            "cryostat.agent.webclient.tls.trust-all";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_VERIFY_HOSTNAME =
            "cryostat.agent.webclient.tls.verify-hostname";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_CONNECT_TIMEOUT_MS =
            "cryostat.agent.webclient.connect.timeout-ms";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_TIMEOUT_MS =
            "cryostat.agent.webclient.response.timeout-ms";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PATH =
            "cryostat.agent.webclient.tls.truststore.path";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PASS_FILE =
            "cryostat.agent.webclient.tls.truststore.pass.file";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PASS_CHARSET =
            "cryostat.agent.webclient.tls.truststore.pass-charset";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PASS =
            "cryostat.agent.webclient.tls.truststore.pass";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_TYPE =
            "cryostat.agent.webclient.tls.truststore.type";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_CERTS =
            "cryostat.agent.webclient.tls.truststore.cert";
    public static final Pattern CRYOSTAT_AGENT_TRUSTSTORE_PATTERN =
            Pattern.compile(
                    "^(?:cryostat\\.agent\\.webclient\\.tls\\.truststore\\.cert).(?<index>\\d+).\\.(?<property>.*)$");
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_CERT_PATH =
            "cryostat.agent.webclient.tls.client-auth.cert.path";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_CERT_TYPE =
            "cryostat.agent.webclient.tls.client-auth.cert.type";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_CERT_ALIAS =
            "cryostat.agent.webclient.tls.client-auth.cert.alias";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PATH =
            "cryostat.agent.webclient.tls.client-auth.key.path";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_CHARSET =
            "cryostat.agent.webclient.tls.client-auth.key.charset";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_ENCODING =
            "cryostat.agent.webclient.tls.client-auth.key.encoding";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_TYPE =
            "cryostat.agent.webclient.tls.client-auth.key.type";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PASS_FILE =
            "cryostat.agent.webclient.tls.client-auth.key.pass.file";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PASS_CHARSET =
            "cryostat.agent.webclient.tls.client-auth.key.pass-charset";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PASS =
            "cryostat.agent.webclient.tls.client-auth.key.pass";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_PASS_FILE =
            "cryostat.agent.webclient.tls.client-auth.keystore.pass.file";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_PASS_CHARSET =
            "cryostat.agent.webclient.tls.client-auth.keystore.pass-charset";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_PASS =
            "cryostat.agent.webclient.tls.client-auth.keystore.pass";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_TYPE =
            "cryostat.agent.webclient.tls.client-auth.keystore.type";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_MANAGER_TYPE =
            "cryostat.agent.webclient.tls.client-auth.key-manager.type";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_RETRY_COUNT =
            "cryostat.agent.webclient.response.retry-count";

    public static final String CRYOSTAT_AGENT_WEBSERVER_HOST = "cryostat.agent.webserver.host";
    public static final String CRYOSTAT_AGENT_WEBSERVER_PORT = "cryostat.agent.webserver.port";
    public static final String CRYOSTAT_AGENT_WEBSERVER_TLS_VERSION =
            "cryostat.agent.webserver.tls.version";
    public static final String CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_PASS =
            "cryostat.agent.webserver.tls.keystore.pass";
    public static final String CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_PASS_CHARSET =
            "cryostat.agent.webserver.tls.keystore.pass-charset";
    public static final String CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_FILE =
            "cryostat.agent.webserver.tls.keystore.file";
    public static final String CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_TYPE =
            "cryostat.agent.webserver.tls.keystore.type";

    public static final String CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_ALIAS =
            "cryostat.agent.webserver.tls.key.alias";
    public static final String CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PATH =
            "cryostat.agent.webserver.tls.key.path";
    public static final String CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_CHARSET =
            "cryostat.agent.webserver.tls.key.charset";
    public static final String CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_ENCODING =
            "cryostat.agent.webserver.tls.key.encoding";
    public static final String CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_TYPE =
            "cryostat.agent.webserver.tls.key.type";
    public static final String CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PASS_FILE =
            "cryostat.agent.webserver.tls.key.pass.file";
    public static final String CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PASS_CHARSET =
            "cryostat.agent.webserver.tls.key.pass-charset";
    public static final String CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PASS =
            "cryostat.agent.webserver.tls.key.pass";

    public static final String CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_ALIAS =
            "cryostat.agent.webserver.tls.cert.alias";
    public static final String CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_FILE =
            "cryostat.agent.webserver.tls.cert.file";
    public static final String CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_TYPE =
            "cryostat.agent.webserver.tls.cert.type";
    public static final String CRYOSTAT_AGENT_WEBSERVER_CREDENTIALS_USER =
            "cryostat.agent.webserver.credentials.user";
    public static final String CRYOSTAT_AGENT_WEBSERVER_CREDENTIALS_PASS_HASH_FUNCTION =
            "cryostat.agent.webserver.credentials.pass.hash-function";
    public static final String CRYOSTAT_AGENT_WEBSERVER_CREDENTIALS_PASS_LENGTH =
            "cryostat.agent.webserver.credentials.pass.length";

    public static final String CRYOSTAT_AGENT_CONFIG_LOADABLE = "cryostat.agent.config.loadable";
    public static final String CRYOSTAT_AGENT_APP_NAME = "cryostat.agent.app.name";
    public static final String CRYOSTAT_AGENT_HOSTNAME = "cryostat.agent.hostname";
    public static final String CRYOSTAT_AGENT_APP_JMX_PORT = "cryostat.agent.app.jmx.port";
    public static final String CRYOSTAT_AGENT_REGISTRATION_RETRY_MS =
            "cryostat.agent.registration.retry-ms";
    public static final String CRYOSTAT_AGENT_REGISTRATION_CHECK_MS =
            "cryostat.agent.registration.check-ms";
    public static final String CRYOSTAT_AGENT_REGISTRATION_JMX_IGNORE =
            "cryostat.agent.registration.jmx.ignore";
    public static final String CRYOSTAT_AGENT_REGISTRATION_JMX_USE_CALLBACK_HOST =
            "cryostat.agent.registration.jmx.use-callback-host";
    public static final String CRYOSTAT_AGENT_EXIT_SIGNALS = "cryostat.agent.exit.signals";
    public static final String CRYOSTAT_AGENT_EXIT_DEREGISTRATION_TIMEOUT_MS =
            "cryostat.agent.exit.deregistration.timeout-ms";

    public static final String CRYOSTAT_AGENT_HARVESTER_PERIOD_MS =
            "cryostat.agent.harvester.period-ms";
    public static final String CRYOSTAT_AGENT_HARVESTER_TEMPLATE =
            "cryostat.agent.harvester.template";
    public static final String CRYOSTAT_AGENT_HARVESTER_MAX_FILES =
            "cryostat.agent.harvester.max-files";
    public static final String CRYOSTAT_AGENT_HARVESTER_UPLOAD_TIMEOUT_MS =
            "cryostat.agent.harvester.upload.timeout-ms";
    public static final String CRYOSTAT_AGENT_HARVESTER_EXIT_MAX_AGE_MS =
            "cryostat.agent.harvester.exit.max-age-ms";
    public static final String CRYOSTAT_AGENT_HARVESTER_EXIT_MAX_SIZE_B =
            "cryostat.agent.harvester.exit.max-size-b";
    public static final String CRYOSTAT_AGENT_HARVESTER_MAX_AGE_MS =
            "cryostat.agent.harvester.max-age-ms";
    public static final String CRYOSTAT_AGENT_HARVESTER_MAX_SIZE_B =
            "cryostat.agent.harvester.max-size-b";

    public static final String CRYOSTAT_AGENT_SMART_TRIGGER_DEFINITIONS =
            "cryostat.agent.smart-trigger.definitions";
    public static final String CRYOSTAT_AGENT_SMART_TRIGGER_EVALUATION_PERIOD_MS =
            "cryostat.agent.smart-trigger.evaluation.period-ms";

    public static final String CRYOSTAT_AGENT_CALLBACK_SCHEME = "cryostat.agent.callback.scheme";
    public static final String CRYOSTAT_AGENT_CALLBACK_HOST_NAME =
            "cryostat.agent.callback.host-name";
    public static final String CRYOSTAT_AGENT_CALLBACK_DOMAIN_NAME =
            "cryostat.agent.callback.domain-name";
    public static final String CRYOSTAT_AGENT_CALLBACK_PORT = "cryostat.agent.callback.port";

    public static final String CRYOSTAT_AGENT_API_WRITES_ENABLED =
            "cryostat.agent.api.writes-enabled";

    private static final String HOST_SCRIPT_PATTERN_STRING =
            "(?<host>[A-Za-z0-9-.]+)(?:\\[(?<script>.+)\\])?";
    private static final Pattern HOST_SCRIPT_PATTERN = Pattern.compile(HOST_SCRIPT_PATTERN_STRING);

    @Provides
    @Singleton
    public static Config provideConfig() {
        List<Pair<String, Callable<Config>>> fns = new ArrayList<>();
        fns.add(Pair.of("Simple", ConfigProvider::getConfig));
        Function<Callable<ClassLoader>, Callable<Config>> clConfigLoader =
                cl ->
                        () -> {
                            ClassLoader loader;
                            try {
                                loader =
                                        AccessController.doPrivileged(
                                                (PrivilegedExceptionAction<ClassLoader>)
                                                        () -> cl.call());
                            } catch (Exception e) {
                                log.warn(
                                        "ClassLoader AccessController failure - is this JVM too new"
                                            + " to have the AccessController and SecurityManager?",
                                        e);
                                loader = cl.call();
                            }
                            return ConfigProvider.getConfig(loader);
                        };
        fns.add(
                Pair.of(
                        "ResourcesUtil ClassLoader",
                        clConfigLoader.apply(ResourcesUtil::getClassLoader)));
        fns.add(
                Pair.of(
                        "Config Class ClassLoader",
                        clConfigLoader.apply(Config.class::getClassLoader)));
        fns.add(
                Pair.of(
                        "Agent JAR URL ClassLoader",
                        clConfigLoader.apply(
                                () ->
                                        new URLClassLoader(
                                                new URL[] {Agent.selfJarLocation().toURL()}))));

        Config config = null;
        for (Pair<String, Callable<Config>> fn : fns) {
            try {
                log.trace(
                        "Testing classloader \"{}\" for {} property",
                        fn.getLeft(),
                        CRYOSTAT_AGENT_CONFIG_LOADABLE);
                Config candidate = fn.getRight().call();
                if (!candidate.getValue(CRYOSTAT_AGENT_CONFIG_LOADABLE, boolean.class)) {
                    log.warn(
                            "{} was false. Assuming that this means the {} classloader"
                                    + " cannot be used to load properties. Do not override this"
                                    + " configuration property with a blank or false value!",
                            CRYOSTAT_AGENT_CONFIG_LOADABLE,
                            fn.getLeft());
                    continue;
                }
                config = candidate;
                break;
            } catch (Exception e) {
                config = null;
                log.debug(
                        String.format("Failed to load config from \"%s\" supplier", fn.getLeft()),
                        e);
            }
        }
        if (config == null || !config.getValue(CRYOSTAT_AGENT_CONFIG_LOADABLE, boolean.class)) {
            log.error("Unable to load configuration from any classloader source!");
        }
        return config;
    }

    @Provides
    @Named(CRYOSTAT_AGENT_BASEURI_RANGE)
    public static URIRange provideUriRange(Config config) {
        return URIRange.fromString(config.getValue(CRYOSTAT_AGENT_BASEURI_RANGE, String.class));
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_BASEURI)
    public static URI provideCryostatAgentBaseUri(Config config) {
        return config.getValue(CRYOSTAT_AGENT_BASEURI, URI.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_CALLBACK)
    public static URI provideCryostatAgentCallback(Config config) {
        Optional<URI> callback = buildCallbackFromComponents(config);
        if (callback.isPresent()) {
            return callback.get();
        }
        return config.getValue(CRYOSTAT_AGENT_CALLBACK, URI.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_REALM)
    public static String provideCryostatAgentRealm(
            Config config, @Named(CRYOSTAT_AGENT_APP_NAME) String appName) {
        return config.getOptionalValue(CRYOSTAT_AGENT_REALM, String.class).orElse(appName);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_AUTHORIZATION)
    public static Supplier<Optional<String>> provideCryostatAgentAuthorization(
            Config config,
            AuthorizationType authorizationType,
            @Named(CRYOSTAT_AGENT_AUTHORIZATION_VALUE) Optional<String> authorizationValue) {
        Optional<String> opt = config.getOptionalValue(CRYOSTAT_AGENT_AUTHORIZATION, String.class);
        return () -> opt.or(() -> authorizationValue.map(authorizationType::apply));
    }

    @Provides
    @Singleton
    public static AuthorizationType provideCryostatAgentAuthorizationType(Config config) {
        return AuthorizationType.fromString(
                config.getValue(CRYOSTAT_AGENT_AUTHORIZATION_TYPE, String.class));
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_AUTHORIZATION_VALUE)
    public static Optional<String> provideCryostatAgentAuthorizationValue(Config config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_AUTHORIZATION_VALUE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUST_ALL)
    public static boolean provideCryostatAgentWebclientTrustAll(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUST_ALL, boolean.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_VERIFY_HOSTNAME)
    public static boolean provideCryostatAgentWebclientVerifyHostname(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_VERIFY_HOSTNAME, boolean.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_CONNECT_TIMEOUT_MS)
    public static int provideCryostatAgentWebclientConnectTimeoutMs(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_CONNECT_TIMEOUT_MS, int.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_TIMEOUT_MS)
    public static int provideCryostatAgentWebclientResponseTimeoutMs(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_TIMEOUT_MS, int.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PATH)
    public static Optional<String> provideCryostatAgentWebclientTlsTruststorePath(Config config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PATH, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PASS_FILE)
    public static Optional<BytePass> provideCryostatAgentWebclientTlsTruststorePassFromFile(
            Config config) {
        Optional<String> truststorePassFile =
                config.getOptionalValue(
                        CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PASS_FILE, String.class);
        if (truststorePassFile.isEmpty()) {
            return Optional.empty();
        }
        try (FileInputStream passFile = new FileInputStream(truststorePassFile.get())) {
            byte[] pass = passFile.readAllBytes();
            Optional<BytePass> bytePass = Optional.of(new BytePass(pass));
            Arrays.fill(pass, (byte) 0);
            return bytePass;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PASS_CHARSET)
    public static String provideCryostatAgentWebclientTlsTruststorePassCharset(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PASS_CHARSET, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PASS)
    public static Optional<BytePass> provideCryostatAgentWebclientTlsTruststorePass(
            Config config,
            @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PASS_FILE)
                    Optional<BytePass> truststorePass,
            @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PASS_CHARSET) String passCharset) {
        Optional<String> opt =
                config.getOptionalValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PASS, String.class);
        if (opt.isEmpty()) {
            return truststorePass;
        }
        return Optional.of(new BytePass(opt.get(), passCharset));
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_TYPE)
    public static String provideCryostatAgentWebclientTlsTruststoreType(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_TYPE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_CERTS)
    public static List<TruststoreConfig> provideCryostatAgentWecblientTlsTruststoreCerts(
            Config config,
            @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PASS) Optional<BytePass> truststorePass,
            @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PATH) Optional<String> truststorePath) {
        Map<Integer, TruststoreConfig.Builder> truststoreBuilders = new HashMap<>();
        List<TruststoreConfig> truststoreConfigs = new ArrayList<>();

        if (!truststorePass.isEmpty() || !truststorePath.isEmpty()) {
            return truststoreConfigs;
        }

        StreamSupport.stream(config.getPropertyNames().spliterator(), false)
                .filter(e -> e.startsWith(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_CERTS))
                .forEach(
                        name -> {
                            Matcher matcher = CRYOSTAT_AGENT_TRUSTSTORE_PATTERN.matcher(name);
                            if (!matcher.matches()) {
                                throw new IllegalArgumentException(
                                        String.format(
                                                "Invalid truststore config property name format:"
                                                        + " \"%s\". Make sure the config property"
                                                        + " matches the following pattern:"
                                                        + " '"
                                                        + CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_CERTS
                                                        + "[CERT_NUMBER].CERT_PROPERTY'",
                                                name));
                            }
                            int truststoreNumber = Integer.parseInt(matcher.group("index"));
                            String configProp = matcher.group("property");

                            TruststoreConfig.Builder truststoreBuilder =
                                    truststoreBuilders.computeIfAbsent(
                                            truststoreNumber, k -> new TruststoreConfig.Builder());

                            String value = config.getValue(name, String.class);
                            switch (configProp) {
                                case "alias":
                                    truststoreBuilder = truststoreBuilder.withAlias(value);
                                    break;
                                case "path":
                                    truststoreBuilder = truststoreBuilder.withPath(value);
                                    break;
                                case "type":
                                    truststoreBuilder = truststoreBuilder.withType(value);
                                    break;
                                default:
                                    throw new IllegalArgumentException(
                                            String.format(
                                                    "Truststore config only includes alias, path,"
                                                        + " and type. Rename this config property:"
                                                        + " %s",
                                                    name));
                            }
                        });

        for (TruststoreConfig.Builder builder : truststoreBuilders.values()) {
            try {
                truststoreConfigs.add(builder.build());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return truststoreConfigs;
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_CERT_PATH)
    public static Optional<String> provideCryostatAgentWebclientTlsClientAuthCertPath(
            Config config) {
        return config.getOptionalValue(
                CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_CERT_PATH, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_CERT_TYPE)
    public static String provideCryostatAgentWebclientTlsClientAuthCertType(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_CERT_TYPE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_CERT_ALIAS)
    public static String provideCryostatAgentWebclientTlsClientAuthCertAlias(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_CERT_ALIAS, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PATH)
    public static Optional<String> provideCryostatAgentWebclientTlsClientAuthKeyPath(
            Config config) {
        return config.getOptionalValue(
                CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PATH, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_TYPE)
    public static String provideCryostatAgentWebclientTlsClientAuthKeyType(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_TYPE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_CHARSET)
    public static String provideCryostatAgentWebclientTlsClientAuthKeyCharset(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_CHARSET, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_ENCODING)
    public static String provideCryostatAgentWebclientTlsClientAuthKeyEncoding(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_ENCODING, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PASS_FILE)
    public static Optional<String> provideCryostatAgentWebclientTlsClientAuthKeyPassFile(
            Config config) {
        return config.getOptionalValue(
                CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PASS_FILE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PASS_CHARSET)
    public static String provideCryostatAgentWebclientTlsClientAuthKeyPassCharset(Config config) {
        return config.getValue(
                CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PASS_CHARSET, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PASS)
    public static Optional<String> provideCryostatAgentWebclientTlsClientAuthKeyPass(
            Config config) {
        return config.getOptionalValue(
                CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_PASS, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_PASS_FILE)
    public static Optional<String> provideCryostatAgentWebclientTlsClientAuthKeystorePassFile(
            Config config) {
        return config.getOptionalValue(
                CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_PASS_FILE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_PASS_CHARSET)
    public static String provideCryostatAgentWebclientTlsClientAuthKeystorePassCharset(
            Config config) {
        return config.getValue(
                CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_PASS_CHARSET, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_PASS)
    public static Optional<String> provideCryostatAgentWebclientTlsClientAuthKeystorePass(
            Config config) {
        return config.getOptionalValue(
                CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_PASS, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_TYPE)
    public static String provideCryostatAgentWebclientTlsClientAuthKeystoreType(Config config) {
        return config.getValue(
                CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_TYPE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_MANAGER_TYPE)
    public static String provideCryostatAgentWebclientTlsClientAuthKeyManagerType(Config config) {
        return config.getValue(
                CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_MANAGER_TYPE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_RETRY_COUNT)
    public static int provideCryostatAgentWebclientResponseRetryCount(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_RETRY_COUNT, int.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_HOST)
    public static String provideCryostatAgentWebserverHost(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_HOST, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_PORT)
    public static int provideCryostatAgentWebserverPort(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_PORT, int.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_VERSION)
    public static String provideCryostatAgentWebclientTlsVersion(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_VERSION, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_VERSION)
    public static String provideCryostatAgentWebserverTlsVersion(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_TLS_VERSION, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_PASS)
    public static Optional<String> provideCryostatAgentWebserverTlsKeyStorePass(Config config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_PASS, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_PASS_CHARSET)
    public static String provideCryostatAgentWebserverTlsKeyStorePassCharset(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_PASS_CHARSET, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_FILE)
    public static Optional<String> provideCryostatAgentWebserverTlsKeyStoreFile(Config config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_FILE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_TYPE)
    public static String provideCryostatAgentWebserverTlsKeyStoreType(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_TYPE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_ALIAS)
    public static String provideCryostatAgentWebserverTlsKeyAlias(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_ALIAS, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PATH)
    public static Optional<String> provideCryostatAgentWebserverTlsKeyPath(Config config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PATH, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_CHARSET)
    public static String provideCryostatAgentWebserverTlsKeyCharset(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_CHARSET, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_ENCODING)
    public static String provideCryostatAgentWebserverTlsKeyEncoding(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_ENCODING, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_TYPE)
    public static String provideCryostatAgentWebserverTlsKeyType(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_TYPE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PASS_FILE)
    public static Optional<String> provideCryostatAgentWebserverTlsKeyPassFile(Config config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PASS_FILE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PASS_CHARSET)
    public static String provideCryostatAgentWebserverTlsKeyPassCharset(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PASS_CHARSET, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PASS)
    public static Optional<String> provideCryostatAgentWebserverTlsKeyPass(Config config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PASS, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_ALIAS)
    public static String provideCryostatAgentWebserverTlsCertAlias(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_ALIAS, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_FILE)
    public static Optional<String> provideCryostatAgentWebserverTlsCertFile(Config config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_FILE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_TYPE)
    public static String provideCryostatAgentWebserverTlsCertType(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_TYPE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_CREDENTIALS_USER)
    public static String provideCryostatAgentWebserverCredentialsUser(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_CREDENTIALS_USER, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_CREDENTIALS_PASS_HASH_FUNCTION)
    public static MessageDigest provideCryostatAgentWebserverCredentialsPassHashFunction(
            Config config) {
        try {
            String id =
                    config.getValue(
                            CRYOSTAT_AGENT_WEBSERVER_CREDENTIALS_PASS_HASH_FUNCTION, String.class);
            return MessageDigest.getInstance(id);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_CREDENTIALS_PASS_LENGTH)
    public static int provideCryostatAgentWebserverCredentialsPassLength(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_CREDENTIALS_PASS_LENGTH, int.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_INSTANCE_ID)
    public static String provideCryostatAgentInstanceId(Config config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_INSTANCE_ID, String.class)
                .orElseGet(() -> UUID.randomUUID().toString());
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_APP_NAME)
    public static String provideCryostatAgentAppName(Config config) {
        return config.getValue(CRYOSTAT_AGENT_APP_NAME, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_HOSTNAME)
    public static String provideCryostatAgentHostname(Config config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_HOSTNAME, String.class)
                .orElseGet(
                        () -> {
                            try {
                                return InetAddress.getLocalHost().getHostName();
                            } catch (UnknownHostException uhe) {
                                log.error("Unable to determine own hostname", uhe);
                                return null;
                            }
                        });
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_APP_JMX_PORT)
    public static int provideCryostatAgentAppJmxPort(Config config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_APP_JMX_PORT, int.class)
                .orElse(
                        Integer.valueOf(
                                System.getProperty("com.sun.management.jmxremote.port", "-1")));
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_REGISTRATION_RETRY_MS)
    public static int provideCryostatAgentRegistrationRetryMs(Config config) {
        return config.getValue(CRYOSTAT_AGENT_REGISTRATION_RETRY_MS, int.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_REGISTRATION_CHECK_MS)
    public static int provideCryostatAgentRegistrationCheckMs(Config config) {
        return config.getValue(CRYOSTAT_AGENT_REGISTRATION_CHECK_MS, int.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_REGISTRATION_JMX_IGNORE)
    public static boolean provideCryostatAgentRegistrationJmxIgnore(Config config) {
        return config.getValue(CRYOSTAT_AGENT_REGISTRATION_JMX_IGNORE, boolean.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_REGISTRATION_JMX_USE_CALLBACK_HOST)
    public static boolean provideCryostatAgentRegistrationJmxUseCallbackHost(Config config) {
        return config.getValue(CRYOSTAT_AGENT_REGISTRATION_JMX_USE_CALLBACK_HOST, boolean.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_HARVESTER_PERIOD_MS)
    public static long provideCryostatAgentHarvesterPeriod(Config config) {
        return config.getValue(CRYOSTAT_AGENT_HARVESTER_PERIOD_MS, long.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_HARVESTER_TEMPLATE)
    public static String provideCryostatAgentHarvesterTemplate(Config config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_HARVESTER_TEMPLATE, String.class).orElse("");
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_HARVESTER_MAX_FILES)
    public static int provideCryostatAgentHarvesterMaxFiles(Config config) {
        return config.getValue(CRYOSTAT_AGENT_HARVESTER_MAX_FILES, int.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_HARVESTER_UPLOAD_TIMEOUT_MS)
    public static long provideCryostatAgentHarvesterUploadTimeoutMs(Config config) {
        return config.getValue(CRYOSTAT_AGENT_HARVESTER_UPLOAD_TIMEOUT_MS, long.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_HARVESTER_EXIT_MAX_AGE_MS)
    public static long provideCryostatAgentHarvesterExitMaxAge(Config config) {
        return config.getValue(CRYOSTAT_AGENT_HARVESTER_EXIT_MAX_AGE_MS, long.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_HARVESTER_EXIT_MAX_SIZE_B)
    public static long provideCryostatAgentHarvesterExitMaxSize(Config config) {
        return config.getValue(CRYOSTAT_AGENT_HARVESTER_EXIT_MAX_SIZE_B, long.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_HARVESTER_MAX_AGE_MS)
    public static long provideCryostatAgentHarvesterMaxAge(Config config) {
        return config.getValue(CRYOSTAT_AGENT_HARVESTER_MAX_AGE_MS, long.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_HARVESTER_MAX_SIZE_B)
    public static long provideCryostatAgentHarvesterMaxSize(Config config) {
        return config.getValue(CRYOSTAT_AGENT_HARVESTER_MAX_SIZE_B, long.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_EXIT_SIGNALS)
    public static List<String> provideCryostatAgentExitSignals(Config config) {
        return Arrays.asList(config.getValue(CRYOSTAT_AGENT_EXIT_SIGNALS, String.class).split(","));
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_EXIT_DEREGISTRATION_TIMEOUT_MS)
    public static long provideCryostatAgentExitDeregistrationTimeoutMs(Config config) {
        return config.getValue(CRYOSTAT_AGENT_EXIT_DEREGISTRATION_TIMEOUT_MS, long.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_SMART_TRIGGER_DEFINITIONS)
    public static List<String> provideCryostatSmartTriggerDefinitions(Config config) {
        return config.getOptionalValues(CRYOSTAT_AGENT_SMART_TRIGGER_DEFINITIONS, String.class)
                .orElse(List.of());
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_SMART_TRIGGER_EVALUATION_PERIOD_MS)
    public static long provideCryostatSmartTriggerEvaluationPeriodMs(Config config) {
        return config.getValue(CRYOSTAT_AGENT_SMART_TRIGGER_EVALUATION_PERIOD_MS, long.class);
    }

    public enum URIRange {
        LOOPBACK(u -> check(u, u2 -> true, InetAddress::isLoopbackAddress)),
        LINK_LOCAL(
                u ->
                        check(
                                u,
                                u2 -> StringUtils.isNotBlank(u2.getHost()),
                                InetAddress::isLinkLocalAddress)),
        SITE_LOCAL(
                u ->
                        check(
                                u,
                                u2 -> StringUtils.isNotBlank(u2.getHost()),
                                InetAddress::isSiteLocalAddress)),
        DNS_LOCAL(
                u ->
                        StringUtils.isNotBlank(u.getHost())
                                && (u.getHost().endsWith(".local")
                                        || u.getHost().endsWith(".localhost"))),
        PUBLIC(u -> true),
        ;

        private URIRange(Predicate<URI> fn) {
            this.fn = fn;
        }

        private final Predicate<URI> fn;

        private static boolean check(URI uri, Predicate<URI> f1, Predicate<InetAddress> f2) {
            try {
                return f1.test(uri) && f2.test(InetAddress.getByName(uri.getHost()));
            } catch (UnknownHostException uhe) {
                log.error("Failed to resolve host", uhe);
                return false;
            }
        }

        private boolean test(URI uri) {
            return fn.test(uri);
        }

        public boolean validate(URI uri) {
            List<URIRange> ranges =
                    List.of(URIRange.values()).stream()
                            .filter(r -> r.ordinal() <= this.ordinal())
                            .collect(Collectors.toList());
            boolean match = false;
            for (URIRange range : ranges) {
                match |= range.test(uri);
            }
            return match;
        }

        public static URIRange fromString(String s) {
            for (URIRange r : URIRange.values()) {
                if (r.name().equalsIgnoreCase(s)) {
                    return r;
                }
            }
            return SITE_LOCAL;
        }
    }

    public static class BytePass {
        private final byte[] buf;

        public BytePass(byte[] s) {
            this.buf = Arrays.copyOf(s, s.length);
        }

        public BytePass(String s, String charset) {
            this.buf = Arrays.copyOf(s.getBytes(Charset.forName(charset)), s.length());
        }

        public byte[] get() {
            return Arrays.copyOf(this.buf, this.buf.length);
        }

        public void clear() {
            Arrays.fill(this.buf, (byte) 0);
        }
    }

    private static Optional<URI> buildCallbackFromComponents(Config config) {
        Optional<String> scheme =
                config.getOptionalValue(CRYOSTAT_AGENT_CALLBACK_SCHEME, String.class);
        Optional<String[]> hostNames =
                config.getOptionalValue(CRYOSTAT_AGENT_CALLBACK_HOST_NAME, String[].class);
        Optional<String> domainName =
                config.getOptionalValue(CRYOSTAT_AGENT_CALLBACK_DOMAIN_NAME, String.class);
        Optional<Integer> port =
                config.getOptionalValue(CRYOSTAT_AGENT_CALLBACK_PORT, Integer.class);
        if (scheme.isPresent()
                && hostNames.isPresent()
                && domainName.isPresent()
                && port.isPresent()) {

            // Try resolving the each provided host name in order as a DNS name
            Optional<String> resolvedHost =
                    computeHostNames(hostNames.get()).stream()
                            .sequential()
                            .map(name -> name + "." + domainName.get())
                            .filter(host -> tryResolveHostname(host))
                            .findFirst();

            // If none of the above resolved, then throw an error
            if (resolvedHost.isEmpty()) {
                throw new RuntimeException(
                        "Failed to resolve hostname, consider disabling hostname verification in"
                                + " Cryostat for the agent callback");
            }

            try {
                URI result =
                        new URIBuilder()
                                .setScheme(scheme.get())
                                .setHost(resolvedHost.get())
                                .setPort(port.get())
                                .build();
                log.debug("Using {} for callback URL", result.toASCIIString());
                return Optional.of(result);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return Optional.empty();
    }

    private static List<String> computeHostNames(String[] hostNames) {
        ScriptHost scriptHost = ScriptHost.newBuilder().build();
        List<String> result = new ArrayList<>(hostNames.length);
        for (String hostName : hostNames) {
            hostName = hostName.trim();
            Matcher m = HOST_SCRIPT_PATTERN.matcher(hostName);
            if (!m.matches()) {
                throw new RuntimeException(
                        "Invalid hostname argument encountered: "
                                + hostName
                                + ". Expected format: \"hostname\" or \"hostname[cel-script]\".");
            }
            if (m.group("script") == null) {
                result.add(m.group("host"));
            } else {
                result.add(evaluateHostnameScript(scriptHost, m.group("script"), m.group("host")));
            }
        }

        return result;
    }

    private static String evaluateHostnameScript(
            ScriptHost scriptHost, String scriptText, String hostname) {
        try {
            Script script =
                    scriptHost
                            .buildScript("\"" + hostname + "\"." + scriptText)
                            .withLibraries(new StringsLib())
                            .build();
            Map<String, Object> args = new HashMap<>();
            return script.execute(String.class, args);
        } catch (ScriptException e) {
            throw new RuntimeException("Failed to execute provided CEL script", e);
        }
    }

    private static boolean tryResolveHostname(String hostname) {
        try {
            log.debug("Attempting to resolve {}", hostname);
            InetAddress addr = InetAddress.getByName(hostname);
            log.debug("Resolved {} to {}", hostname, addr.getHostAddress());
            return true;
        } catch (UnknownHostException ignored) {
            return false;
        }
    }
}
