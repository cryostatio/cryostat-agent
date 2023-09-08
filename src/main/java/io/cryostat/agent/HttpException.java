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

import java.net.URI;

public class HttpException extends RuntimeException {
    HttpException(int statusCode, URI uri) {
        super(
                String.format(
                        "Unexpected non-OK status code %d on API path %s",
                        statusCode, uri.toString()));
    }

    HttpException(int statusCode, Throwable cause) {
        super(String.format("HTTP %d", statusCode), cause);
    }
}
