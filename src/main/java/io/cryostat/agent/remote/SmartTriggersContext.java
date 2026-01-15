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

import io.cryostat.agent.triggers.TriggerEvaluator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import jakarta.inject.Inject;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SmartTriggersContext implements RemoteContext {

    private Logger log = LoggerFactory.getLogger(getClass());
    private TriggerEvaluator evaluator;
    private ObjectMapper mapper;

    @Inject
    SmartTriggersContext(ObjectMapper mapper, TriggerEvaluator evaluator) {
        this.evaluator = evaluator;
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String mtd = exchange.getRequestMethod();
            switch (mtd) {
                case "GET":
                    // Query the currently loaded smart triggers
                    exchange.sendResponseHeaders(HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
                    try (OutputStream response = exchange.getResponseBody()) {
                        mapper.writeValue(response, evaluator.getDefinitions());
                    }
                    break;
                case "POST":
                    try (InputStream body = exchange.getRequestBody()) {
                        SmartTriggerRequest req = mapper.readValue(body, SmartTriggerRequest.class);
                        boolean resp = evaluator.append(req.definitions);
                        if (!resp) {
                            exchange.sendResponseHeaders(
                                    HttpStatus.SC_BAD_REQUEST, BODY_LENGTH_NONE);
                        } else {
                            exchange.sendResponseHeaders(HttpStatus.SC_ACCEPTED, BODY_LENGTH_NONE);
                        }
                    } catch (Exception e) {
                        log.warn("Smart trigger serialization failure", e);
                        exchange.sendResponseHeaders(HttpStatus.SC_BAD_GATEWAY, BODY_LENGTH_NONE);
                    }
                    break;
                case "DELETE":
                    try (InputStream body = exchange.getRequestBody()) {
                        SmartTriggerRequest req = mapper.readValue(body, SmartTriggerRequest.class);
                        boolean resp = evaluator.remove(req.definitions);
                        if (!resp) {
                            exchange.sendResponseHeaders(
                                    HttpStatus.SC_BAD_REQUEST, BODY_LENGTH_NONE);
                        } else {
                            exchange.sendResponseHeaders(HttpStatus.SC_ACCEPTED, BODY_LENGTH_NONE);
                        }
                    } catch (Exception e) {
                        log.warn("Smart trigger serialization failure", e);
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

    @Override
    public String path() {
        return "/smart-triggers/";
    }

    static class SmartTriggerRequest {

        // This is fine as one string, the TriggerParser can handle it
        // if there are multiple triggers defined.
        public String definitions;
    }
}
