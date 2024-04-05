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
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import java.util.zip.DeflaterOutputStream;

import io.cryostat.agent.remote.RemoteContext;

import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dagger.Lazy;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class WebServer {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final Lazy<Set<RemoteContext>> remoteContexts;
    private final Lazy<CryostatClient> cryostat;
    private final ScheduledExecutorService executor;
    private final String host;
    private final int port;
    private final Credentials credentials;
    private final URI callback;
    private final Lazy<Registration> registration;
    private HttpServer http;
    private volatile int credentialId = -1;

    private final AgentAuthenticator agentAuthenticator;
    private final RequestLoggingFilter requestLoggingFilter;
    private final CompressionFilter compressionFilter;

    WebServer(
            Lazy<Set<RemoteContext>> remoteContexts,
            Lazy<CryostatClient> cryostat,
            ScheduledExecutorService executor,
            String host,
            int port,
            MessageDigest digest,
            String user,
            int passLength,
            URI callback,
            Lazy<Registration> registration) {
        this.remoteContexts = remoteContexts;
        this.cryostat = cryostat;
        this.executor = executor;
        this.host = host;
        this.port = port;
        this.credentials = new Credentials(digest, user, passLength);
        this.callback = callback;
        this.registration = registration;

        this.agentAuthenticator = new AgentAuthenticator();
        this.requestLoggingFilter = new RequestLoggingFilter();
        this.compressionFilter = new CompressionFilter();
    }

    void start() throws IOException {
        if (this.http != null) {
            stop();
        }

        this.http = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.http.setExecutor(executor);

        Set<RemoteContext> mergedContexts = new HashSet<>(remoteContexts.get());
        mergedContexts.add(new PingContext(registration));
        mergedContexts.stream()
                .filter(RemoteContext::available)
                .forEach(
                        rc -> {
                            HttpContext ctx = this.http.createContext(rc.path(), wrap(rc::handle));
                            ctx.setAuthenticator(agentAuthenticator);
                            ctx.getFilters().add(requestLoggingFilter);
                            ctx.getFilters().add(compressionFilter);
                        });

        this.http.start();
    }

    void stop() {
        if (this.http != null) {
            this.http.stop(0);
            this.http = null;
        }
    }

    int getCredentialId() {
        return credentialId;
    }

    void resetCredentialId() {
        this.credentialId = -1;
    }

    CompletableFuture<Void> generateCredentials() {
        this.credentials.regenerate();
        return this.cryostat
                .get()
                .submitCredentialsIfRequired(this.credentialId, this.credentials, this.callback)
                .handle(
                        (v, t) -> {
                            this.credentials.clear();
                            if (t != null) {
                                this.resetCredentialId();
                                log.error("Could not submit credentials", t);
                                throw new CompletionException("Could not submit credentials", t);
                            }
                            return v;
                        })
                .thenAccept(
                        i -> {
                            this.credentialId = i;
                            log.info("Defined credentials with id {}", i);
                        });
    }

    private HttpHandler wrap(HttpHandler handler) {
        return x -> {
            try {
                handler.handle(x);
            } catch (Exception e) {
                log.error("Unhandled exception", e);
                x.sendResponseHeaders(
                        HttpStatus.SC_INTERNAL_SERVER_ERROR, RemoteContext.BODY_LENGTH_NONE);
                x.close();
            }
        };
    }

    private class PingContext implements RemoteContext {

        private final Lazy<Registration> registration;

        PingContext(Lazy<Registration> registration) {
            this.registration = registration;
        }

        @Override
        public String path() {
            return "/";
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String mtd = exchange.getRequestMethod();
                switch (mtd) {
                    case "POST":
                        synchronized (WebServer.this.credentials) {
                            exchange.sendResponseHeaders(
                                    HttpStatus.SC_NO_CONTENT, BODY_LENGTH_NONE);
                            this.registration
                                    .get()
                                    .notify(Registration.RegistrationEvent.State.REFRESHING);
                        }
                        break;
                    case "GET":
                        exchange.sendResponseHeaders(HttpStatus.SC_NO_CONTENT, BODY_LENGTH_NONE);
                        break;
                    default:
                        log.warn("Unknown request method {}", mtd);
                        exchange.sendResponseHeaders(
                                HttpStatus.SC_METHOD_NOT_ALLOWED, BODY_LENGTH_NONE);
                        break;
                }
            } finally {
                exchange.close();
            }
        }
    }

    private class RequestLoggingFilter extends Filter {
        @Override
        public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
            long start = System.nanoTime();
            String requestMethod = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            log.info("{} {}", requestMethod, path);
            chain.doFilter(exchange);
            long elapsed = System.nanoTime() - start;
            log.info(
                    "{} {} : {} {}ms",
                    requestMethod,
                    path,
                    exchange.getResponseCode(),
                    Duration.ofNanos(elapsed).toMillis());
        }

        @Override
        public String description() {
            return "requestLog";
        }
    }

    private class CompressionFilter extends Filter {

        @Override
        public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
            List<String> requestedEncodings =
                    exchange.getRequestHeaders().getOrDefault("Accept-Encoding", List.of()).stream()
                            .map(raw -> raw.replaceAll("\\s", ""))
                            .map(raw -> raw.split(","))
                            .map(Arrays::asList)
                            .flatMap(List::stream)
                            .collect(Collectors.toList());
            String negotiatedEncoding = null;
            priority:
            for (String encoding : requestedEncodings) {
                switch (encoding) {
                    case "deflate":
                        negotiatedEncoding = encoding;
                        exchange.setStreams(
                                exchange.getRequestBody(),
                                new DeflaterOutputStream(exchange.getResponseBody()));
                        break priority;
                        // TODO gzip encoding breaks communication with the server, need to
                        // determine why and re-enable this
                        // case "gzip":
                        // actualEncoding = requestedEncoding;
                        // exchange.setStreams(
                        //         exchange.getRequestBody(),
                        //         new GZIPOutputStream(exchange.getResponseBody()));
                        // break priority;
                    default:
                        break;
                }
            }
            if (negotiatedEncoding == null) {
                log.info("Using no encoding");
            } else {
                log.info("Using '{}' encoding", negotiatedEncoding);
                exchange.getResponseHeaders().put("Content-Encoding", List.of(negotiatedEncoding));
            }
            chain.doFilter(exchange);
        }

        @Override
        public String description() {
            return "responseCompression";
        }
    }

    private class AgentAuthenticator extends BasicAuthenticator {

        public AgentAuthenticator() {
            super("cryostat-agent");
        }

        @Override
        public boolean checkCredentials(String username, String password) {
            return WebServer.this.credentials.checkUserInfo(username, password);
        }
    }

    static class Credentials {

        private final SecureRandom random = new SecureRandom();
        private final MessageDigest digest;
        private final String user;
        private final byte[] pass;
        private byte[] passHash = new byte[0];

        Credentials(MessageDigest digest, String user, int passLength) {
            this.digest = digest;
            this.user = user;
            this.pass = new byte[passLength];
        }

        synchronized boolean checkUserInfo(String username, String password) {
            return passHash.length > 0
                    && Objects.equals(username, user)
                    && Arrays.equals(hash(password), this.passHash);
        }

        synchronized void regenerate() {
            for (int idx = 0; idx < this.pass.length; idx++) {
                this.pass[idx] = randomAscii();
            }
            this.passHash = hash(this.pass);
        }

        String user() {
            return user;
        }

        synchronized byte[] pass() {
            return pass;
        }

        synchronized void clear() {
            Arrays.fill(this.pass, (byte) 0);
        }

        private byte randomAscii() {
            // ASCII printable characters range from 33 to 126. Other values are null, whitespace,
            // and various control characters
            char start = (char) 33;
            char end = (char) 126;
            int diff = end - start;
            return (byte) (random.nextInt(diff + 1) + start);
        }

        private byte[] hash(String pass) {
            return hash(pass.getBytes(StandardCharsets.US_ASCII));
        }

        private byte[] hash(byte[] bytes) {
            return digest.digest(bytes);
        }
    }
}
