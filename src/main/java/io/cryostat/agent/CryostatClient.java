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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.cryostat.agent.model.DiscoveryNode;
import io.cryostat.agent.model.PluginInfo;
import io.cryostat.agent.model.RegistrationInfo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class CryostatClient {

    private static final String API_PATH = "/api/v2.2/discovery";

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final URI baseUri;
    private final URI callback;
    private final String realm;
    private final String authorization;

    CryostatClient(HttpClient http, URI baseUri, URI callback, String realm, String authorization) {
        this.http = http;
        this.baseUri = baseUri;
        this.callback = callback;
        this.realm = realm;
        this.authorization = authorization;
        this.mapper = new ObjectMapper();

        log.info("Using Cryostat baseuri {}", baseUri);
    }

    CompletableFuture<PluginInfo> register(PluginInfo pluginInfo) {
        RegistrationInfo registrationInfo =
                new RegistrationInfo(pluginInfo.getId(), realm, callback, pluginInfo.getToken());
        HttpRequest req;
        try {
            req =
                    HttpRequest.newBuilder(baseUri.resolve(API_PATH))
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            mapper.writeValueAsString(registrationInfo)))
                            .setHeader("Authorization", authorization)
                            .timeout(Duration.ofSeconds(1))
                            .build();
            log.trace("{}", req);
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
        return http.sendAsync(req, BodyHandlers.ofInputStream())
                .thenApply(
                        res -> {
                            log.trace(
                                    "{} {} : {}",
                                    res.request().method(),
                                    res.request().uri(),
                                    res.statusCode());
                            return res;
                        })
                .thenApply(this::assertOkStatus)
                .thenApply(
                        resp -> {
                            try {
                                return mapper.readValue(resp.body(), ObjectNode.class);
                            } catch (IOException e) {
                                e.printStackTrace();
                                throw new RuntimeException(e);
                            }
                        })
                .thenApply(
                        node -> {
                            try {
                                return mapper.readValue(
                                        node.get("data").get("result").toString(),
                                        PluginInfo.class);
                            } catch (IOException e) {
                                e.printStackTrace();
                                throw new RuntimeException(e);
                            }
                        });
    }

    CompletableFuture<Void> deregister(PluginInfo pluginInfo) {
        HttpRequest req =
                HttpRequest.newBuilder(
                                baseUri.resolve(
                                        API_PATH
                                                + "/"
                                                + pluginInfo.getId()
                                                + "?token="
                                                + pluginInfo.getToken()))
                        .DELETE()
                        .timeout(Duration.ofSeconds(1))
                        .build();
        log.trace("{}", req);
        return http.sendAsync(req, BodyHandlers.discarding())
                .thenApply(
                        res -> {
                            log.trace(
                                    "{} {} : {}",
                                    res.request().method(),
                                    res.request().uri(),
                                    res.statusCode());
                            return res;
                        })
                .thenApply(this::assertOkStatus)
                .thenApply(res -> null);
    }

    CompletableFuture<Void> update(PluginInfo pluginInfo, Set<DiscoveryNode> subtree) {
        HttpRequest req;
        try {
            req =
                    HttpRequest.newBuilder(
                                    baseUri.resolve(
                                            API_PATH
                                                    + "/"
                                                    + pluginInfo.getId()
                                                    + "?token="
                                                    + pluginInfo.getToken()))
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            mapper.writeValueAsString(subtree)))
                            .setHeader("Authorization", authorization)
                            .timeout(Duration.ofSeconds(1))
                            .build();
            log.trace("{}", req);
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
        return http.sendAsync(req, BodyHandlers.discarding())
                .thenApply(
                        res -> {
                            log.trace(
                                    "{} {} : {}",
                                    res.request().method(),
                                    res.request().uri(),
                                    res.statusCode());
                            return res;
                        })
                .thenApply(this::assertOkStatus)
                .thenApply(res -> null);
    }

    private <T> HttpResponse<T> assertOkStatus(HttpResponse<T> res) {
        int sc = res.statusCode();
        boolean isOk = 200 <= sc && sc < 300;
        if (isOk) {
            return res;
        }
        throw new RuntimeException(String.format("HTTP API %d", sc));
    }
}
