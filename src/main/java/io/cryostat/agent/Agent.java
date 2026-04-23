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
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import io.cryostat.agent.ConfigModule.URIRange;
import io.cryostat.agent.VersionInfo.Semver;
import io.cryostat.agent.harvest.Harvester;
import io.cryostat.agent.insights.InsightsAgentHelper;
import io.cryostat.agent.model.PluginInfo;
import io.cryostat.agent.mxbean.CryostatAgentMXBeanImpl;
import io.cryostat.agent.triggers.TriggerEvaluator;
import io.cryostat.libcryostat.net.CryostatAgentMXBean;

import dagger.Component;
import io.smallrye.config.SmallRyeConfig;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import sun.misc.Signal;
import sun.misc.SignalHandler;

@Command(
        name = "CryostatAgent",
        mixinStandardHelpOptions = true,
        showAtFileInUsageHelp = true,
        versionProvider = Agent.VersionProvider.class,
        description =
                "Launcher for Cryostat Agent to self-inject and dynamically attach to workload"
                        + " JVMs")
public class Agent implements Callable<Integer>, Consumer<AgentArgs> {

    private static final AtomicBoolean needsCleanup = new AtomicBoolean(true);

    @Parameters(
            index = "0",
            defaultValue = "0",
            arity = "0..1",
            description =
                    "The PID to attach to and attempt to self-inject the Cryostat Agent. If not"
                        + " specified, the Agent will look to find exactly one candidate and attach"
                        + " to that, failing if none or more than one are found. Otherwise, this"
                        + " should be a process ID, or the '*' wildcard to request the Agent"
                        + " attempt to attach to all discovered JVMs.")
    String pid;

    @Option(
            names = {"-D", "--property"},
            description =
                    "Optional property definitions to supply to the injected Agent copies to add or"
                        + " override property definitions once the Agent is running in the workload"
                        + " JVM. These should be specified as key=value pairs, ex."
                        + " -Dcryostat.agent.baseuri=http://cryostat.service.local . May be"
                        + " specified more than once.")
    Map<String, String> properties;

    @Option(
            names = "--smartTrigger",
            description = "Smart Triggers definition. May be specified more than once.")
    List<String> smartTriggers;

    @Option(
            names = {"-w", "--watch"},
            defaultValue = "false",
            description =
                    "Watch mode. If this is set then the initial Agent process will stay alive and"
                        + " watch for more JVM PIDs to appear and attempt self-injection on each"
                        + " one. This implies the attach PID '*'.")
    boolean watch;

    @Option(
            names = {"-i", "--watch-include"},
            defaultValue = "",
            arity = "1..*",
            description =
                    "Watch mode inclusion filter. If this is set then the Agent will only attempt"
                        + " to self-inject to JVMs whose descriptors (ex. Main-Class name or JAR"
                        + " path) include any of the given keyword string values. This is only used"
                        + " when watch mode is enabled. The default is a single empty string, which"
                        + " allows self-injection to any JVM. Pass this option more than once to"
                        + " specify additional keywords, or pass more than one value to this"
                        + " option. This is case insensitive.")
    List<String> watchIncludeKeywords;

    // standalone entry point, Agent is started separately and should self-inject and dynamic attach
    // to the host JVM application. After the agent is dynamically attached we should begin
    // execution again in agentmain
    public static void main(String[] args) {
        int exitCode = new CommandLine(new Agent()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        new Attacher().attach(this);
        return 0;
    }

    // -javaagent entry point, Agent is starting before the host JVM application
    public static void premain(String args, Instrumentation instrumentation) {
        agentmain(args, instrumentation);
    }

    // dynamic attach entry point, Agent is starting after being loaded and attached to a running
    // JVM application
    public static void agentmain(String args, Instrumentation instrumentation) {
        AgentArgs aa = AgentArgs.from(instrumentation, args);
        // set properties early before instantiating the Agent proper, in case properties include
        // things like log level overrides which must be done before instances are created and may
        // be cached
        aa.getProperties().forEach((k, v) -> System.setProperty(k, v));
        Agent agent = new Agent();
        Thread t =
                new Thread(
                        () -> {
                            agent.accept(aa);
                        });
        t.setDaemon(true);
        t.setName("cryostat-agent-main");
        t.start();
    }

    @Override
    public void accept(AgentArgs args) {
        Map<String, String> properties = new HashMap<>();
        properties.putAll(args.getProperties());
        properties.put("build.git.commit-hash", new BuildInfo().getGitInfo().getHash());
        Semver agentVersion = Semver.UNKNOWN;
        Pair<Semver, Semver> serverVersionRange = Pair.of(Semver.UNKNOWN, Semver.UNKNOWN);
        IOException propsIoe = null;
        try {
            VersionInfo versionInfo = VersionInfo.load();
            serverVersionRange = Pair.of(versionInfo.getServerMin(), versionInfo.getServerMax());
            agentVersion = versionInfo.getAgentVersion();
            properties.putAll(versionInfo.asMap());
        } catch (IOException ioe) {
            propsIoe = ioe;
        }
        // set properties first, since properties may include log level override
        properties.forEach((k, v) -> System.setProperty(k, v));
        // after setting properties, log what we did
        Logger log = LoggerFactory.getLogger(getClass());
        if (propsIoe != null) {
            log.warn("Unable to read versions.properties file", propsIoe);
        }
        properties.forEach((k, v) -> log.trace("Set system property {} = {}", k, v));
        log.debug(
                "Cryostat Agent version {} (for Cryostat server version range [{}, {}) )"
                        + " starting...",
                agentVersion,
                serverVersionRange.getLeft(),
                serverVersionRange.getRight());
        AgentExitHandler agentExitHandler = null;
        try {
            final Client client = DaggerAgent_Client.builder().build();

            CryostatAgentMXBean mxBean = client.agentMXBean();
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            mbs.registerMBean(mxBean, new ObjectName(CryostatAgentMXBean.OBJECT_NAME));

            boolean sample = client.fleetSampleValue() < client.fleetSamplingRatio();
            log.trace(
                    "fleetSampleValue: {} , fleetSampleRatio: {}",
                    client.fleetSampleValue(),
                    client.fleetSamplingRatio());
            if (!sample) {
                log.debug(
                        "Cryostat Agent aborting startup - fleet sample value {} is greater than"
                                + " the configured sampling ratio {}",
                        client.fleetSampleValue(),
                        client.fleetSamplingRatio());
                return;
            }

            InsightsAgentHelper insights =
                    new InsightsAgentHelper(client.config(), args.getInstrumentation());

            URI baseUri = client.baseUri();
            URIRange uriRange = client.uriRange();
            if (!uriRange.validate(baseUri)) {
                throw new IllegalArgumentException(
                        String.format(
                                "cryostat.agent.baseuri of \"%s\" is unacceptable with URI range"
                                        + " \"%s\"",
                                baseUri, uriRange));
            }

            Registration registration = client.registration();
            Harvester harvester = client.harvester();
            WebServer webServer = client.webServer();
            CredentialCleanupJob credentialCleanupJob = client.credentialCleanupJob();
            CredentialTracker credentialTracker = client.credentialTracker();
            CryostatClient cryostatClient = client.cryostatClient();
            ExecutorService executor = client.executor();
            List<String> exitSignals = client.exitSignals();
            long exitDeregistrationTimeout = client.exitDeregistrationTimeout();

            agentExitHandler =
                    installSignalHandlers(
                            log,
                            exitSignals,
                            registration,
                            harvester,
                            webServer,
                            credentialCleanupJob,
                            credentialTracker,
                            cryostatClient,
                            executor,
                            exitDeregistrationTimeout);
            final AgentExitHandler fHandler = agentExitHandler;
            Thread t =
                    new Thread(
                            () -> {
                                if (needsCleanup.getAndSet(false)) {
                                    fHandler.performCleanup(null);
                                }
                            });
            t.setName("cryostat-agent-shutdown");
            t.setDaemon(false);
            Runtime.getRuntime().addShutdownHook(t);

            registration.addRegistrationListener(
                    evt -> {
                        switch (evt.state) {
                            case REGISTERED:
                                log.debug("Registration state: {}", evt.state);
                                // If Red Hat Insights support is enabled, set it up
                                setupInsightsIfEnabled(log, insights, registration.getPluginInfo());
                                break;
                            case UNREGISTERED:
                            case REFRESHING:
                            case REFRESHED:
                            case PUBLISHED:
                                log.debug("Registration state: {}", evt.state);
                                break;
                            default:
                                log.warn("Unknown registration state: {}", evt.state);
                                break;
                        }
                    });
            webServer.start();
            registration.start();
            credentialCleanupJob.start();
            client.triggerEvaluator().start(args.getSmartTriggers());
            log.trace("Startup complete");
        } catch (Exception e) {
            log.error(Agent.class.getSimpleName() + " startup failure", e);
            if (agentExitHandler != null) {
                agentExitHandler.reset();
            }
        }
    }

    private static AgentExitHandler installSignalHandlers(
            Logger log,
            List<String> exitSignals,
            Registration registration,
            Harvester harvester,
            WebServer webServer,
            CredentialCleanupJob credentialCleanupJob,
            CredentialTracker credentialTracker,
            CryostatClient cryostatClient,
            ExecutorService executor,
            long exitDeregistrationTimeout) {
        AgentExitHandler agentExitHandler =
                new AgentExitHandler(
                        registration,
                        harvester,
                        webServer,
                        credentialCleanupJob,
                        credentialTracker,
                        cryostatClient,
                        executor,
                        exitDeregistrationTimeout);
        for (String s : exitSignals) {
            Signal signal = new Signal(s);
            try {
                SignalHandler oldHandler = Signal.handle(signal, agentExitHandler);
                agentExitHandler.setOldHandler(signal, oldHandler);
            } catch (IllegalArgumentException iae) {
                log.warn("Unable to register signal handler for SIG" + s, iae);
            }
        }
        return agentExitHandler;
    }

    private static void setupInsightsIfEnabled(
            Logger log, InsightsAgentHelper insights, PluginInfo pluginInfo) {
        if (insights != null && insights.isInsightsEnabled(pluginInfo)) {
            try {
                insights.runInsightsAgent(pluginInfo);
                log.debug("Started Red Hat Insights client");
            } catch (Throwable e) {
                log.error("Unable to start Red Hat Insights client", e);
            }
        }
    }

    @Singleton
    @Component(modules = {MainModule.class})
    interface Client {
        SmallRyeConfig config();

        @Named(ConfigModule.CRYOSTAT_AGENT_FLEET_SAMPLING_RATIO)
        double fleetSamplingRatio();

        @Named(MainModule.CRYOSTAT_AGENT_FLEET_SAMPLE_VALUE)
        double fleetSampleValue();

        @Named(ConfigModule.CRYOSTAT_AGENT_BASEURI)
        URI baseUri();

        @Named(ConfigModule.CRYOSTAT_AGENT_BASEURI_RANGE)
        URIRange uriRange();

        CryostatAgentMXBeanImpl agentMXBean();

        WebServer webServer();

        Registration registration();

        Harvester harvester();

        TriggerEvaluator triggerEvaluator();

        CredentialCleanupJob credentialCleanupJob();

        CredentialTracker credentialTracker();

        CryostatClient cryostatClient();

        ScheduledExecutorService executor();

        @Named(ConfigModule.CRYOSTAT_AGENT_EXIT_SIGNALS)
        List<String> exitSignals();

        @Named(ConfigModule.CRYOSTAT_AGENT_EXIT_DEREGISTRATION_TIMEOUT_MS)
        long exitDeregistrationTimeout();

        @Component.Builder
        interface Builder {
            Client build();
        }
    }

    private static class AgentExitHandler implements SignalHandler {

        private static Logger log = LoggerFactory.getLogger(AgentExitHandler.class);

        private final Map<Signal, SignalHandler> oldHandlers = new HashMap<>();
        private final Registration registration;
        private final Harvester harvester;
        private final WebServer webServer;
        private final CredentialCleanupJob credentialCleanupJob;
        private final CredentialTracker credentialTracker;
        private final CryostatClient cryostatClient;
        private final ExecutorService executor;
        private final long exitDeregistrationTimeout;

        private AgentExitHandler(
                Registration registration,
                Harvester harvester,
                WebServer webServer,
                CredentialCleanupJob credentialCleanupJob,
                CredentialTracker credentialTracker,
                CryostatClient cryostatClient,
                ExecutorService executor,
                long exitDeregistrationTimeout) {
            this.registration = Objects.requireNonNull(registration);
            this.harvester = Objects.requireNonNull(harvester);
            this.webServer = Objects.requireNonNull(webServer);
            this.credentialCleanupJob = Objects.requireNonNull(credentialCleanupJob);
            this.credentialTracker = Objects.requireNonNull(credentialTracker);
            this.cryostatClient = Objects.requireNonNull(cryostatClient);
            this.executor = Objects.requireNonNull(executor);
            this.exitDeregistrationTimeout = exitDeregistrationTimeout;
        }

        void setOldHandler(Signal signal, SignalHandler oldHandler) {
            this.oldHandlers.put(signal, oldHandler);
        }

        @Override
        public void handle(Signal sig) {
            if (sig == null) {
                log.debug("'null' signal handler invoked");
            } else {
                log.debug("Caught SIG{}({})", sig.getName(), sig.getNumber());
            }
            if (needsCleanup.getAndSet(false)) {
                performCleanup(sig);
            }
        }

        void performCleanup(Signal sig) {
            log.trace("Performing cleanup...");
            try {
                harvester.exitUpload().get();
            } catch (InterruptedException | ExecutionException e) {
                log.error("Exit upload failed", e);
            } finally {
                registration
                        .deregister()
                        .orTimeout(exitDeregistrationTimeout, TimeUnit.MILLISECONDS)
                        .handle(
                                (v, t) -> {
                                    if (t != null) {
                                        log.warn("Exception during deregistration", t);
                                    }

                                    try {
                                        Set<Integer> orphaned =
                                                credentialTracker.getOrphanedCredentials();
                                        if (!orphaned.isEmpty()) {
                                            log.debug(
                                                    "Cleaning up {} credentials on shutdown",
                                                    orphaned.size());
                                            List<CompletableFuture<Void>> deletions =
                                                    orphaned.stream()
                                                            .map(cryostatClient::deleteCredentials)
                                                            .collect(Collectors.toList());
                                            CompletableFuture.allOf(
                                                            deletions.toArray(
                                                                    new CompletableFuture[0]))
                                                    .get(30, TimeUnit.SECONDS);
                                            log.debug("Cleaned up all credentials");
                                        }
                                    } catch (Exception e) {
                                        log.error("Error during credential cleanup on shutdown", e);
                                    }

                                    try {
                                        log.debug("Shutting down...");
                                        safeCall(webServer::stop);
                                        safeCall(registration::stop);
                                        safeCall(credentialCleanupJob::stop);
                                        safeCall(executor::shutdown);
                                    } finally {
                                        log.debug("Shutdown complete");
                                        if (sig != null) {
                                            // pass signal on to whatever would have handled it had
                                            // this Agent not been installed, so host application
                                            // has a chance to perform a graceful shutdown
                                            oldHandlers.get(sig).handle(sig);
                                        }
                                    }
                                    return null;
                                });
            }
        }

        void reset() {
            this.oldHandlers.forEach(Signal::handle);
            this.oldHandlers.clear();
        }

        private void safeCall(Runnable r) {
            try {
                r.run();
            } catch (Exception e) {
                log.warn("Exception during shutdown", e);
            }
        }
    }

    static class VersionProvider implements IVersionProvider {

        @Override
        public String[] getVersion() throws Exception {
            return new String[] {Agent.class.getPackage().getImplementationVersion()};
        }
    }
}
