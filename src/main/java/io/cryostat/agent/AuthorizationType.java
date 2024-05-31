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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.function.Function;

import io.cryostat.agent.util.StringUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum AuthorizationType implements Function<String, String> {
    NONE(v -> null),
    BEARER(v -> String.format("Bearer %s", v)),
    BASIC(
            v ->
                    String.format(
                            "Basic %s",
                            Base64.getEncoder()
                                    .encodeToString(v.getBytes(StandardCharsets.UTF_8)))),
    KUBERNETES(
            v -> {
                try {
                    File file = new File(v);
                    String token = Files.readString(file.toPath()).strip();
                    return String.format("Bearer %s", token);
                } catch (IOException ioe) {
                    Logger log = LoggerFactory.getLogger(AuthorizationType.class);
                    log.warn(String.format("Failed to read serviceaccount token from %s", v), ioe);
                    return null;
                }
            }),
    AUTO(
            v -> {
                String k8s = KUBERNETES.fn.apply(v);
                if (StringUtils.isNotBlank(k8s)) {
                    return k8s;
                }
                return NONE.fn.apply(v);
            }),
    ;

    private final Function<String, String> fn;

    private AuthorizationType(Function<String, String> fn) {
        this.fn = fn;
    }

    @Override
    public String apply(String in) {
        return fn.apply(in);
    }

    public static AuthorizationType fromString(String s) {
        for (AuthorizationType t : AuthorizationType.values()) {
            if (t.name().toLowerCase().equals(s.toLowerCase())) {
                return t;
            }
        }
        return NONE;
    }
}
