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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.UUID;

import javax.inject.Inject;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;

import io.cryostat.agent.ConfigModule;
import io.cryostat.agent.CryostatClient;
import io.cryostat.agent.remote.AsyncProfilerContext.AsyncProfilerMXBean;
import io.cryostat.libcryostat.net.CryostatAgentMXBean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import io.smallrye.config.SmallRyeConfig;
import org.apache.hc.core5.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class InvokeContext extends MutatingRemoteContext {

    private static final String DUMP_THREADS = "threadPrint";
    private static final String DUMP_THREADS_TO_FIlE = "threadDumpToFile";
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ObjectMapper mapper;

    private CryostatClient client;

    @Inject
    InvokeContext(ObjectMapper mapper, SmallRyeConfig config, CryostatClient client) {
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
                        String requestId = "";
                        String filename =
                                Files.createTempFile(
                                                config.getValue(
                                                        ConfigModule.CRYOSTAT_AGENT_APP_NAME,
                                                        String.class),
                                                (String) null)
                                        .toString();
                        if (!req.isValid()) {
                            exchange.sendResponseHeaders(
                                    HttpStatus.SC_BAD_REQUEST, BODY_LENGTH_NONE);
                        }

                        if (req.getOperation().equals("dumpHeap")) {
                            requestId = (String) req.parameters[2];
                            filename += "-" + UUID.randomUUID().toString() + ".hprof";
                            req.parameters[0] = filename;
                            // Job ID is passed along in parameters[2]
                            Object[] parameters = {req.parameters[0], req.parameters[1]};
                            req.parameters = parameters;
                        }

                        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
                        Object response =
                                server.invoke(
                                        ObjectName.getInstance(req.beanName),
                                        req.operation,
                                        req.parameters,
                                        req.signature);

                        if (req.getOperation().equals("dumpHeap")) {
                            // Send the request Id back with the heap dump so the server
                            // can match it with the open requests.
                            client.pushHeapDump(Paths.get(filename), requestId);
                        }

                        if (Objects.nonNull(response)) {
                            exchange.sendResponseHeaders(HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
                            try (OutputStream responseStream = exchange.getResponseBody()) {
                                mapper.writeValue(responseStream, response);
                            }
                        } else {
                            exchange.sendResponseHeaders(HttpStatus.SC_ACCEPTED, BODY_LENGTH_NONE);
                        }
                    } catch (InstanceNotFoundException
                            | IOException
                            | MBeanException
                            | MalformedObjectNameException
                            | ReflectionException e) {
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
        private static final String DIAGNOSTIC_COMMAND_BEAN_NAME =
                "com.sun.management:type=DiagnosticCommand";
        private static final String JMC_AGENT_BEAN_NAME =
                "org.openjdk.jmc.jfr.agent:type=AgentController";

        public boolean isValid() {
            if (CryostatAgentMXBean.OBJECT_NAME.equals(beanName)) {
                return true;
            }
            if (ManagementFactory.MEMORY_MXBEAN_NAME.equals(beanName)
                    || HOTSPOT_DIAGNOSTIC_BEAN_NAME.equals(beanName)) {
                return true;
            }
            if (DIAGNOSTIC_COMMAND_BEAN_NAME.equals(beanName)
                    && (DUMP_THREADS.equals(this.operation)
                            || DUMP_THREADS_TO_FIlE.equals(this.operation))) {
                return true;
            }
            if (JMC_AGENT_BEAN_NAME.equals(beanName)) {
                return true;
            }
            if (AsyncProfilerMXBean.OBJECT_NAME.equals(beanName)) {
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
