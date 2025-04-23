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

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.cryostat.agent.ConfigModule.CallbackCandidate;

import org.apache.http.client.utils.URIBuilder;
import org.projectnessie.cel.extension.StringsLib;
import org.projectnessie.cel.tools.Script;
import org.projectnessie.cel.tools.ScriptException;
import org.projectnessie.cel.tools.ScriptHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CallbackResolver {

    private static final String HOST_SCRIPT_PATTERN_STRING =
            "(?<host>[A-Za-z0-9-.]+)(?:\\[(?<script>.+)\\])?";
    private static final Pattern HOST_SCRIPT_PATTERN = Pattern.compile(HOST_SCRIPT_PATTERN_STRING);

    private final ScriptHost scriptHost;
    private final List<CallbackCandidate> callbacks;
    private final Logger log = LoggerFactory.getLogger(getClass());

    public CallbackResolver(ScriptHost scriptHost, Collection<CallbackCandidate> callbacks) {
        this.scriptHost = scriptHost;
        this.callbacks = new ArrayList<>(callbacks);
    }

    public URI determineSelfCallback() {
        // Try resolving each provided host name in order as a DNS name
        Optional<URI> resolvedCallback =
                callbacks.stream()
                        .sequential()
                        .map(this::tryResolveCallback)
                        .filter(Objects::nonNull)
                        .findFirst();

        // If none of the above resolved, then throw an error
        URI callback =
                resolvedCallback.orElseThrow(
                        () ->
                                new RuntimeException(
                                        "Failed to resolve hostname, consider disabling hostname"
                                            + " verification in Cryostat for the agent callback"));
        log.debug("Using {} as callback", callback);
        return callback;
    }

    private URI tryResolveCallback(CallbackCandidate cb) {
        try {
            String transformedHostname = transformHostname(cb.getHostname());
            String host =
                    cb.getDomainName() == null
                            ? transformedHostname
                            : String.format("%s.%s", transformedHostname, cb.getDomainName());
            host = tryResolveHostname(host);
            return new URIBuilder()
                    .setScheme(cb.getScheme())
                    .setHost(host)
                    .setPort(cb.getPort())
                    .build();
        } catch (URISyntaxException e) {
            log.debug(String.format("Callback candidate %s was invalid", cb), e);
            return null;
        } catch (UnknownHostException e) {
            log.debug(String.format("Callback candidate %s could not be resolved", cb), e);
            return null;
        }
    }

    private String transformHostname(String hostName) {
        Matcher m = HOST_SCRIPT_PATTERN.matcher(hostName);
        if (!m.matches()) {
            throw new RuntimeException(
                    String.format(
                            "Invalid hostname argument encountered: %s. Expected format:"
                                    + " \"hostname\" or \"hostname[cel-script]\".",
                            hostName));
        }
        if (m.group("script") == null) {
            return (m.group("host"));
        }
        return (evaluateHostnameScript(m.group("script"), m.group("host")));
    }

    private String evaluateHostnameScript(String scriptText, String hostname) {
        try {
            Script script =
                    scriptHost
                            .buildScript("\"" + hostname + "\"." + scriptText)
                            .withLibraries(new StringsLib())
                            .build();
            Map<String, Object> args = new HashMap<>();
            return script.execute(String.class, args);
        } catch (ScriptException e) {
            throw new RuntimeException("Failed to execute provided CEL script", e);
        }
    }

    private String tryResolveHostname(String hostname) throws UnknownHostException {
        log.debug("Attempting to resolve {}", hostname);
        InetAddress addr = InetAddress.getByName(hostname);
        log.debug("Resolved {} to {}", hostname, addr.getHostAddress());
        return hostname;
    }
}
