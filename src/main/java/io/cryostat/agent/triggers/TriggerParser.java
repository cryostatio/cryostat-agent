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
package io.cryostat.agent.triggers;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;

import org.projectnessie.cel.checker.Decls;
import org.projectnessie.cel.tools.Script;
import org.projectnessie.cel.tools.ScriptHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.cryostat.agent.model.MBeanInfo;
import jdk.management.jfr.FlightRecorderMXBean;

public class TriggerParser {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private ScriptHost scriptHost = ScriptHost.newBuilder().build();
    private String rawTriggerDefinitions;
    private Map<String, Object> metrics;
    private String recordingTemplateName;

    public TriggerParser(String args) {
        if (args.isEmpty()) {
            log.warn("Agent args were empty, no Triggers were defined");
            return;
        } 
        rawTriggerDefinitions = args;
    }

    public void parse() {
        // Build the script
        // Trigger Syntax: (constraint1, ...)~template
        // target.processCPULoad > 0.2 && target.duration > 30s
        // (constraint 1, constraint 2, ...)
        // target.metrics.processCPULoad
        // (target.ProcessCPULoad > 0.2 for 30s && target.physicalMemoryUsage > 0.5 for 0s)~foo.jfc
        String[] triggerDefinitions = rawTriggerDefinitions.split("~");
        recordingTemplateName = triggerDefinitions[triggerDefinitions.length-1];
        try {
            metrics = new MBeanInfo().rawMetrics;
            Script script = scriptHost.buildScript(triggerDefinitions[0])
            .withDeclarations(
                Decls.newVar("metrics", Decls.newMapType(Decls.String, Decls.Any)))
                .build();
            List<Boolean> results = script.execute(List.class, metrics);
            if (results.contains(true)) {
                handleRecording(recordingTemplateName);
            }
        } catch (Exception e) {
            log.error("Failed to create/run script: ", e);
        }
    }

    public void handleRecording(String recordingName) {
        FlightRecorderMXBean FlightRecorderBean = ManagementFactory.getPlatformMXBean(FlightRecorderMXBean.class);
        // TODO: Find recording template on disk
        FlightRecorderBean.startRecording(0);
    }
}
