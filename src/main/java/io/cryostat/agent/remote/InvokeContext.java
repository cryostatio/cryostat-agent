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
package io.cryostat.agent.remote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Paths;
import java.util.Objects;

import javax.inject.Inject;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import io.cryostat.agent.CryostatClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import org.apache.http.HttpStatus;
import org.eclipse.microprofile.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class InvokeContext extends MutatingRemoteContext {

    private static final String DUMP_THREADS = "threadPrint";
    private static final String DUMP_THREADS_TO_FIlE = "threadDumpToFile";
    private static final String DIAGNOSTIC_BEAN_NAME = "com.sun.management:type=DiagnosticCommand";
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ObjectMapper mapper;

    private CryostatClient client;

    @Inject
    InvokeContext(ObjectMapper mapper, Config config, CryostatClient client) {
        super(config);
        this.mapper = mapper;
        this.client = client;
    }

    @Override
    public String path() {
        return "/mbean-invoke/";
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String mtd = exchange.getRequestMethod();
            switch (mtd) {
                case "POST":
                    try (InputStream body = exchange.getRequestBody()) {
                        MBeanInvocationRequest req =
                                mapper.readValue(body, MBeanInvocationRequest.class);
                        if (!req.isValid()) {
                            exchange.sendResponseHeaders(
                                    HttpStatus.SC_BAD_REQUEST, BODY_LENGTH_NONE);
                        }
                        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
                        Object response =
                                server.invoke(
                                        ObjectName.getInstance(req.beanName),
                                        req.operation,
                                        req.parameters,
                                        req.signature);

                        // TODO: Verify if dumpHeap is blocking, if so we should split the
                        // invocation
                        // into a separate thread and listen for when it finishes
                        if (req.getOperation().equals("dumpHeap")) {
                            String fileName = req.getParameters()[0].toString();
                            client.pushHeapDump(1, fileName, Paths.get(fileName));
                        }

                        if (Objects.nonNull(response)) {
                            exchange.sendResponseHeaders(HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
                            try (OutputStream responseStream = exchange.getResponseBody()) {
                                mapper.writeValue(responseStream, response);
                            }
                        } else {
                            exchange.sendResponseHeaders(HttpStatus.SC_ACCEPTED, BODY_LENGTH_NONE);
                        }
                    } catch (Exception e) {
                        log.error("mbean serialization failure", e);
                        exchange.sendResponseHeaders(HttpStatus.SC_BAD_GATEWAY, BODY_LENGTH_NONE);
                    }
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

    static class MBeanInvocationRequest<T> {

        public String beanName;
        public String operation;
        public Object[] parameters;
        public String[] signature;

        private static final String HOTSPOT_DIAGNOSTIC_BEAN_NAME =
                "com.sun.management:type=HotSpotDiagnostic";

        public boolean isValid() {
            if (this.beanName.equals(ManagementFactory.MEMORY_MXBEAN_NAME)
                    || this.beanName.equals(HOTSPOT_DIAGNOSTIC_BEAN_NAME)) {
                return true;
            } else if (this.beanName.equals(DIAGNOSTIC_BEAN_NAME)
                    && (this.operation.equals(DUMP_THREADS)
                            || this.operation.equals(DUMP_THREADS_TO_FIlE))) {
                return true;
            }
            return false;
        }

        public Object[] getParameters() {
            return parameters;
        }

        public void setParameters(Object[] parameters) {
            this.parameters = parameters;
        }

        public String getBeanName() {
            return beanName;
        }

        public void setBeanName(String beanName) {
            this.beanName = beanName;
        }

        public String getOperation() {
            return operation;
        }

        public void setOperation(String operation) {
            this.operation = operation;
        }
    }
}
