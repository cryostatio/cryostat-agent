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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import javax.net.ssl.SSLContext;

import io.cryostat.agent.model.DiscoveryNode;
import io.cryostat.agent.model.PluginInfo;
import io.cryostat.agent.model.RegistrationInfo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.input.CountingInputStream;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.FormBodyPartBuilder;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.InputStreamBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CryostatClient {

    private static final String API_PATH = "/api/v2.2/discovery";

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final SSLContext sslCtx;
    private final HttpClient http;
    private final ObjectMapper mapper;

    private final String appName;
    private final String jvmId;
    private final URI baseUri;
    private final URI callback;
    private final String realm;
    private final String authorization;
    private final long responseTimeoutMs;
    private final long uploadTimeoutMs;

    CryostatClient(
            SSLContext sslCtx,
            HttpClient http,
            ObjectMapper mapper,
            String jvmId,
            String appName,
            URI baseUri,
            URI callback,
            String realm,
            String authorization,
            long responseTimeoutMs,
            long uploadTimeoutMs) {
        this.sslCtx = sslCtx;
        this.http = http;
        this.mapper = mapper;
        this.jvmId = jvmId;
        this.appName = appName;
        this.baseUri = baseUri;
        this.callback = callback;
        this.realm = realm;
        this.authorization = authorization;
        this.responseTimeoutMs = responseTimeoutMs;
        this.uploadTimeoutMs = uploadTimeoutMs;

        log.info("Using Cryostat baseuri {}", baseUri);
    }

    public CompletableFuture<PluginInfo> register(PluginInfo pluginInfo) {
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
                            .timeout(Duration.ofMillis(responseTimeoutMs))
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
                                log.error("Unable to parse response as JSON", e);
                                throw new RegistrationException(e);
                            }
                        })
                .thenApply(
                        node -> {
                            try {
                                return mapper.readValue(
                                        node.get("data").get("result").toString(),
                                        PluginInfo.class);
                            } catch (IOException e) {
                                log.error("Unable to parse response as JSON", e);
                                throw new RegistrationException(e);
                            }
                        });
    }

    public CompletableFuture<Void> deregister(PluginInfo pluginInfo) {
        HttpRequest req =
                HttpRequest.newBuilder(
                                baseUri.resolve(
                                        API_PATH
                                                + "/"
                                                + pluginInfo.getId()
                                                + "?token="
                                                + pluginInfo.getToken()))
                        .DELETE()
                        .timeout(Duration.ofMillis(responseTimeoutMs))
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

    public CompletableFuture<Void> update(PluginInfo pluginInfo, Set<DiscoveryNode> subtree) {
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
                            .timeout(Duration.ofMillis(responseTimeoutMs))
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
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<Void> upload(
            Harvester.PushType pushType, String template, int maxFiles, Path recording)
            throws IOException {
        try (CloseableHttpClient ahttp =
                HttpClients.custom()
                        .setSSLContext(sslCtx)
                        .setSSLHostnameVerifier((hostname, session) -> true)
                        .setDefaultHeaders(Set.of(new BasicHeader("Authorization", authorization)))
                        .build()) {
            Instant start = Instant.now();
            String timestamp =
                    start.truncatedTo(ChronoUnit.SECONDS).toString().replaceAll("[-:]", "");
            String fileName = String.format("%s_%s_%s.jfr", appName, template, timestamp);
            Map<String, String> labels =
                    Map.of(
                            "jvmId",
                            jvmId,
                            "template.name",
                            template,
                            "template.type",
                            "TARGET",
                            "pushType",
                            pushType.name());

            HttpPost req = new HttpPost(baseUri.resolve("/api/beta/recordings/" + jvmId));
            req.setConfig(
                    RequestConfig.custom()
                            .setExpectContinueEnabled(true)
                            .setAuthenticationEnabled(true)
                            .build());

            CountingInputStream is = getRecordingInputStream(recording);
            MultipartEntityBuilder entityBuilder =
                    MultipartEntityBuilder.create()
                            .setMode(HttpMultipartMode.RFC6532)
                            .addPart(
                                    FormBodyPartBuilder.create(
                                                    "recording",
                                                    new InputStreamBody(
                                                            is,
                                                            ContentType.APPLICATION_OCTET_STREAM,
                                                            fileName))
                                            .build())
                            .addPart(
                                    FormBodyPartBuilder.create(
                                                    "labels",
                                                    new StringBody(
                                                            mapper.writeValueAsString(labels),
                                                            ContentType.APPLICATION_JSON))
                                            .build())
                            .addPart(
                                    FormBodyPartBuilder.create(
                                                    "maxFiles",
                                                    new StringBody(
                                                            Integer.toString(maxFiles),
                                                            ContentType.TEXT_PLAIN))
                                            .build());
            req.setEntity(entityBuilder.build());
            try (CloseableHttpResponse res = ahttp.execute(req)) {
                Instant finish = Instant.now();
                log.info(
                        "{} {} ({} -> {}): {}/{}",
                        req.getMethod(),
                        res.getStatusLine().getStatusCode(),
                        fileName,
                        req.getURI(),
                        FileUtils.byteCountToDisplaySize(is.getByteCount()),
                        Duration.between(start, finish));
                assertOkStatus(req, res);
                return CompletableFuture.completedFuture(null);
            } finally {
                req.releaseConnection();
            }
        } catch (IOException e) {
            log.error("Upload failure", e);
            throw e;
        }
    }

    private CountingInputStream getRecordingInputStream(Path filePath) throws IOException {
        return new CountingInputStream(new BufferedInputStream(Files.newInputStream(filePath)));
    }

    private <T> HttpResponse<T> assertOkStatus(HttpResponse<T> res) {
        int sc = res.statusCode();
        boolean isOk = 200 <= sc && sc < 300;
        if (!isOk) {
            log.error("Non-OK response ({}) on HTTP API {}", sc, res.request().uri());
            URI uri = res.request().uri();
            try {
                throw new HttpException(
                        sc,
                        new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null));
            } catch (URISyntaxException use) {
                throw new IllegalStateException(use);
            }
        }
        return res;
    }

    private void assertOkStatus(HttpRequestBase req, CloseableHttpResponse res) {
        int sc = res.getStatusLine().getStatusCode();
        boolean isOk = 200 <= sc && sc < 300;
        if (!isOk) {
            URI uri = req.getURI();
            log.error("Non-OK response ({}) on HTTP API {}", sc, uri);
            try {
                throw new HttpException(
                        sc,
                        new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null));
            } catch (URISyntaxException use) {
                throw new IllegalStateException(use);
            }
        }
    }
}
