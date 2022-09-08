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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ScheduledExecutorService;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dagger.Lazy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class WebServer {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final ScheduledExecutorService executor;
    private final String host;
    private final int port;
    private final Lazy<Registration> registration;
    private HttpServer http;

    WebServer(
            ScheduledExecutorService executor,
            String host,
            int port,
            Lazy<Registration> registration) {
        this.executor = executor;
        this.host = host;
        this.port = port;
        this.registration = registration;
    }

    public void start() throws IOException {
        if (this.http != null) {
            stop();
        }
        this.http = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(host, port), 0);

        this.http.setExecutor(executor);
        this.http
                .createContext("/")
                .setHandler(
                        new HttpHandler() {
                            @Override
                            public void handle(HttpExchange exchange) throws IOException {
                                String mtd = exchange.getRequestMethod();
                                if ("POST".equals(mtd)) {
                                    executor.execute(registration.get()::tryRegister);
                                }
                                log.trace("{} {}: 204", mtd, exchange.getRequestURI());

                                exchange.sendResponseHeaders(204, -1);
                                exchange.close();
                            }
                        });

        this.http.start();
    }

    public void stop() {
        this.http.stop(0);
        this.http = null;
    }
}
