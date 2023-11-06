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
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
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

    public static final String CRYOSTAT_AGENT_APP_NAME = "cryostat.agent.app.name";
    public static final String CRYOSTAT_AGENT_HOSTNAME = "cryostat.agent.hostname";
    public static final String CRYOSTAT_AGENT_APP_JMX_PORT = "cryostat.agent.app.jmx.port";
    public static final String CRYOSTAT_AGENT_REGISTRATION_RETRY_MS =
            "cryostat.agent.registration.retry-ms";
    public static final String CRYOSTAT_AGENT_REGISTRATION_CHECK_MS =
            "cryostat.agent.registration.check-ms";
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
        return ConfigProvider.getConfig();
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
    public static String provideCryostatAgentAuthorization(Config config) {
        return config.getValue(CRYOSTAT_AGENT_AUTHORIZATION, String.class);
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
