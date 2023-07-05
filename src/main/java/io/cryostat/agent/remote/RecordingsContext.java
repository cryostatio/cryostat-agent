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
package io.cryostat.agent.remote;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.common.unit.QuantityConversionException;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.configuration.events.EventOptionID;
import org.openjdk.jmc.flightrecorder.configuration.recording.RecordingOptionsBuilder;
import org.openjdk.jmc.rjmx.ServiceNotAvailableException;
import org.openjdk.jmc.rjmx.services.jfr.IFlightRecorderService;

import io.cryostat.agent.StringUtils;
import io.cryostat.core.FlightRecorderException;
import io.cryostat.core.net.JFRConnection;
import io.cryostat.core.net.JFRConnectionToolkit;
import io.cryostat.core.serialization.SerializableRecordingDescriptor;
import io.cryostat.core.sys.Environment;
import io.cryostat.core.sys.FileSystem;
import io.cryostat.core.templates.LocalStorageTemplateService;
import io.cryostat.core.templates.MutableTemplateService.InvalidEventTemplateException;
import io.cryostat.core.templates.MutableTemplateService.InvalidXmlException;
import io.cryostat.core.templates.RemoteTemplateService;
import io.cryostat.core.templates.Template;
import io.cryostat.core.templates.TemplateService;
import io.cryostat.core.tui.ClientWriter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import io.smallrye.config.SmallRyeConfig;
import jdk.jfr.FlightRecorder;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RecordingsContext implements RemoteContext {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final SmallRyeConfig config;
    private final ObjectMapper mapper;
    private final Path templatesTmp;

    @Inject
    RecordingsContext(SmallRyeConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        try {
            this.templatesTmp = Files.createTempDirectory(null);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String path() {
        return "/recordings";
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String mtd = exchange.getRequestMethod();
            if (!ensureMethodAccepted(exchange)) {
                exchange.close();
                return;
            }
            switch (mtd) {
                case "GET":
                    try (OutputStream response = exchange.getResponseBody()) {
                        List<SerializableRecordingDescriptor> recordings = getRecordings();
                        exchange.sendResponseHeaders(HttpStatus.SC_OK, 0);
                        mapper.writeValue(response, recordings);
                    } catch (Exception e) {
                        log.error("recordings serialization failure", e);
                    }
                    break;
                case "POST":
                    try (InputStream body = exchange.getRequestBody()) {
                        StartRecordingRequest req =
                                mapper.readValue(body, StartRecordingRequest.class);
                        log.info(mapper.writeValueAsString(req));
                        if (!req.isValid()) {
                            exchange.sendResponseHeaders(HttpStatus.SC_BAD_REQUEST, -1);
                            return;
                        }
                        FileSystem fs = new FileSystem();
                        Environment env =
                                new Environment() {
                                    @Override
                                    public String getEnv(String key) {
                                        if (LocalStorageTemplateService.TEMPLATE_PATH.equals(key)) {
                                            return templatesTmp.toString();
                                        }
                                        return super.getEnv(key);
                                    }
                                };
                        JFRConnectionToolkit tk =
                                new JFRConnectionToolkit(
                                        new ClientWriter() {
                                            @Override
                                            public void print(String s) {
                                                log.info(s);
                                            }

                                            @Override
                                            public void println(Exception e) {
                                                log.warn("", e);
                                            }
                                        },
                                        fs,
                                        env);
                        JFRConnection conn = tk.connect(tk.createServiceURL("localhost", 0));
                        TemplateService templates = null;
                        Template template = null;
                        IConstrainedMap<EventOptionID> events;
                        try {
                            if (req.requestsCustomTemplate()) {
                                templates = new LocalStorageTemplateService(fs, env);
                                template =
                                        ((LocalStorageTemplateService) templates)
                                                .addTemplate(
                                                        new ByteArrayInputStream(
                                                                req.template.getBytes(
                                                                        StandardCharsets.UTF_8)));
                            } else {
                                templates = new RemoteTemplateService(conn);
                                template =
                                        templates.getTemplates().stream()
                                                .filter(
                                                        t ->
                                                                t.getName()
                                                                        .equals(
                                                                                req.localTemplateName))
                                                .findFirst()
                                                .orElseThrow();
                            }
                            events = templates.getEvents(template).orElseThrow();
                            IFlightRecorderService svc = conn.getService();
                            SerializableRecordingDescriptor recording =
                                    new SerializableRecordingDescriptor(
                                            svc.start(
                                                    new RecordingOptionsBuilder(conn.getService())
                                                            .name(req.name)
                                                            .duration(
                                                                    UnitLookup.MILLISECOND.quantity(
                                                                            req.duration))
                                                            .maxSize(
                                                                    UnitLookup.BYTE.quantity(
                                                                            req.maxSize))
                                                            .maxAge(
                                                                    UnitLookup.MILLISECOND.quantity(
                                                                            req.maxAge))
                                                            .toDisk(true)
                                                            .build(),
                                                    events));
                            exchange.sendResponseHeaders(HttpStatus.SC_CREATED, 0);
                            log.info(
                                    "Responding with new recording:\n{}",
                                    mapper.writeValueAsString(recording));
                            try (OutputStream response = exchange.getResponseBody()) {
                                mapper.writeValue(response, recording);
                            }
                        } catch (QuantityConversionException
                                | ServiceNotAvailableException
                                | FlightRecorderException
                                | org.openjdk.jmc.rjmx.services.jfr.FlightRecorderException
                                | InvalidEventTemplateException
                                | InvalidXmlException e) {
                            log.error("Failed to start recording", e);
                            exchange.sendResponseHeaders(HttpStatus.SC_INTERNAL_SERVER_ERROR, -1);
                        } finally {
                            if (templates != null
                                    && template != null
                                    && req.requestsCustomTemplate()) {
                                try {
                                    ((LocalStorageTemplateService) templates)
                                            .deleteTemplate(template);
                                } catch (InvalidEventTemplateException e) {
                                    log.error(
                                            "Failed to clean up template " + template.getName(), e);
                                }
                            }
                        }
                    }
                    break;
                default:
                    log.warn("Unknown request method {}", mtd);
                    exchange.sendResponseHeaders(HttpStatus.SC_METHOD_NOT_ALLOWED, -1);
                    break;
            }
        } finally {
            exchange.close();
        }
    }

    private boolean ensureMethodAccepted(HttpExchange exchange) throws IOException {
        Set<String> blocked = Set.of("POST");
        String mtd = exchange.getRequestMethod();
        boolean restricted = blocked.contains(mtd);
        if (!restricted) {
            return true;
        }
        boolean passed = restricted && MutatingRemoteContext.apiWritesEnabled(config);
        if (!passed) {
            exchange.sendResponseHeaders(HttpStatus.SC_FORBIDDEN, -1);
        }
        return passed;
    }

    private List<SerializableRecordingDescriptor> getRecordings() {
        return FlightRecorder.getFlightRecorder().getRecordings().stream()
                .map(SerializableRecordingDescriptor::new)
                .collect(Collectors.toList());
    }

    static class StartRecordingRequest {

        public String name;
        public String localTemplateName;
        public String template;
        public long duration;
        public long maxSize;
        public long maxAge;

        boolean requestsCustomTemplate() {
            return !StringUtils.isBlank(template);
        }

        boolean requestsBundledTemplate() {
            return !StringUtils.isBlank(localTemplateName);
        }

        boolean isValid() {
            boolean requestsCustomTemplate = requestsCustomTemplate();
            boolean requestsBundledTemplate = requestsBundledTemplate();
            boolean requestsEither = requestsCustomTemplate || requestsBundledTemplate;
            boolean requestsBoth = requestsCustomTemplate && requestsBundledTemplate;
            return requestsEither && !requestsBoth;
        }
    }
}
