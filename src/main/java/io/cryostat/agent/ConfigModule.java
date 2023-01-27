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

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import io.smallrye.config.SmallRyeConfig;
import org.eclipse.microprofile.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Module
public abstract class ConfigModule {

    private static final Logger log = LoggerFactory.getLogger(ConfigModule.class);

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

    @Provides
    @Singleton
    public static SmallRyeConfig provideConfig() {
        return ConfigProvider.getConfig().unwrap(SmallRyeConfig.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_BASEURI)
    public static URI provideCryostatAgentBaseUri(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_BASEURI, URI.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_CALLBACK)
    public static URI provideCryostatAgentCallback(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_CALLBACK, URI.class);
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
    public static String provideCryostatAgentAuthorization(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_AUTHORIZATION, String.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_SSL_TRUST_ALL)
    public static boolean provideCryostatAgentWebclientTrustAll(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_SSL_TRUST_ALL, boolean.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_SSL_VERIFY_HOSTNAME)
    public static boolean provideCryostatAgentWebclientVerifyHostname(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_SSL_VERIFY_HOSTNAME, boolean.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_CONNECT_TIMEOUT_MS)
    public static long provideCryostatAgentWebclientConnectTimeoutMs(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_CONNECT_TIMEOUT_MS, long.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_TIMEOUT_MS)
    public static long provideCryostatAgentWebclientResponseTimeoutMs(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_WEBCLIENT_RESPONSE_TIMEOUT_MS, long.class);
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
    @Named(CRYOSTAT_AGENT_HARVESTER_PERIOD_MS)
    public static long provideCryostatAgentHarvesterPeriod(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_HARVESTER_PERIOD_MS, long.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_HARVESTER_TEMPLATE)
    public static String provideCryostatAgentHarvesterTemplate(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_HARVESTER_TEMPLATE, String.class);
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
    public static long provideCryostatAgentHarvesterMaxAge(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_HARVESTER_EXIT_MAX_AGE_MS, long.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_HARVESTER_EXIT_MAX_SIZE_B)
    public static long provideCryostatAgentHarvesterMaxSize(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_HARVESTER_EXIT_MAX_SIZE_B, long.class);
    }

    @Provides
    @Singleton
    @Named(CRYOSTAT_AGENT_EXIT_DEREGISTRATION_TIMEOUT_MS)
    public static long provideCryostatAgentExitDeregistrationTimeoutMs(SmallRyeConfig config) {
        return config.getValue(CRYOSTAT_AGENT_EXIT_DEREGISTRATION_TIMEOUT_MS, long.class);
    }
}
