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

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class WebServer extends AbstractVerticle {

    private static final String DEFAULT_HTTP_HOST = "0.0.0.0";
    private static final int DEFAULT_HTTP_PORT = 9977;

    private final Logger log = LoggerFactory.getLogger(getClass());

    private HttpServer http;

    @Override
    public void start(Promise<Void> promise) {
        String host = System.getenv("CRYOSTAT_AGENT_HTTP_HOST");
        if (StringUtils.isBlank(host)) {
            host = DEFAULT_HTTP_HOST;
        }
        final String fHost = host;
        String port = System.getenv("CRYOSTAT_AGENT_HTTP_PORT");
        if (StringUtils.isBlank(port)) {
            port = String.valueOf(DEFAULT_HTTP_PORT);
        }
        this.http =
                getVertx()
                        .createHttpServer(
                                new HttpServerOptions()
                                        .setHost(host)
                                        .setPort(Integer.valueOf(port)));

        Router router = Router.router(getVertx());
        router.route()
                .path("/")
                .method(HttpMethod.POST)
                .method(HttpMethod.GET)
                .handler(rc -> rc.end());

        this.http
                .requestHandler(router)
                .listen(
                        ar -> {
                            if (ar.failed()) {
                                promise.fail(ar.cause());
                                return;
                            }
                            promise.complete();
                            log.info(
                                    "HTTP Server started on {}:{}",
                                    fHost,
                                    ar.result().actualPort());
                        });
    }

    @Override
    public void stop(Promise<Void> promise) {
        log.info("HTTP Server stopping");
        if (this.http != null) {
            this.http.close(
                    ar -> {
                        promise.complete();
                        log.info("HTTP Server stopped");
                    });
        } else {
            log.info("HTTP Server not started?");
        }
    }
}
