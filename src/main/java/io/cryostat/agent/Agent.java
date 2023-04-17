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
            Registration registration = client.registration();
            Harvester harvester = client.harvester();
            WebServer webServer = client.webServer();
            ExecutorService executor = client.executor();
            List<String> exitSignals = client.exitSignals();
            long exitDeregistrationTimeout = client.exitDeregistrationTimeout();

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

    public static void agentmain(String args) {
        Thread t =
                new Thread(
                        () -> {
                            log.info("Cryostat Agent starting...");
                            main(args == null ? new String[0] : args.split("\\s"));
                        });
        t.setDaemon(true);
        t.setName("cryostat-agent-main");
        t.start();
    }

    public static void premain(String args) {
        agentmain(args);
    }

    @Singleton
    @Component(modules = {MainModule.class})
    interface Client {
        WebServer webServer();

        Registration registration();

        Harvester harvester();

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
