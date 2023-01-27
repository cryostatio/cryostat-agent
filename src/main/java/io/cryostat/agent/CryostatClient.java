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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.CompletableFuture;

import io.cryostat.agent.model.DiscoveryNode;
import io.cryostat.agent.model.PluginInfo;
import io.cryostat.agent.model.RegistrationInfo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.input.CountingInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CryostatClient {

    private static final String API_PATH = "/api/v2.2/discovery";

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final ObjectMapper mapper;
    private final HttpClient http;

    private final String appName;
    private final String jvmId;
    private final URI baseUri;
    private final URI callback;
    private final String realm;
    private final String authorization;
    private final long responseTimeoutMs;
    private final long uploadTimeoutMs;

    CryostatClient(
            HttpClient http,
            String jvmId,
            String appName,
            URI baseUri,
            URI callback,
            String realm,
            String authorization,
            long responseTimeoutMs,
            long uploadTimeoutMs) {
        this.http = http;
        this.jvmId = jvmId;
        this.appName = appName;
        this.baseUri = baseUri;
        this.callback = callback;
        this.realm = realm;
        this.authorization = authorization;
        this.responseTimeoutMs = responseTimeoutMs;
        this.uploadTimeoutMs = uploadTimeoutMs;
        this.mapper = new ObjectMapper();

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
        String boundary = new BigInteger(256, new Random()).toString();
        CountingInputStream is = getRecordingInputStream(recording);
        HttpRequest req =
                HttpRequest.newBuilder(baseUri.resolve("/api/beta/recordings/" + jvmId))
                        .POST(ofMultipartData(boundary, is, fileName, labels, maxFiles))
                        .setHeader("Authorization", authorization)
                        .setHeader(
                                "Content-Type",
                                String.format("multipart/form-data; boundary=%s", boundary))
                        .timeout(Duration.ofMillis(uploadTimeoutMs))
                        .build();
        log.trace("{}", req);
        return http.sendAsync(req, BodyHandlers.discarding())
                .thenApply(
                        res -> {
                            Instant finish = Instant.now();
                            log.info(
                                    "{} {} ({} -> {}): {}/{}",
                                    res.request().method(),
                                    res.statusCode(),
                                    fileName,
                                    res.request().uri(),
                                    FileUtils.byteCountToDisplaySize(is.getByteCount()),
                                    Duration.between(start, finish));
                            return res;
                        })
                .thenApply(this::assertOkStatus)
                .thenApply(res -> null);
    }

    private CountingInputStream getRecordingInputStream(Path filePath) throws IOException {
        return new CountingInputStream(new BufferedInputStream(Files.newInputStream(filePath)));
    }

    @SuppressFBWarnings("VA_FORMAT_STRING_USES_NEWLINE")
    private HttpRequest.BodyPublisher ofMultipartData(
            String boundary,
            InputStream stream,
            String uploadName,
            Map<String, String> labels,
            int maxFiles)
            throws IOException {
        byte[] newline = new byte[] {'\r', '\n'};
        String separator = "--" + boundary;

        Vector<InputStream> parts = new Vector<>();

        // recording file
        {
            parts.add(asStream(separator));
            parts.add(asStream(newline));

            parts.add(
                    asStream(
                            String.format(
                                    "Content-Disposition: form-data; name=\"recording\";"
                                            + " filename=\"%s\"",
                                    uploadName)));
            parts.add(asStream(newline));
            parts.add(asStream("Content-Type: application/octet-stream"));

            parts.add(asStream(newline));
            parts.add(asStream(newline));
            parts.add(stream);
            parts.add(asStream(newline));
        }

        // recording labels
        {
            parts.add(asStream(separator));
            parts.add(asStream(newline));

            parts.add(asStream(("Content-Disposition: form-data; name=\"labels\"")));

            parts.add(asStream(newline));
            parts.add(asStream(newline));
            parts.add(asStream(mapper.writeValueAsBytes(labels)));
            parts.add(asStream(newline));
        }

        // maxFiles
        {
            parts.add(asStream(separator));
            parts.add(asStream(newline));

            parts.add(asStream(("Content-Disposition: form-data; name=\"maxFiles\"")));

            parts.add(asStream(newline));
            parts.add(asStream(newline));
            parts.add(asStream(maxFiles));
            parts.add(asStream(newline));
        }

        String endBoundary = ("--" + boundary + "--");
        parts.add(asStream(endBoundary));

        return HttpRequest.BodyPublishers.ofInputStream(
                () -> new SequenceInputStream(parts.elements()));
    }

    private static InputStream asStream(byte[] arr) {
        return new ByteArrayInputStream(arr);
    }

    private static InputStream asStream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static InputStream asStream(int i) {
        return new ByteArrayInputStream(ByteBuffer.allocate(4).putInt(i).array());
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
}
