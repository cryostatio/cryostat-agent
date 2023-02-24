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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import io.cryostat.agent.Registration.RegistrationEvent;

import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dagger.Lazy;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class WebServer implements Consumer<RegistrationEvent> {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final Lazy<CryostatClient> cryostat;
    private final ScheduledExecutorService executor;
    private final String host;
    private final int port;
    private final Credentials credentials;
    private final Lazy<Registration> registration;
    private HttpServer http;

    WebServer(
            Lazy<CryostatClient> cryostat,
            ScheduledExecutorService executor,
            String host,
            int port,
            Lazy<Registration> registration) {
        this.cryostat = cryostat;
        this.executor = executor;
        this.host = host;
        this.port = port;
        this.credentials = new Credentials();
        this.registration = registration;
    }

    void start() throws IOException, NoSuchAlgorithmException {
        if (this.http != null) {
            stop();
        }

        this.generateCredentials();

        this.http = HttpServer.create(new InetSocketAddress(host, port), 0);

        this.http.setExecutor(executor);

        HttpContext pingCtx =
                this.http.createContext(
                        "/",
                        new HttpHandler() {
                            @Override
                            public void handle(HttpExchange exchange) throws IOException {
                                String mtd = exchange.getRequestMethod();
                                switch (mtd) {
                                    case "POST":
                                        synchronized (WebServer.this.credentials) {
                                            executor.execute(registration.get()::tryRegister);
                                            exchange.sendResponseHeaders(
                                                    HttpStatus.SC_NO_CONTENT, -1);
                                            exchange.close();
                                        }
                                        break;
                                    case "GET":
                                        exchange.sendResponseHeaders(HttpStatus.SC_NO_CONTENT, -1);
                                        exchange.close();
                                        break;
                                    default:
                                        exchange.sendResponseHeaders(HttpStatus.SC_NOT_FOUND, -1);
                                        exchange.close();
                                        break;
                                }
                            }
                        });
        pingCtx.setAuthenticator(new AgentAuthenticator());
        pingCtx.getFilters()
                .add(
                        Filter.beforeHandler(
                                "beforeLog",
                                exchange ->
                                        log.info(
                                                "{} {}",
                                                exchange.getRequestMethod(),
                                                exchange.getRequestURI().getPath())));
        pingCtx.getFilters()
                .add(
                        Filter.afterHandler(
                                "afterLog",
                                exchange ->
                                        log.info(
                                                "{} {} : {}",
                                                exchange.getRequestMethod(),
                                                exchange.getRequestURI().getPath(),
                                                exchange.getResponseCode())));

        this.registration.get().addRegistrationListener(this);

        this.http.start();
    }

    void stop() {
        if (this.http != null) {
            this.http.stop(0);
            this.http = null;
        }
    }

    Credentials getCredentials() {
        return credentials;
    }

    void generateCredentials() throws NoSuchAlgorithmException {
        synchronized (this.credentials) {
            this.credentials.regenerate();
            this.cryostat
                    .get()
                    .submitCredentials(this.credentials)
                    .thenAccept(i -> log.info("Defined credentials with id {}", i));
        }
    }

    @Override
    public void accept(RegistrationEvent t) {
        switch (t.state) {
            case PUBLISHED:
                // once we have successfully registered and published, the Cryostat server has been
                // fully informed of our presence and the credentials we expect to be presented on
                // HTTP API communications back to us. So, we clear these credentials out of memory
                // - the username and a hash of the password are held, but the password itself is
                // dropped.
                this.credentials.clear();
                break;
            case REFRESHED:
                break;
            case UNREGISTERED:
                break;
            case REGISTERED:
                break;
            default:
                break;
        }
    }

    private class AgentAuthenticator extends BasicAuthenticator {

        private final Logger log = LoggerFactory.getLogger(getClass());

        public AgentAuthenticator() {
            super("cryostat-agent");
        }

        @Override
        public synchronized boolean checkCredentials(String username, String password) {
            try {
                return WebServer.this.credentials.checkUserInfo(username, password);
            } catch (NoSuchAlgorithmException e) {
                log.error("Could not check credentials", e);
                return false;
            }
        }
    }

    static class Credentials {

        static final String user = "agent";
        byte[] passHash;
        char[] pass;

        synchronized boolean checkUserInfo(String username, String password)
                throws NoSuchAlgorithmException {
            byte[] passHash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(password.getBytes(StandardCharsets.UTF_8));
            return Objects.equals(username, Credentials.user)
                    && Arrays.equals(passHash, this.passHash);
        }

        synchronized void regenerate() throws NoSuchAlgorithmException {
            String set = "abcdefghijklmnopqrstuvwxyz-_=[].0123456789";
            String pass =
                    RandomStringUtils.random(
                            16, 0, set.length(), true, true, set.toCharArray(), new SecureRandom());
            this.pass = pass.toCharArray();
            this.passHash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(pass.getBytes(StandardCharsets.UTF_8));
        }

        synchronized void clear() {
            if (pass != null) {
                for (int i = 0; i < pass.length; i++) {
                    pass[i] = '\0';
                }
                pass = null;
            }
        }
    }
}
