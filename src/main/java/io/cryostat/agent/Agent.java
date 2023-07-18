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
import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Named;
import javax.inject.Singleton;

import io.cryostat.agent.ConfigModule.URIRange;
import io.cryostat.agent.harvest.Harvester;
import io.cryostat.agent.triggers.TriggerEvaluator;

import dagger.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Signal;
import sun.misc.SignalHandler;

public class Agent {

    private static Logger log = LoggerFactory.getLogger(Agent.class);
    private static final AtomicBoolean needsCleanup = new AtomicBoolean(true);

    public static void main(String[] args) {
        AgentExitHandler agentExitHandler = null;
        try {
            final Client client = DaggerAgent_Client.builder().build();

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
            ExecutorService executor = client.executor();
            List<String> exitSignals = client.exitSignals();
            long exitDeregistrationTimeout = client.exitDeregistrationTimeout();
            InsightsAgentHelper insights = client.insights();

            agentExitHandler =
                    installSignalHandlers(
                            exitSignals,
                            registration,
                            harvester,
                            webServer,
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
                                log.info("Registration state: {}", evt.state);
                                try {
                                    insights.runInsightsAgent(registration.getPluginInfo());
                                    log.info("Started Red Hat Insights client");
                                } catch (Throwable e) {
                                    log.error("Unable to start Red Hat Insights client", e);
                                }
                                break;
                            case UNREGISTERED:
                            case REFRESHING:
                            case REFRESHED:
                            case PUBLISHED:
                                log.info("Registration state: {}", evt.state);
                                break;
                            default:
                                log.error("Unknown registration state: {}", evt.state);
                                break;
                        }
                    });
            webServer.start();
            registration.start();
            client.triggerEvaluator().start(args);
            log.info("Startup complete");
        } catch (Exception e) {
            log.error(Agent.class.getSimpleName() + " startup failure", e);
            if (agentExitHandler != null) {
                agentExitHandler.reset();
            }
        }
    }

    private static AgentExitHandler installSignalHandlers(
            List<String> exitSignals,
            Registration registration,
            Harvester harvester,
            WebServer webServer,
            ExecutorService executor,
            long exitDeregistrationTimeout) {
        AgentExitHandler agentExitHandler =
                new AgentExitHandler(
                        registration, harvester, webServer, executor, exitDeregistrationTimeout);
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

    public static void agentmain(String args, Instrumentation instrumentation) {
        Thread t =
                new Thread(
                        () -> {
                            log.info("Cryostat Agent starting...");
                            InsightsAgentHelper.instrument(instrumentation);
                            main(args == null ? new String[0] : args.split("\\s"));
                        });
        t.setDaemon(true);
        t.setName("cryostat-agent-main");
        t.start();
    }

    public static void premain(String args, Instrumentation instrumentation) {
        agentmain(args, instrumentation);
    }

    @Singleton
    @Component(modules = {MainModule.class})
    interface Client {
        @Named(ConfigModule.CRYOSTAT_AGENT_BASEURI)
        URI baseUri();

        @Named(ConfigModule.CRYOSTAT_AGENT_BASEURI_RANGE)
        URIRange uriRange();

        WebServer webServer();

        Registration registration();

        Harvester harvester();

        TriggerEvaluator triggerEvaluator();

        ScheduledExecutorService executor();

        @Named(ConfigModule.CRYOSTAT_AGENT_EXIT_SIGNALS)
        List<String> exitSignals();

        @Named(ConfigModule.CRYOSTAT_AGENT_EXIT_DEREGISTRATION_TIMEOUT_MS)
        long exitDeregistrationTimeout();

        InsightsAgentHelper insights();

        @Component.Builder
        interface Builder {
            Client build();
        }
    }

    private static class AgentExitHandler implements SignalHandler {

        private static Logger log = LoggerFactory.getLogger(Agent.class);

        private final Map<Signal, SignalHandler> oldHandlers = new HashMap<>();
        private final Registration registration;
        private final Harvester harvester;
        private final WebServer webServer;
        private final ExecutorService executor;
        private final long exitDeregistrationTimeout;

        private AgentExitHandler(
                Registration registration,
                Harvester harvester,
                WebServer webServer,
                ExecutorService executor,
                long exitDeregistrationTimeout) {
            this.registration = Objects.requireNonNull(registration);
            this.harvester = Objects.requireNonNull(harvester);
            this.webServer = Objects.requireNonNull(webServer);
            this.executor = Objects.requireNonNull(executor);
            this.exitDeregistrationTimeout = exitDeregistrationTimeout;
        }

        void setOldHandler(Signal signal, SignalHandler oldHandler) {
            this.oldHandlers.put(signal, oldHandler);
        }

        @Override
        public void handle(Signal sig) {
            log.info("Caught SIG{}({})", sig.getName(), sig.getNumber());
            if (needsCleanup.getAndSet(false)) {
                performCleanup(sig);
            }
        }

        void performCleanup(Signal sig) {
            log.info("Performing cleanup...");
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
                                        log.info("Shutting down...");
                                        safeCall(webServer::stop);
                                        safeCall(registration::stop);
                                        safeCall(executor::shutdown);
                                    } finally {
                                        log.info("Shutdown complete");
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
}
