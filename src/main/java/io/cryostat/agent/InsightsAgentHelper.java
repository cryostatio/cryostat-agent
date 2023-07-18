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

import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;

import io.cryostat.agent.model.PluginInfo;

import com.redhat.insights.InsightsReport;
import com.redhat.insights.InsightsReportController;
import com.redhat.insights.agent.AgentBasicReport;
import com.redhat.insights.agent.AgentConfiguration;
import com.redhat.insights.agent.ClassNoticer;
import com.redhat.insights.agent.InsightsAgentHttpClient;
import com.redhat.insights.http.InsightsHttpClient;
import com.redhat.insights.jars.JarInfo;
import com.redhat.insights.logging.InsightsLogger;
import com.redhat.insights.tls.PEMSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InsightsAgentHelper {

    private static final SLF4JWrapper log =
            new SLF4JWrapper(LoggerFactory.getLogger(InsightsAgentHelper.class));
    private static final BlockingQueue<JarInfo> jarsToSend = new LinkedBlockingQueue<>();
    private final String appName;

    InsightsAgentHelper(String appName) {
        this.appName = appName;
    }

    static void instrument(Instrumentation instrumentation) {
        ClassNoticer noticer = new ClassNoticer(log, jarsToSend);
        instrumentation.addTransformer(noticer);
    }

    void runInsightsAgent(PluginInfo pluginInfo) {
        log.info("Starting Red Hat Insights client");
        String token = pluginInfo.getEnv().get("INSIGHTS_TOKEN");
        if (token == null) {
            log.warning("Insights token missing");
            return;
        }
        String server = System.getenv("INSIGHTS_SERVER");
        if (server == null) {
            log.warning("Insights server missing");
            return;
        }
        if (appName == null) {
            log.warning("Application name missing");
            return;
        }

        Map<String, String> out = new HashMap<>();
        out.put("name", appName);
        out.put("base_url", server);
        out.put("token", token);
        AgentConfiguration config = new AgentConfiguration(out);

        final InsightsReport simpleReport = AgentBasicReport.of(log, config);
        final PEMSupport pem = new PEMSupport(log, config);

        final Supplier<InsightsHttpClient> httpClientSupplier =
                () -> new InsightsAgentHttpClient(log, config, () -> pem.createTLSContext());
        final InsightsReportController controller =
                InsightsReportController.of(
                        log, config, simpleReport, httpClientSupplier, jarsToSend);
        controller.generate();
    }

    private static class SLF4JWrapper implements InsightsLogger {

        private final Logger delegate;

        SLF4JWrapper(Logger delegate) {
            this.delegate = delegate;
        }

        @Override
        public void debug(String message) {
            delegate.debug(message);
        }

        @Override
        public void debug(String message, Throwable err) {
            delegate.debug(message, err);
        }

        @Override
        public void error(String message) {
            delegate.error(message);
        }

        @Override
        public void error(String message, Throwable err) {
            delegate.error(message, err);
        }

        @Override
        public void info(String message) {
            delegate.info(message);
        }

        @Override
        public void warning(String message) {
            delegate.warn(message);
        }

        @Override
        public void warning(String message, Throwable err) {
            delegate.warn(message, err);
        }
    }
}
