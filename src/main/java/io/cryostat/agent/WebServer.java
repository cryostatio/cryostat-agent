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
import java.net.URI;
import java.net.URISyntaxException;
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
import org.apache.http.client.utils.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class WebServer implements Consumer<RegistrationEvent> {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final ScheduledExecutorService executor;
    private final String host;
    private final int port;
    private Credentials credentials;
    private final URI callback;
    private final Lazy<Registration> registration;
    private HttpServer http;

    WebServer(
            ScheduledExecutorService executor,
            String host,
            int port,
            URI callback,
            Lazy<Registration> registration) {
        this.executor = executor;
        this.host = host;
        this.port = port;
        this.callback = callback;
        this.registration = registration;
    }

    URI getCallback() throws URISyntaxException {
        return new URIBuilder(callback)
                .setUserInfo(credentials.user, new String(credentials.pass))
                .build();
    }

    void start() throws IOException, NoSuchAlgorithmException {
        if (this.http != null) {
            stop();
        }
        this.http = HttpServer.create(new InetSocketAddress(host, port), 0);

        this.http.setExecutor(executor);
        this.credentials = Authenticators.INSTANCE.authenticator.regenerate();

        HttpContext pingCtx =
                this.http.createContext(
                        "/",
                        new HttpHandler() {
                            @Override
                            public void handle(HttpExchange exchange) throws IOException {
                                String mtd = exchange.getRequestMethod();
                                switch (mtd) {
                                    case "POST":
                                        executor.execute(registration.get()::tryRegister);
                                        exchange.sendResponseHeaders(HttpStatus.SC_NO_CONTENT, -1);
                                        exchange.close();
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
        pingCtx.setAuthenticator(Authenticators.INSTANCE.authenticator);
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
            case UNREGISTERED:
                break;
            case REFRESHED:
                break;
            case REGISTERED:
                break;
            default:
                break;
        }
    }

    private enum Authenticators {
        INSTANCE(new AgentAuthenticator());

        private final AgentAuthenticator authenticator;

        Authenticators(AgentAuthenticator authenticator) {
            this.authenticator = authenticator;
        }
    }

    private static class AgentAuthenticator extends BasicAuthenticator {

        private final Logger log = LoggerFactory.getLogger(getClass());
        private final MessageDigest md;
        private String user;
        private byte[] passHash;

        public AgentAuthenticator() {
            super("cryostat-agent");
            try {
                this.md = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException nsae) {
                log.error("No such algorithm", nsae);
                throw new RuntimeException(nsae);
            }
        }

        @Override
        public synchronized boolean checkCredentials(String username, String password) {
            byte[] passHash = md.digest(password.getBytes(StandardCharsets.UTF_8));
            return Objects.equals(username, this.user) && Arrays.equals(passHash, this.passHash);
        }

        private synchronized Credentials regenerate() {
            String user = "agent";
            String set = "abcdefghijklmnopqrstuvwxyz-_=[].0123456789";
            String pass =
                    RandomStringUtils.random(
                            16, 0, set.length(), true, true, set.toCharArray(), new SecureRandom());
            Credentials c = new Credentials(user, pass.toCharArray());
            this.user = user;
            this.passHash = md.digest(pass.getBytes(StandardCharsets.UTF_8));
            return c;
        }
    }

    static class Credentials {
        String user;
        char[] pass;

        Credentials(String user, char[] pass) {
            this.user = user;
            this.pass = pass;
        }

        void clear() {
            if (pass != null) {
                for (int i = 0; i < pass.length; i++) {
                    pass[i] = '\0';
                }
                pass = null;
            }
        }
    }
}
