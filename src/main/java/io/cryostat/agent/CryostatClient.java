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

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Function;

import io.cryostat.agent.FlightRecorderHelper.TemplatedRecording;
import io.cryostat.agent.WebServer.Credentials;
import io.cryostat.agent.harvest.Harvester;
import io.cryostat.agent.model.DiscoveryNode;
import io.cryostat.agent.model.PluginInfo;
import io.cryostat.agent.model.RegistrationInfo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.input.CountingInputStream;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
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

public class CryostatClient {

    private static final String DISCOVERY_API_PATH = "/api/v2.2/discovery";
    private static final String CREDENTIALS_API_PATH = "/api/v2.2/credentials";

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final Executor executor;
    private final ObjectMapper mapper;
    private final HttpClient http;

    private final String appName;
    private final String instanceId;
    private final String jvmId;
    private final URI baseUri;
    private final String realm;

    CryostatClient(
            Executor executor,
            ObjectMapper mapper,
            HttpClient http,
            String instanceId,
            String jvmId,
            String appName,
            URI baseUri,
            String realm) {
        this.executor = executor;
        this.mapper = mapper;
        this.http = http;
        this.instanceId = instanceId;
        this.jvmId = jvmId;
        this.appName = appName;
        this.baseUri = baseUri;
        this.realm = realm;

        log.info("Using Cryostat baseuri {}", baseUri);
    }

    public CompletableFuture<Boolean> checkRegistration(PluginInfo pluginInfo) {
        if (!pluginInfo.isInitialized()) {
            return CompletableFuture.completedFuture(false);
        }
        HttpGet req =
                new HttpGet(
                        baseUri.resolve(
                                DISCOVERY_API_PATH
                                        + "/"
                                        + pluginInfo.getId()
                                        + "?token="
                                        + pluginInfo.getToken()));
        log.info("{}", req);
        return supply(req, (res) -> logResponse(req, res)).thenApply(this::isOkStatus);
    }

    public CompletableFuture<PluginInfo> register(
            int credentialId, PluginInfo pluginInfo, URI callback) {
        try {
            RegistrationInfo registrationInfo =
                    new RegistrationInfo(
                            pluginInfo.getId(), realm, callback, pluginInfo.getToken());
            HttpPost req = new HttpPost(baseUri.resolve(DISCOVERY_API_PATH));
            log.info("{}", req);
            req.setEntity(
                    new StringEntity(
                            mapper.writeValueAsString(registrationInfo),
                            ContentType.APPLICATION_JSON));
            return supply(req, (res) -> logResponse(req, res))
                    .handle(
                            (res, t) -> {
                                if (t != null) {
                                    throw new CompletionException(t);
                                }
                                if (!isOkStatus(res)) {
                                    try {
                                        deleteCredentials(credentialId).get();
                                    } catch (InterruptedException | ExecutionException e) {
                                        log.error("Failed to delete previous credentials", e);
                                    }
                                }
                                return assertOkStatus(req, res);
                            })
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

    public CompletableFuture<Integer> submitCredentialsIfRequired(
            int prevId, Credentials credentials, URI callback) {
        if (prevId < 0) {
            return queryExistingCredentials(callback)
                    .thenCompose(
                            id -> {
                                if (id >= 0) {
                                    return CompletableFuture.completedFuture(id);
                                }
                                return submitCredentials(prevId, credentials, callback);
                            });
        }
        HttpGet req = new HttpGet(baseUri.resolve(CREDENTIALS_API_PATH + "/" + prevId));
        log.info("{}", req);
        return supply(req, (res) -> logResponse(req, res))
                .handle(
                        (v, t) -> {
                            if (t != null) {
                                log.error("Failed to get credentials with ID " + prevId, t);
                                throw new CompletionException(t);
                            }
                            return isOkStatus(v);
                        })
                .thenCompose(
                        exists -> {
                            if (exists) {
                                return CompletableFuture.completedFuture(prevId);
                            }
                            return submitCredentials(prevId, credentials, callback);
                        });
    }

    private CompletableFuture<Integer> queryExistingCredentials(URI callback) {
        HttpGet req = new HttpGet(baseUri.resolve(CREDENTIALS_API_PATH));
        log.info("{}", req);
        return supply(req, (res) -> logResponse(req, res))
                .handle(
                        (res, t) -> {
                            if (t != null) {
                                log.error("Failed to get credentials", t);
                                throw new CompletionException(t);
                            }
                            return assertOkStatus(req, res);
                        })
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
                                        new TypeReference<List<StoredCredential>>() {});
                            } catch (IOException e) {
                                log.error("Unable to parse response as JSON", e);
                                throw new RegistrationException(e);
                            }
                        })
                .thenApply(
                        l ->
                                l.stream()
                                        .filter(
                                                sc ->
                                                        Objects.equals(
                                                                sc.matchExpression,
                                                                selfMatchExpression(callback)))
                                        .map(sc -> sc.id)
                                        .findFirst()
                                        .orElse(-1));
    }

    private CompletableFuture<Integer> submitCredentials(
            int prevId, Credentials credentials, URI callback) {
        HttpPost req = new HttpPost(baseUri.resolve(CREDENTIALS_API_PATH));
        MultipartEntityBuilder entityBuilder =
                MultipartEntityBuilder.create()
                        .addPart(
                                FormBodyPartBuilder.create(
                                                "username",
                                                new StringBody(
                                                        credentials.user(), ContentType.TEXT_PLAIN))
                                        .build())
                        .addPart(
                                FormBodyPartBuilder.create(
                                                "password",
                                                new InputStreamBody(
                                                        new ByteArrayInputStream(
                                                                credentials.pass()),
                                                        ContentType.TEXT_PLAIN))
                                        .build())
                        .addPart(
                                FormBodyPartBuilder.create(
                                                "matchExpression",
                                                new StringBody(
                                                        selfMatchExpression(callback),
                                                        ContentType.TEXT_PLAIN))
                                        .build());
        log.info("{}", req);
        req.setEntity(entityBuilder.build());
        return supply(req, (res) -> logResponse(req, res))
                .thenApply(
                        res -> {
                            if (!isOkStatus(res)) {
                                try {
                                    if (res.getStatusLine().getStatusCode() == 409) {
                                        int queried = queryExistingCredentials(callback).get();
                                        if (queried >= 0) {
                                            return queried;
                                        }
                                    }
                                } catch (InterruptedException | ExecutionException e) {
                                    log.error("Failed to query for existing credentials", e);
                                }
                                try {
                                    deleteCredentials(prevId).get();
                                } catch (InterruptedException | ExecutionException e) {
                                    log.error(
                                            "Failed to delete previous credentials with id "
                                                    + prevId,
                                            e);
                                    throw new RegistrationException(e);
                                }
                            }
                            String location =
                                    assertOkStatus(req, res)
                                            .getFirstHeader(HttpHeaders.LOCATION)
                                            .getValue();
                            String id =
                                    location.substring(
                                            location.lastIndexOf('/') + 1, location.length());
                            return Integer.valueOf(id);
                        });
    }

    public CompletableFuture<Void> deleteCredentials(int id) {
        if (id < 0) {
            return CompletableFuture.completedFuture(null);
        }
        HttpDelete req = new HttpDelete(baseUri.resolve(CREDENTIALS_API_PATH + "/" + id));
        log.info("{}", req);
        return supply(req, (res) -> logResponse(req, res)).thenApply(res -> null);
    }

    public CompletableFuture<Void> deregister(PluginInfo pluginInfo) {
        HttpDelete req =
                new HttpDelete(
                        baseUri.resolve(
                                DISCOVERY_API_PATH
                                        + "/"
                                        + pluginInfo.getId()
                                        + "?token="
                                        + pluginInfo.getToken()));
        log.info("{}", req);
        return supply(req, (res) -> logResponse(req, res))
                .thenApply(res -> assertOkStatus(req, res))
                .thenApply(res -> null);
    }

    public CompletableFuture<Void> update(
            PluginInfo pluginInfo, Collection<DiscoveryNode> subtree) {
        try {
            HttpPost req =
                    new HttpPost(
                            baseUri.resolve(
                                    DISCOVERY_API_PATH
                                            + "/"
                                            + pluginInfo.getId()
                                            + "?token="
                                            + pluginInfo.getToken()));
            req.setEntity(
                    new StringEntity(
                            mapper.writeValueAsString(subtree), ContentType.APPLICATION_JSON));

            log.info("{}", req);
            return supply(req, (res) -> logResponse(req, res))
                    .thenApply(res -> assertOkStatus(req, res))
                    .thenApply(res -> null);
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<Void> upload(
            Harvester.PushType pushType,
            Optional<TemplatedRecording> opt,
            int maxFiles,
            Path recording)
            throws IOException {
        Instant start = Instant.now();
        String timestamp = start.truncatedTo(ChronoUnit.SECONDS).toString().replaceAll("[-:]", "");
        String template =
                opt.map(TemplatedRecording::getConfiguration)
                        .map(Configuration::getName)
                        .map(String::toLowerCase)
                        .map(String::trim)
                        .orElse("unknown");
        String fileName =
                String.format(
                        "%s_%s_%s.jfr",
                        appName
                                + opt.map(TemplatedRecording::getRecording)
                                        .map(Recording::getName)
                                        .map(n -> "-" + n)
                                        .orElse(""),
                        template,
                        timestamp);
        Map<String, String> labels =
                Map.of(
                        "jvmId",
                        jvmId,
                        "pushType",
                        pushType.name(),
                        "template.name",
                        template,
                        "template.type",
                        "TARGET");

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

    private HttpResponse logResponse(HttpRequestBase req, HttpResponse res) {
        log.info("{} {} : {}", req.getMethod(), req.getURI(), res.getStatusLine().getStatusCode());
        return res;
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

    private String selfMatchExpression(URI callback) {
        return String.format(
                "target.connectUrl == \"%s\" && target.annotations.platform[\"INSTANCE_ID\"] =="
                        + " \"%s\"",
                callback, instanceId);
    }

    private boolean isOkStatus(HttpResponse res) {
        int sc = res.getStatusLine().getStatusCode();
        // 2xx is OK, 3xx is redirect range so allow those too
        return 200 <= sc && sc < 400;
    }

    private HttpResponse assertOkStatus(HttpRequestBase req, HttpResponse res) {
        int sc = res.getStatusLine().getStatusCode();
        if (!isOkStatus(res)) {
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

    @SuppressFBWarnings(
            value = {
                "URF_UNREAD_FIELD",
                "UWF_UNWRITTEN_FIELD",
                "UWF_UNWRITTEN_PUBLIC_OR_PROTECTED_FIELD"
            })
    public static class StoredCredential {

        public int id;
        public String matchExpression;

        @Override
        public int hashCode() {
            return Objects.hash(id, matchExpression);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            StoredCredential other = (StoredCredential) obj;
            return id == other.id && Objects.equals(matchExpression, other.matchExpression);
        }
    }
}
