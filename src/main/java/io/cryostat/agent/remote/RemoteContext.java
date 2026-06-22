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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.apache.commons.io.IOUtils;
import org.slf4j.LoggerFactory;

public interface RemoteContext extends HttpHandler {

    public static final int BODY_LENGTH_NONE = -1;
    public static final int BODY_LENGTH_UNKNOWN = 0;

    String path();

    default boolean available() {
        return true;
    }

    default void drain(HttpExchange exchange) {
        try {
            IOUtils.consume(exchange.getRequestBody());
        } catch (IOException e) {
            LoggerFactory.getLogger(getClass()).trace("Failed to drain request body", e);
        }
    }
}
