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
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ReflectionException;

import io.cryostat.libcryostat.net.MbeanAttributeMap;
import io.cryostat.libcryostat.net.MbeanAttributeMap.MBeanAttribute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import jakarta.inject.Inject;
import org.apache.hc.core5.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueryMbeansContext implements RemoteContext {

    private ObjectMapper mapper;
    private final Logger log = LoggerFactory.getLogger(getClass());

    @Inject
    QueryMbeansContext(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String mtd = exchange.getRequestMethod();
            List<MbeanAttributeMap> attributeMap = new ArrayList<MbeanAttributeMap>();
            switch (mtd) {
                case "GET":
                    {
                        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
                        // null,null returns all Mbeans
                        Set<ObjectInstance> beans = server.queryMBeans(null, null);
                        for (ObjectInstance bean : beans) {
                            List<MBeanAttribute> attrs = new ArrayList<>();
                            MBeanInfo info = server.getMBeanInfo(bean.getObjectName());
                            for (MBeanAttributeInfo a : info.getAttributes()) {
                                attrs.add(
                                        new MBeanAttribute(
                                                a.getName(),
                                                a.getType(),
                                                a.getDescription(),
                                                bean.getClassName(),
                                                a.isReadable(),
                                                a.isWritable()));
                            }
                            attributeMap.add(new MbeanAttributeMap(bean.getClassName(), attrs));
                        }
                        exchange.sendResponseHeaders(HttpStatus.SC_OK, BODY_LENGTH_UNKNOWN);
                        try (OutputStream responseStream = exchange.getResponseBody()) {
                            mapper.writeValue(responseStream, attributeMap);
                        }
                    }
                default:
                    exchange.sendResponseHeaders(HttpStatus.SC_BAD_REQUEST, BODY_LENGTH_UNKNOWN);
            }
        } catch (IOException
                | IntrospectionException
                | InstanceNotFoundException
                | ReflectionException e) {
            log.error(e.toString());
            exchange.sendResponseHeaders(HttpStatus.SC_INTERNAL_SERVER_ERROR, BODY_LENGTH_NONE);
        } finally {
            exchange.close();
        }
    }

    @Override
    public String path() {
        return "/mbean-query/";
    }
}
