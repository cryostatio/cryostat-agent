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

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Singleton;

import dagger.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Signal;
import sun.misc.SignalHandler;

public class Agent {

    private static Logger log = LoggerFactory.getLogger(Agent.class);

    public static void main(String[] args) {
        final Client client = DaggerAgent_Client.builder().build();

        List.of(new Signal("INT"), new Signal("TERM"))
                .forEach(
                        signal -> {
                            SignalHandler oldHandler = Signal.handle(signal, s -> {});
                            SignalHandler handler =
                                    s -> {
                                        log.info("Caught SIG{}({})", s.getName(), s.getNumber());
                                        client.registration()
                                                .deregister()
                                                .orTimeout(1, TimeUnit.SECONDS)
                                                .thenRunAsync(
                                                        () -> {
                                                            try {
                                                                log.info("Shutting down...");
                                                                client.webServer().stop();
                                                                client.registration().stop();
                                                                client.executor().shutdown();
                                                            } catch (Exception e) {
                                                                log.warn(
                                                                        "Exception during shutdown",
                                                                        e);
                                                            } finally {
                                                                log.info("Shutdown complete");
                                                                oldHandler.handle(s);
                                                            }
                                                        },
                                                        client.executor());
                                    };
                            Signal.handle(signal, handler);
                        });

        try {
            client.registration().start();
            client.webServer().start();
        } catch (Exception e) {
            log.error(Agent.class.getSimpleName() + " startup failure", e);
            return;
        }
        log.info("Startup complete");
    }

    public static void agentmain(String args) {
        Thread t =
                new Thread(
                        () -> {
                            log.info("Cryostat Agent starting...");
                            main(args == null ? new String[0] : args.split("\\s"));
                        });
        t.setDaemon(true);
        t.setName("cryostat-agent");
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

        ScheduledExecutorService executor();

        @Component.Builder
        interface Builder {
            Client build();
        }
    }
}
