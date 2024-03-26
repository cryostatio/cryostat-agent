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

import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.UnknownHostException;
import java.security.AccessController;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.inject.Named;
import javax.inject.Singleton;

import io.cryostat.agent.util.StringUtils;

import dagger.Module;
import dagger.Provides;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
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

    public static final String CRYOSTAT_AGENT_WEBCLIENT_SSL_TRUST_ALL =
            "cryostat.agent.webclient.ssl.trust-all";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_SSL_VERIFY_HOSTNAME =
            "cryostat.agent.webclient.ssl.verify-hostname";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_CONNECT_TIMEOUT_MS =
            "cryostat.agent.webclient.connect.timeout-ms";
    public static final String CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_TIMEOUT_MS =
            "cryostat.agent.webclient.response.timeout-ms";

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

    public static final String CRYOSTAT_AGENT_API_WRITES_ENABLED =
            "cryostat.agent.api.writes-enabled";

    @Provides
    @Singleton
    public static Config provideConfig() {
        // if we don't do this then the SmallRye Config loader may end up with a null classloader in
        // the case that the Agent starts separately and is dynamically attached to a running VM,
        // which results in an NPE. Here we try to detect and preempt that case and ensure that
        // there is a reasonable classloader for the SmallRye config loader to use.
        PrivilegedExceptionAction<ClassLoader> pea =
                () -> {
                    ClassLoader cl = Thread.currentThread().getContextClassLoader();
                    if (cl != null) {
                        return cl;
                    }
                    return new URLClassLoader(new URL[] {Agent.selfJarLocation().toURL()});
                };
        try {
            ClassLoader cl = AccessController.doPrivileged(pea);
            return ConfigProvider.getConfig(cl);
        } catch (PrivilegedActionException pae) {
            throw new RuntimeException(pae);
        }
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
    public static Optional<String> provideCryostatAgentAuthorization(
            Config config,
            AuthorizationType authorizationType,
            @Named(CRYOSTAT_AGENT_AUTHORIZATION_VALUE) Optional<String> authorizationValue) {
        Optional<String> opt = config.getOptionalValue(CRYOSTAT_AGENT_AUTHORIZATION, String.class);
        return opt.or(() -> authorizationValue.map(authorizationType::apply));
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
    @Named(CRYOSTAT_AGENT_WEBCLIENT_SSL_TRUST_ALL)
    public static boolean provideCryostatAgentWebclientTrustAll(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_SSL_TRUST_ALL, boolean.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_SSL_VERIFY_HOSTNAME)
    public static boolean provideCryostatAgentWebclientVerifyHostname(Config config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_SSL_VERIFY_HOSTNAME, boolean.class);
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
}
