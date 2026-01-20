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
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Function;

import io.cryostat.agent.FlightRecorderHelper.ConfigurationInfo;
import io.cryostat.agent.FlightRecorderHelper.TemplatedRecording;
import io.cryostat.agent.WebServer.Credentials;
import io.cryostat.agent.harvest.Harvester;
import io.cryostat.agent.model.DiscoveryNode;
import io.cryostat.agent.model.PluginInfo;
import io.cryostat.agent.model.RegistrationInfo;
import io.cryostat.agent.model.ServerHealth;
import io.cryostat.agent.triggers.TriggerEvaluator.SmartTriggerUpdate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jdk.jfr.Recording;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.input.CountingInputStream;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.entity.mime.ByteArrayBody;
import org.apache.hc.client5.http.entity.mime.FormBodyPartBuilder;
import org.apache.hc.client5.http.entity.mime.InputStreamBody;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.entity.mime.StringBody;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CryostatClient {

    private static final String DISCOVERY_API_PATH = "/api/v4/discovery";
    private static final String DISCOVERY_PUBLISH_API_PATH = "/api/v4.2/discovery";
    private static final String CREDENTIALS_API_PATH = "/api/v4/credentials";
    private static final String CHECK_CREDENTIAL_API_PATH = "/api/beta/discovery/credential_exists";
    private static final String DISCOVERY_TOKEN_HEADER = "Cryostat-Discovery-Authentication";

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final Executor executor;
    private final ObjectMapper mapper;
    private final HttpHost host;
    private final HttpClient http;

    private final String appName;
    private final String instanceId;
    private final String jvmId;
    private final URI baseUri;
    private final String realm;
    private final DiscoveryPublication discoveryPublication;

    CryostatClient(
            Executor executor,
            ObjectMapper mapper,
            HttpClient http,
            String instanceId,
            String jvmId,
            String appName,
            URI baseUri,
            String realm,
            DiscoveryPublication discoveryPublication) {
        this.executor = executor;
        this.mapper = mapper;
        this.host = HttpHost.create(baseUri);
        this.http = http;
        this.instanceId = instanceId;
        this.jvmId = jvmId;
        this.appName = appName;
        this.baseUri = baseUri;
        this.realm = realm;
        this.discoveryPublication = discoveryPublication;

        log.trace("Using Cryostat baseuri {}", baseUri);
    }

    public CompletableFuture<ServerHealth> serverHealth() {
        HttpGet req = new HttpGet(baseUri.resolve("/health"));
        log.trace("{}", req);
        return supply(req, res -> logResponse(req, res))
                .thenApply(
                        res -> {
                            try (InputStream is = res.getEntity().getContent()) {
                                return mapper.readValue(is, ServerHealth.class);
                            } catch (IOException e) {
                                log.error("Unable to parse response as JSON", e);
                                throw new RegistrationException(e);
                            }
                        })
                .whenComplete((v, t) -> req.reset());
    }

    public CompletableFuture<Boolean> checkRegistration(PluginInfo pluginInfo) {
        if (!pluginInfo.isInitialized()) {
            return CompletableFuture.completedFuture(false);
        }
        HttpGet req = new HttpGet(baseUri.resolve(DISCOVERY_API_PATH + "/" + pluginInfo.getId()));
        req.addHeader(DISCOVERY_TOKEN_HEADER, pluginInfo.getToken());
        log.trace("{}", req);
        return supply(req, (res) -> logResponse(req, res))
                .thenApply(this::isOkStatus)
                .whenComplete((v, t) -> req.reset());
    }

    public CompletableFuture<PluginInfo> register(
            int credentialId, PluginInfo pluginInfo, URI callback) {
        try {
            RegistrationInfo registrationInfo =
                    new RegistrationInfo(
                            pluginInfo.getId(), realm, callback, pluginInfo.getToken());
            HttpPost req = new HttpPost(baseUri.resolve(DISCOVERY_API_PATH));
            log.trace("{}", req);
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
                                    return mapper.readValue(is, PluginInfo.class);
                                } catch (IOException e) {
                                    log.error("Unable to parse response as JSON", e);
                                    throw new RegistrationException(e);
                                }
                            })
                    .whenComplete((v, t) -> req.reset());
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
        log.trace("{}", req);
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
                        })
                .whenComplete((v, t) -> req.reset());
    }

    private CompletableFuture<Integer> queryExistingCredentials(URI callback) {
        HttpPost req = new HttpPost(baseUri.resolve(CHECK_CREDENTIAL_API_PATH));
        MultipartEntityBuilder entityBuilder =
                MultipartEntityBuilder.create()
                        .addPart(
                                FormBodyPartBuilder.create(
                                                "script",
                                                new StringBody(
                                                        selfMatchExpression(callback),
                                                        ContentType.TEXT_PLAIN))
                                        .build());
        log.trace("{}", req);
        req.setEntity(entityBuilder.build());
        return supply(req, (res) -> logResponse(req, res))
                .handle(
                        (res, t) -> {
                            if (t != null) {
                                log.error("Failed to check credential", t);
                                throw new CompletionException(t);
                            }
                            return res;
                        })
                .thenApply(
                        res -> {
                            if (!isOkStatus(res)) {
                                return -1;
                            }
                            try (InputStream is = res.getEntity().getContent()) {
                                return mapper.readValue(is, StoredCredential.class).id;
                            } catch (IOException e) {
                                log.error("Unable to parse response as JSON", e);
                                throw new RegistrationException(e);
                            }
                        })
                .whenComplete((v, t) -> req.reset());
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
                                                new ByteArrayBody(
                                                        credentials.pass(),
                                                        ContentType.TEXT_PLAIN,
                                                        "pass"))
                                        .build())
                        .addPart(
                                FormBodyPartBuilder.create(
                                                "matchExpression",
                                                new StringBody(
                                                        selfMatchExpression(callback),
                                                        ContentType.TEXT_PLAIN))
                                        .build());
        log.trace("{}", req);
        req.setEntity(entityBuilder.build());
        return supply(req, (res) -> logResponse(req, res))
                .thenApply(
                        res -> {
                            if (!isOkStatus(res)) {
                                try {
                                    if (res.getCode() == 409) {
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
                        })
                .whenComplete((v, t) -> req.reset());
    }

    public CompletableFuture<Void> deleteCredentials(int id) {
        if (id < 0) {
            return CompletableFuture.completedFuture(null);
        }
        HttpDelete req = new HttpDelete(baseUri.resolve(CREDENTIALS_API_PATH + "/" + id));
        log.trace("{}", req);
        return supply(req, (res) -> logResponse(req, res))
                .whenComplete((v, t) -> req.reset())
                .thenApply(res -> null);
    }

    public CompletableFuture<Void> deregister(PluginInfo pluginInfo) {
        HttpDelete req =
                new HttpDelete(baseUri.resolve(DISCOVERY_API_PATH + "/" + pluginInfo.getId()));
        req.addHeader(DISCOVERY_TOKEN_HEADER, pluginInfo.getToken());
        log.trace("{}", req);
        return supply(req, (res) -> logResponse(req, res))
                .thenApply(res -> assertOkStatus(req, res))
                .whenComplete((v, t) -> req.reset())
                .thenApply(res -> null);
    }

    public CompletableFuture<Void> update(
            PluginInfo pluginInfo, Collection<DiscoveryNode> subtree) {
        try {
            HttpPost req =
                    new HttpPost(
                            baseUri.resolve(DISCOVERY_PUBLISH_API_PATH + "/" + pluginInfo.getId()));
            req.addHeader(DISCOVERY_TOKEN_HEADER, pluginInfo.getToken());
            req.setEntity(
                    new StringEntity(
                            mapper.writeValueAsString(
                                    new DiscoveryPublication(discoveryPublication, subtree)),
                            ContentType.APPLICATION_JSON));

            log.trace("{}", req);
            return supply(req, (res) -> logResponse(req, res))
                    .thenApply(res -> assertOkStatus(req, res))
                    .whenComplete((v, t) -> req.reset())
                    .thenApply(res -> null);
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<Void> syncSmartTrigger(SmartTriggerUpdate update) {
        try {
            HttpPost req =
                    new HttpPost(
                            baseUri.resolve(
                                    "/api/beta/targets/" + jvmId + "/smart_triggers/sync/"));
            req.setEntity(
                    new StringEntity(
                            mapper.writeValueAsString(update), ContentType.APPLICATION_JSON));
            log.trace("Sending Smart Trigger Sync Request: {}", req);
            return supply(req, (res) -> logResponse(req, res))
                    .thenApply(res -> assertOkStatus(req, res))
                    .whenComplete((v, t) -> req.reset())
                    .thenApply(res -> null);
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<Void> pushHeapDump(Path heapDump, String requestId)
            throws IOException, IllegalArgumentException {
        Instant start = Instant.now();
        if (heapDump.toFile().getName().isBlank()) {
            throw new IllegalArgumentException("Failed to generate heap dump");
        }
        HttpPost req =
                new HttpPost(baseUri.resolve("/api/beta/diagnostics/heapdump/upload/" + jvmId));

        CountingInputStream is = getRecordingInputStream(heapDump);

        MultipartEntityBuilder entityBuilder =
                MultipartEntityBuilder.create()
                        .addPart(
                                FormBodyPartBuilder.create(
                                                "heapDump",
                                                new InputStreamBody(
                                                        is,
                                                        ContentType.APPLICATION_OCTET_STREAM,
                                                        heapDump.toFile().getName()))
                                        .build())
                        .addPart(
                                FormBodyPartBuilder.create(
                                                "jobId",
                                                new StringBody(requestId, ContentType.TEXT_PLAIN))
                                        .build());
        req.setEntity(entityBuilder.build());
        return supply(
                        req,
                        (res) -> {
                            Instant finish = Instant.now();
                            log.trace(
                                    "{} {} ({} -> {}): {}/{}",
                                    req.getMethod(),
                                    res.getCode(),
                                    heapDump.getFileName().toString(),
                                    req.getRequestUri(),
                                    FileUtils.byteCountToDisplaySize(is.getByteCount()),
                                    Duration.between(start, finish));
                            assertOkStatus(req, res);
                            return (Void) null;
                        })
                .whenComplete(
                        (v, t) -> {
                            // Heap dump files tend to be very large, clean up after uploading
                            try {
                                Files.delete(heapDump);
                            } catch (IOException ioe) {
                                log.warn("Failed to delete heap dump: ", heapDump.toString());
                            }
                            req.reset();
                        });
    }

    public CompletableFuture<Void> upload(
            Harvester.PushType pushType,
            Optional<TemplatedRecording> opt,
            int maxFiles,
            Map<String, String> additionalLabels,
            Path recording)
            throws IOException {
        Instant start = Instant.now();
        String timestamp = start.truncatedTo(ChronoUnit.SECONDS).toString().replaceAll("[-:]", "");
        String template =
                opt.map(TemplatedRecording::getConfigurationInfo)
                        .map(ConfigurationInfo::getName)
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
        Map<String, String> labels = new HashMap<>(additionalLabels);
        labels.putAll(
                Map.of(
                        "jvmId",
                        jvmId,
                        "pushType",
                        pushType.name(),
                        "template.name",
                        template,
                        "template.type",
                        "TARGET"));

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
                            log.trace(
                                    "{} {} ({} -> {}): {}/{}",
                                    req.getMethod(),
                                    res.getCode(),
                                    fileName,
                                    req.getRequestUri(),
                                    FileUtils.byteCountToDisplaySize(is.getByteCount()),
                                    Duration.between(start, finish));
                            assertOkStatus(req, res);
                            return (Void) null;
                        })
                .whenComplete((v, t) -> req.reset());
    }

    private ClassicHttpResponse logResponse(HttpUriRequestBase req, ClassicHttpResponse res) {
        log.trace("{} {} : {}", req.getMethod(), req.getRequestUri(), res.getCode());
        return res;
    }

    private <T> CompletableFuture<T> supply(
            HttpUriRequestBase req, Function<ClassicHttpResponse, T> fn) {
        return CompletableFuture.supplyAsync(() -> fn.apply(executeQuiet(req)), executor);
    }

    private ClassicHttpResponse executeQuiet(HttpUriRequest req) {
        try {
            return http.execute(host, req);
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

    private boolean isOkStatus(ClassicHttpResponse res) {
        int sc = res.getCode();
        // 2xx is OK, 3xx is redirect range so allow those too
        return 200 <= sc && sc < 400;
    }

    private ClassicHttpResponse assertOkStatus(HttpUriRequestBase req, ClassicHttpResponse res) {
        int sc = res.getCode();
        if (!isOkStatus(res)) {
            try {
                URI uri = req.getUri();
                log.error("Non-OK response ({}) on HTTP API {}", sc, uri);
                throw new HttpException(
                        sc,
                        new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null));
            } catch (URISyntaxException use) {
                throw new IllegalStateException(use);
            }
        }
        return res;
    }

    @SuppressFBWarnings("UWF_UNWRITTEN_PUBLIC_OR_PROTECTED_FIELD")
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

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public static class DiscoveryPublication {

        final String fillAlgorithm;
        final Map<String, String> context = new HashMap<>();
        final Collection<DiscoveryNode> nodes = new ArrayList<>();

        public DiscoveryPublication(String fillAlgorithm, Map<String, String> context) {
            this.fillAlgorithm = fillAlgorithm;
            this.context.putAll(context);
        }

        DiscoveryPublication(DiscoveryPublication o, Collection<DiscoveryNode> nodes) {
            this(o.fillAlgorithm, o.context);
            this.nodes.addAll(nodes);
        }

        public Collection<DiscoveryNode> getNodes() {
            return nodes;
        }

        public String getFillAlgorithm() {
            return fillAlgorithm;
        }

        public Map<String, String> getContext() {
            return context;
        }

        public void setNodes(Collection<DiscoveryNode> nodes) {
            this.nodes.clear();
            this.nodes.addAll(nodes);
        }

        public void setContext(Map<String, String> context) {
            this.context.clear();
            this.context.putAll(context);
        }

        @Override
        public int hashCode() {
            return Objects.hash(nodes, fillAlgorithm, context);
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
            DiscoveryPublication other = (DiscoveryPublication) obj;
            return Objects.equals(nodes, other.nodes)
                    && Objects.equals(fillAlgorithm, other.fillAlgorithm)
                    && Objects.equals(context, other.context);
        }
    }
}
