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
package io.cryostat.agent;

import java.io.Closeable;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.cryostat.agent.model.DiscoveryNode;
import io.cryostat.agent.model.PluginInfo;
import io.cryostat.agent.model.RegistrationInfo;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.client.predicate.ResponsePredicate;

class CryostatClient implements Closeable {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private WebClient http;
    private final UUID instanceId;

    CryostatClient(Vertx vertx, UUID instanceId) {
        WebClientOptions opts = new WebClientOptions();

        // TODO make configurable
        opts.setDefaultHost("localhost");
        opts.setDefaultPort(8181);
        opts.setSsl(true);
        opts.setTrustAll(true);
        opts.setVerifyHost(false);

        this.http = WebClient.create(vertx, opts);
        this.instanceId = instanceId;
    }

    Future<PluginInfo> register() {
        String auth = System.getenv("CRYOSTAT_AGENT_AUTHORIZATION");
        if (StringUtils.isBlank(auth)) {
            auth = "None";
        }
        String realm = System.getenv("CRYOSTAT_AGENT_REALM");
        if (StringUtils.isBlank(realm)) {
            realm = "cryostat-agent-" + instanceId;
        }
        String callback = System.getenv("CRYOSTAT_AGENT_CALLBACK");
        // do this at startup time
        if (StringUtils.isBlank(callback)) {
            throw new UndefinedVariableException("CRYOSTAT_AGENT_CALLBACK");
        }
        RegistrationInfo registrationInfo = new RegistrationInfo(realm, callback);
        return http.post("/api/v2.2/discovery")
                .putHeader(HttpHeaders.AUTHORIZATION.toString(), auth)
                .expect(ResponsePredicate.SC_SUCCESS)
                .expect(ResponsePredicate.JSON)
                .timeout(1_000L)
                .sendJson(registrationInfo)
                .map(resp -> resp.bodyAsJsonObject())
                .map(json -> json.getJsonObject("data").getJsonObject("result"))
                .map(json -> json.mapTo(PluginInfo.class));
    }

    Future<Void> deregister(String id) {
        String auth = System.getenv("CRYOSTAT_AGENT_AUTHORIZATION");
        if (StringUtils.isBlank(auth)) {
            auth = "None";
        }
        return http.delete("/api/v2.2/discovery/" + id)
                .putHeader(HttpHeaders.AUTHORIZATION.toString(), auth)
                .expect(ResponsePredicate.SC_SUCCESS)
                .expect(ResponsePredicate.JSON)
                .timeout(1_000L)
                .send()
                .map(t -> null);
    }

    Future<Void> update(String id, Set<DiscoveryNode> subtree) {
        String auth = System.getenv("CRYOSTAT_AGENT_AUTHORIZATION");
        if (StringUtils.isBlank(auth)) {
            auth = "None";
        }
        return http.post("/api/v2.2/discovery/" + id)
                .putHeader(HttpHeaders.AUTHORIZATION.toString(), auth)
                .expect(ResponsePredicate.SC_SUCCESS)
                .expect(ResponsePredicate.JSON)
                .timeout(1_000L)
                .sendJson(subtree)
                .map(t -> null);
    }

    @Override
    public void close() {
        if (this.http != null) {
            this.http.close();
        }
    }
}
