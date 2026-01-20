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
import java.net.URL;
import java.net.URLClassLoader;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

import io.cryostat.agent.mxbean.CryostatAgentMXBeanImpl;
import io.cryostat.agent.util.ResourcesUtil;
import io.cryostat.libcryostat.net.CryostatAgentMXBean;

import dagger.Module;
import dagger.Provides;
import io.smallrye.config.SmallRyeConfig;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Module
public abstract class ConfigModule {

    private static final Logger log = LoggerFactory.getLogger(ConfigModule.class);

    // this is a non-config injection key
    public static final String CRYOSTAT_AGENT_CALLBACK_CANDIDATES =
            "cryostat-agent-callback-candidates";

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
    public static final String CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_RETRY_TIME =
            "cryostat.agent.webclient.response.retry-time-seconds";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_HTTP_USE_PREEMPTIVE_AUTHENTICATION =
            "cryostat.agent.webclient.http.use-preemptive-authentication";

    public static final String CRYOSTAT_AGENT_WEBCLIENT_TLS_REQUIRED =
            "cryostat.agent.webclient.tls.required";

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

    public static final String CRYOSTAT_AGENT_PUBLISH_CONTEXT = "cryostat.agent.publish.context";
    public static final String CRYOSTAT_AGENT_PUBLISH_FILL_STRATEGY =
            "cryostat.agent.publish.fill-strategy";

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
    public static final String CRYOSTAT_AGENT_HARVESTER_AUTOANALYZE =
            "cryostat.agent.harvester.autoanalyze";

    public static final String CRYOSTAT_AGENT_ASYNC_PROFILER_REPOSITORY_PATH =
            "cryostat.agent.async-profiler.repository.path";

    public static final String CRYOSTAT_AGENT_SMART_TRIGGER_DEFINITIONS =
            "cryostat.agent.smart-trigger.definitions";
    public static final String CRYOSTAT_AGENT_SMART_TRIGGER_EVALUATION_PERIOD_MS =
            "cryostat.agent.smart-trigger.evaluation.period-ms";
    public static final String CRYOSTAT_AGENT_SMART_TRIGGER_CONFIG_PATH =
            "cryostat.agent.smart-trigger.config.path";

    public static final String CRYOSTAT_AGENT_CALLBACK_SCHEME = "cryostat.agent.callback.scheme";
    public static final String CRYOSTAT_AGENT_CALLBACK_HOST_NAME =
            "cryostat.agent.callback.host-name";
    public static final String CRYOSTAT_AGENT_CALLBACK_DOMAIN_NAME =
            "cryostat.agent.callback.domain-name";
    public static final String CRYOSTAT_AGENT_CALLBACK_PORT = "cryostat.agent.callback.port";

    public static final String CRYOSTAT_AGENT_API_WRITES_ENABLED =
            "cryostat.agent.api.writes-enabled";

    public static final String CRYOSTAT_AGENT_FLEET_SAMPLING_RATIO =
            "cryostat.agent.fleet-sampling-ratio";

    @Provides
    @Singleton
    public static SmallRyeConfig provideConfig() {
        List<Pair<String, Callable<SmallRyeConfig>>> fns = new ArrayList<>();
        fns.add(Pair.of("Simple", () -> ConfigProvider.getConfig().unwrap(SmallRyeConfig.class)));
        Function<Callable<ClassLoader>, Callable<SmallRyeConfig>> clConfigLoader =
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
                            return ConfigProvider.getConfig(loader).unwrap(SmallRyeConfig.class);
                        };
        fns.add(
                Pair.of(
                        "ResourcesUtil ClassLoader",
                        clConfigLoader.apply(ResourcesUtil::getClassLoader)));
        fns.add(
                Pair.of(
                        "Config Class ClassLoader",
                        clConfigLoader.apply(SmallRyeConfig.class::getClassLoader)));
        fns.add(
                Pair.of(
                        "Agent JAR URL ClassLoader",
                        clConfigLoader.apply(
                                () ->
                                        new URLClassLoader(
                                                new URL[] {Agent.selfJarLocation().toURL()}))));

        SmallRyeConfig config = null;
        for (Pair<String, Callable<SmallRyeConfig>> fn : fns) {
            try {
                log.trace(
                        "Testing classloader \"{}\" for {} property",
                        fn.getLeft(),
                        CRYOSTAT_AGENT_CONFIG_LOADABLE);
                SmallRyeConfig candidate = fn.getRight().call();
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
    public static URIRange provideUriRange(SmallRyeConfig config) {
        return URIRange.fromString(config.getValue(CRYOSTAT_AGENT_BASEURI_RANGE, String.class));
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_BASEURI)
    public static URI provideCryostatAgentBaseUri(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_BASEURI, URI.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_CALLBACK_CANDIDATES)
    public static List<CallbackCandidate> provideCryostatAgentCallback(SmallRyeConfig config) {
        List<CallbackCandidate> callbacks = buildCallbacksFromComponents(config);
        if (!callbacks.isEmpty()) {
            return callbacks;
        }
        return List.of(CallbackCandidate.from(config.getValue(CRYOSTAT_AGENT_CALLBACK, URI.class)));
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_REALM)
    public static String provideCryostatAgentRealm(
            SmallRyeConfig config, @Named(CRYOSTAT_AGENT_APP_NAME) String appName) {
        return config.getOptionalValue(CRYOSTAT_AGENT_REALM, String.class).orElse(appName);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_AUTHORIZATION)
    public static Supplier<Optional<String>> provideCryostatAgentAuthorization(
            SmallRyeConfig config,
            AuthorizationType authorizationType,
            @Named(CRYOSTAT_AGENT_AUTHORIZATION_VALUE) Optional<String> authorizationValue) {
        Optional<String> opt = config.getOptionalValue(CRYOSTAT_AGENT_AUTHORIZATION, String.class);
        return () -> opt.or(() -> authorizationValue.map(authorizationType::apply));
    }

    @Provides
    @Singleton
    public static AuthorizationType provideCryostatAgentAuthorizationType(SmallRyeConfig config) {
        return AuthorizationType.fromString(
                config.getValue(CRYOSTAT_AGENT_AUTHORIZATION_TYPE, String.class));
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_AUTHORIZATION_VALUE)
    public static Optional<String> provideCryostatAgentAuthorizationValue(SmallRyeConfig config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_AUTHORIZATION_VALUE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUST_ALL)
    public static boolean provideCryostatAgentWebclientTrustAll(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUST_ALL, boolean.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_VERIFY_HOSTNAME)
    public static boolean provideCryostatAgentWebclientVerifyHostname(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_VERIFY_HOSTNAME, boolean.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_CONNECT_TIMEOUT_MS)
    public static int provideCryostatAgentWebclientConnectTimeoutMs(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_CONNECT_TIMEOUT_MS, int.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_TIMEOUT_MS)
    public static int provideCryostatAgentWebclientResponseTimeoutMs(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_TIMEOUT_MS, int.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PATH)
    public static Optional<String> provideCryostatAgentWebclientTlsTruststorePath(
            SmallRyeConfig config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PATH, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PASS_FILE)
    public static Optional<BytePass> provideCryostatAgentWebclientTlsTruststorePassFromFile(
            SmallRyeConfig config) {
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
    public static String provideCryostatAgentWebclientTlsTruststorePassCharset(
            SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PASS_CHARSET, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_PASS)
    public static Optional<BytePass> provideCryostatAgentWebclientTlsTruststorePass(
            SmallRyeConfig config,
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
    public static String provideCryostatAgentWebclientTlsTruststoreType(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_TYPE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_TRUSTSTORE_CERTS)
    public static List<TruststoreConfig> provideCryostatAgentWecblientTlsTruststoreCerts(
            SmallRyeConfig config,
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
            SmallRyeConfig config) {
        return config.getOptionalValue(
                CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_CERT_PATH, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_CERT_TYPE)
    public static String provideCryostatAgentWebclientTlsClientAuthCertType(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_CERT_TYPE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_CERT_ALIAS)
    public static String provideCryostatAgentWebclientTlsClientAuthCertAlias(
            SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_CERT_ALIAS, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PATH)
    public static Optional<String> provideCryostatAgentWebclientTlsClientAuthKeyPath(
            SmallRyeConfig config) {
        return config.getOptionalValue(
                CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PATH, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_TYPE)
    public static String provideCryostatAgentWebclientTlsClientAuthKeyType(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_TYPE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_CHARSET)
    public static String provideCryostatAgentWebclientTlsClientAuthKeyCharset(
            SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_CHARSET, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_ENCODING)
    public static String provideCryostatAgentWebclientTlsClientAuthKeyEncoding(
            SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_ENCODING, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PASS_FILE)
    public static Optional<String> provideCryostatAgentWebclientTlsClientAuthKeyPassFile(
            SmallRyeConfig config) {
        return config.getOptionalValue(
                CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PASS_FILE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PASS_CHARSET)
    public static String provideCryostatAgentWebclientTlsClientAuthKeyPassCharset(
            SmallRyeConfig config) {
        return config.getValue(
                CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PASS_CHARSET, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_PASS)
    public static Optional<String> provideCryostatAgentWebclientTlsClientAuthKeyPass(
            SmallRyeConfig config) {
        return config.getOptionalValue(
                CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_PASS, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_PASS_FILE)
    public static Optional<String> provideCryostatAgentWebclientTlsClientAuthKeystorePassFile(
            SmallRyeConfig config) {
        return config.getOptionalValue(
                CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_PASS_FILE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_PASS_CHARSET)
    public static String provideCryostatAgentWebclientTlsClientAuthKeystorePassCharset(
            SmallRyeConfig config) {
        return config.getValue(
                CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_PASS_CHARSET, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_PASS)
    public static Optional<String> provideCryostatAgentWebclientTlsClientAuthKeystorePass(
            SmallRyeConfig config) {
        return config.getOptionalValue(
                CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_PASS, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_TYPE)
    public static String provideCryostatAgentWebclientTlsClientAuthKeystoreType(
            SmallRyeConfig config) {
        return config.getValue(
                CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEYSTORE_TYPE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_MANAGER_TYPE)
    public static String provideCryostatAgentWebclientTlsClientAuthKeyManagerType(
            SmallRyeConfig config) {
        return config.getValue(
                CRYOSTAT_AGENT_WEBCLIENT_TLS_CLIENT_AUTH_KEY_MANAGER_TYPE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_RETRY_COUNT)
    public static int provideCryostatAgentWebclientResponseRetryCount(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_RETRY_COUNT, int.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_RETRY_TIME)
    public static int provideCryostatAgentWebclientResponseRetryTime(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_RETRY_TIME, int.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_HTTP_USE_PREEMPTIVE_AUTHENTICATION)
    public static boolean provideCryostatAgentWebclientHttpUsePreemptiveAuthentication(
            Config config) {
        return config.getValue(
                CRYOSTAT_AGENT_WEBCLIENT_HTTP_USE_PREEMPTIVE_AUTHENTICATION, boolean.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_HOST)
    public static String provideCryostatAgentWebserverHost(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_HOST, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_PORT)
    public static int provideCryostatAgentWebserverPort(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_PORT, int.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_VERSION)
    public static String provideCryostatAgentWebclientTlsVersion(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_VERSION, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_VERSION)
    public static String provideCryostatAgentWebserverTlsVersion(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_TLS_VERSION, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_PASS)
    public static Optional<String> provideCryostatAgentWebserverTlsKeyStorePass(
            SmallRyeConfig config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_PASS, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_PASS_CHARSET)
    public static String provideCryostatAgentWebserverTlsKeyStorePassCharset(
            SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_PASS_CHARSET, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_FILE)
    public static Optional<String> provideCryostatAgentWebserverTlsKeyStoreFile(
            SmallRyeConfig config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_FILE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_TYPE)
    public static String provideCryostatAgentWebserverTlsKeyStoreType(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEYSTORE_TYPE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_ALIAS)
    public static String provideCryostatAgentWebserverTlsKeyAlias(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_ALIAS, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PATH)
    public static Optional<String> provideCryostatAgentWebserverTlsKeyPath(SmallRyeConfig config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PATH, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_CHARSET)
    public static String provideCryostatAgentWebserverTlsKeyCharset(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_CHARSET, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_ENCODING)
    public static String provideCryostatAgentWebserverTlsKeyEncoding(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_ENCODING, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_TYPE)
    public static String provideCryostatAgentWebserverTlsKeyType(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_TYPE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PASS_FILE)
    public static Optional<String> provideCryostatAgentWebserverTlsKeyPassFile(
            SmallRyeConfig config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PASS_FILE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PASS_CHARSET)
    public static String provideCryostatAgentWebserverTlsKeyPassCharset(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PASS_CHARSET, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PASS)
    public static Optional<String> provideCryostatAgentWebserverTlsKeyPass(SmallRyeConfig config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_WEBSERVER_TLS_KEY_PASS, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_ALIAS)
    public static String provideCryostatAgentWebserverTlsCertAlias(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_ALIAS, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_FILE)
    public static Optional<String> provideCryostatAgentWebserverTlsCertFile(SmallRyeConfig config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_FILE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_TYPE)
    public static String provideCryostatAgentWebserverTlsCertType(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_TLS_CERT_TYPE, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_CREDENTIALS_USER)
    public static String provideCryostatAgentWebserverCredentialsUser(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_CREDENTIALS_USER, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBSERVER_CREDENTIALS_PASS_HASH_FUNCTION)
    public static MessageDigest provideCryostatAgentWebserverCredentialsPassHashFunction(
            SmallRyeConfig config) {
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
    public static int provideCryostatAgentWebserverCredentialsPassLength(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBSERVER_CREDENTIALS_PASS_LENGTH, int.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_INSTANCE_ID)
    public static String provideCryostatAgentInstanceId(SmallRyeConfig config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_INSTANCE_ID, String.class)
                .orElseGet(() -> UUID.randomUUID().toString());
    }

    @Provides
    @Singleton
    public static CryostatAgentMXBean provideCryostatAgentMXBean(
            @Named(CRYOSTAT_AGENT_INSTANCE_ID) String id) {
        return new CryostatAgentMXBeanImpl(id);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_APP_NAME)
    public static String provideCryostatAgentAppName(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_APP_NAME, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_HOSTNAME)
    public static String provideCryostatAgentHostname(SmallRyeConfig config) {
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
    public static int provideCryostatAgentAppJmxPort(SmallRyeConfig config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_APP_JMX_PORT, int.class)
                .orElse(
                        Integer.valueOf(
                                System.getProperty("com.sun.management.jmxremote.port", "-1")));
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_REGISTRATION_RETRY_MS)
    public static int provideCryostatAgentRegistrationRetryMs(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_REGISTRATION_RETRY_MS, int.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_REGISTRATION_CHECK_MS)
    public static int provideCryostatAgentRegistrationCheckMs(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_REGISTRATION_CHECK_MS, int.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_REGISTRATION_JMX_IGNORE)
    public static boolean provideCryostatAgentRegistrationJmxIgnore(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_REGISTRATION_JMX_IGNORE, boolean.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_REGISTRATION_JMX_USE_CALLBACK_HOST)
    public static boolean provideCryostatAgentRegistrationJmxUseCallbackHost(
            SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_REGISTRATION_JMX_USE_CALLBACK_HOST, boolean.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_HARVESTER_PERIOD_MS)
    public static long provideCryostatAgentHarvesterPeriod(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_HARVESTER_PERIOD_MS, long.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_HARVESTER_TEMPLATE)
    public static String provideCryostatAgentHarvesterTemplate(SmallRyeConfig config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_HARVESTER_TEMPLATE, String.class).orElse("");
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_HARVESTER_MAX_FILES)
    public static int provideCryostatAgentHarvesterMaxFiles(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_HARVESTER_MAX_FILES, int.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_HARVESTER_UPLOAD_TIMEOUT_MS)
    public static long provideCryostatAgentHarvesterUploadTimeoutMs(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_HARVESTER_UPLOAD_TIMEOUT_MS, long.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_HARVESTER_EXIT_MAX_AGE_MS)
    public static long provideCryostatAgentHarvesterExitMaxAge(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_HARVESTER_EXIT_MAX_AGE_MS, long.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_HARVESTER_EXIT_MAX_SIZE_B)
    public static long provideCryostatAgentHarvesterExitMaxSize(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_HARVESTER_EXIT_MAX_SIZE_B, long.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_HARVESTER_MAX_AGE_MS)
    public static long provideCryostatAgentHarvesterMaxAge(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_HARVESTER_MAX_AGE_MS, long.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_HARVESTER_MAX_SIZE_B)
    public static long provideCryostatAgentHarvesterMaxSize(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_HARVESTER_MAX_SIZE_B, long.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_HARVESTER_AUTOANALYZE)
    public static boolean provideCryostatAgentHarvesterAutoanalyze(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_HARVESTER_AUTOANALYZE, boolean.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_ASYNC_PROFILER_REPOSITORY_PATH)
    public static Path provideCryostatAgentAsyncProfilerRepositoryPath(Config config) {
        try {
            Path repository =
                    config.getOptionalValue(
                                    CRYOSTAT_AGENT_ASYNC_PROFILER_REPOSITORY_PATH, String.class)
                            .map(Path::of)
                            .orElse(Files.createTempDirectory("cryostat-async-profiler"));
            log.debug("Using async-profiler repository: {}", repository);
            return repository;
        } catch (IOException ioe) {
            log.error("Failed to create async-profiler repository", ioe);
            throw new RuntimeException(ioe);
        }
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_EXIT_SIGNALS)
    public static List<String> provideCryostatAgentExitSignals(SmallRyeConfig config) {
        return Arrays.asList(config.getValue(CRYOSTAT_AGENT_EXIT_SIGNALS, String.class).split(","));
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_EXIT_DEREGISTRATION_TIMEOUT_MS)
    public static long provideCryostatAgentExitDeregistrationTimeoutMs(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_EXIT_DEREGISTRATION_TIMEOUT_MS, long.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_PUBLISH_CONTEXT)
    public static Map<String, String> provideCryostatAgentPublishContext(SmallRyeConfig config) {
        return config.getOptionalValues(CRYOSTAT_AGENT_PUBLISH_CONTEXT, String.class, String.class)
                .orElse(Map.of());
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_PUBLISH_FILL_STRATEGY)
    public static String provideCryostatAgentPublishFillStrategy(SmallRyeConfig config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_PUBLISH_FILL_STRATEGY, String.class)
                .orElse("");
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_SMART_TRIGGER_DEFINITIONS)
    public static List<String> provideCryostatSmartTriggerDefinitions(SmallRyeConfig config) {
        return config.getOptionalValues(CRYOSTAT_AGENT_SMART_TRIGGER_DEFINITIONS, String.class)
                .orElse(List.of());
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_SMART_TRIGGER_EVALUATION_PERIOD_MS)
    public static long provideCryostatSmartTriggerEvaluationPeriodMs(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_SMART_TRIGGER_EVALUATION_PERIOD_MS, long.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_SMART_TRIGGER_CONFIG_PATH)
    public static Optional<Path> provideCryostatSmartTriggerConfigFiles(SmallRyeConfig config) {
        return config.getOptionalValue(CRYOSTAT_AGENT_SMART_TRIGGER_CONFIG_PATH, Path.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_TLS_REQUIRED)
    public static boolean provideCryostatAgentTlsEnabled(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_TLS_REQUIRED, boolean.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_FLEET_SAMPLING_RATIO)
    public static double provideCryostatAgentFleetSamplingRatio(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_FLEET_SAMPLING_RATIO, double.class);
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

    private static List<CallbackCandidate> buildCallbacksFromComponents(SmallRyeConfig config) {
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
            return Arrays.asList(hostNames.get()).stream()
                    .map(
                            hostname ->
                                    new CallbackCandidate(
                                            scheme.get(), hostname, domainName.get(), port.get()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    public static class CallbackCandidate {
        private final String scheme;
        private final String hostname;
        private final String domainName;
        private final int port;

        public static CallbackCandidate from(URI uri) {
            String host, domain;
            String hostname = uri.getHost();
            if (hostname.contains(".")) {
                host = hostname.substring(0, hostname.indexOf('.'));
                domain = hostname.substring(hostname.indexOf('.') + 1);
            } else {
                host = hostname;
                domain = null;
            }
            return new CallbackCandidate(uri.getScheme(), host, domain, uri.getPort());
        }

        public CallbackCandidate(String scheme, String hostname, String domainName, int port) {
            this.scheme = scheme.strip();
            this.hostname = hostname.strip();
            if (domainName == null) {
                this.domainName = null;
            } else {
                this.domainName = domainName.strip();
            }
            this.port = port;
        }

        public String getScheme() {
            return scheme;
        }

        public String getHostname() {
            return hostname;
        }

        public String getDomainName() {
            return domainName;
        }

        public int getPort() {
            return port;
        }

        @Override
        public int hashCode() {
            return Objects.hash(scheme, hostname, domainName, port);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            CallbackCandidate other = (CallbackCandidate) obj;
            return Objects.equals(scheme, other.scheme)
                    && Objects.equals(hostname, other.hostname)
                    && Objects.equals(domainName, other.domainName)
                    && port == other.port;
        }

        @Override
        public String toString() {
            return "CallbackCandidate [scheme="
                    + scheme
                    + ", hostname="
                    + hostname
                    + ", domainName="
                    + domainName
                    + ", port="
                    + port
                    + "]";
        }
    }
}
