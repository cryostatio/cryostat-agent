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
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.input.CountingInputStream;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.FormBodyPartBuilder;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.InputStreamBody;
import org.apache.http.entity.mime.content.StringBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.cryostat.agent.model.DiscoveryNode;
import io.cryostat.agent.model.PluginInfo;
import io.cryostat.agent.model.RegistrationInfo;

public class CryostatClient {

    private static final String API_PATH = "/api/v2.2/discovery";

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final Executor executor;
    private final ObjectMapper mapper;
    private final HttpClient http;

    private final String appName;
    private final String jvmId;
    private final URI baseUri;
    private final URI callback;
    private final String realm;

    CryostatClient(
            Executor executor,
            HttpClient http,
            ObjectMapper mapper,
            String jvmId,
            String appName,
            URI baseUri,
            URI callback,
            String realm) {
        this.executor = executor;
        this.http = http;
        this.mapper = mapper;
        this.jvmId = jvmId;
        this.appName = appName;
        this.baseUri = baseUri;
        this.callback = callback;
        this.realm = realm;
        this.mapper = new ObjectMapper();

        log.info("Using Cryostat baseuri {}", baseUri);
    }

    public CompletableFuture<PluginInfo> register(PluginInfo pluginInfo) {
        RegistrationInfo registrationInfo =
                new RegistrationInfo(pluginInfo.getId(), realm, callback, pluginInfo.getToken());
        try {
            HttpPost req = new HttpPost(baseUri.resolve(API_PATH));
            req.setEntity(
                    new StringEntity(
                            mapper.writeValueAsString(registrationInfo),
                            ContentType.APPLICATION_JSON));
            log.info("{}", req);
            return supply(
                            req,
                            (res) -> {
                                log.info(
                                        "{} {} : {}",
                                        req.getMethod(),
                                        req.getURI(),
                                        res.getStatusLine().getStatusCode());
                                return res;
                            })
                    .thenApply(res -> assertOkStatus(req, res))
                    .thenApply(
                            res -> {
                                try (InputStream is = res.getEntity().getContent()) {
                                    return mapper.readValue(is, ObjectNode.class);
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
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<Void> deregister(PluginInfo pluginInfo) {
        HttpDelete req =
                new HttpDelete(
                        baseUri.resolve(
                                API_PATH
                                        + "/"
                                        + pluginInfo.getId()
                                        + "?token="
                                        + pluginInfo.getToken()));
        log.info("{}", req);
        return supply(
                        req,
                        (res) -> {
                            log.info(
                                    "{} {} : {}",
                                    req.getMethod(),
                                    req.getURI(),
                                    res.getStatusLine().getStatusCode());
                            return res;
                        })
                .thenApply(res -> assertOkStatus(req, res))
                .thenApply(res -> null);
    }

    public CompletableFuture<Void> update(PluginInfo pluginInfo, Set<DiscoveryNode> subtree) {
        try {
            HttpPost req =
                    new HttpPost(
                            baseUri.resolve(
                                    API_PATH
                                            + "/"
                                            + pluginInfo.getId()
                                            + "?token="
                                            + pluginInfo.getToken()));
            req.setEntity(
                    new StringEntity(
                            mapper.writeValueAsString(subtree), ContentType.APPLICATION_JSON));

            log.info("{}", req);
            return supply(
                            req,
                            (res) -> {
                                log.info(
                                        "{} {} : {}",
                                        req.getMethod(),
                                        req.getURI(),
                                        res.getStatusLine().getStatusCode());
                                return res;
                            })
                    .thenApply(res -> assertOkStatus(req, res))
                    .thenApply(res -> null);
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<Void> upload(
            Harvester.PushType pushType, String template, int maxFiles, Path recording)
            throws IOException {
        Instant start = Instant.now();
        String timestamp = start.truncatedTo(ChronoUnit.SECONDS).toString().replaceAll("[-:]", "");
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

        CountingInputStream is = getRecordingInputStream(recording);
        MultipartEntityBuilder entityBuilder =
                MultipartEntityBuilder.create()
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
        return supply(
                req,
                (res) -> {
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
                    return (Void) null;
                });
    }

    private <T> CompletableFuture<T> supply(HttpRequestBase req, Function<HttpResponse, T> fn) {
        return CompletableFuture.supplyAsync(() -> fn.apply(executeQuiet(req)), executor)
                .whenComplete((v, t) -> req.reset());
    }

    private HttpResponse executeQuiet(HttpUriRequest req) {
        try {
            return http.execute(req);
        } catch (IOException ioe) {
            throw new CompletionException(ioe);
        }
    }

    private CountingInputStream getRecordingInputStream(Path filePath) throws IOException {
        return new CountingInputStream(new BufferedInputStream(Files.newInputStream(filePath)));
    }

    private HttpResponse assertOkStatus(HttpRequestBase req, HttpResponse res) {
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
        return res;
    }
}
