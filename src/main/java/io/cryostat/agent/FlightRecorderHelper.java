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

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.management.jfr.ConfigurationInfo;
import jdk.management.jfr.FlightRecorderMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FlightRecorderHelper {

    private final FlightRecorderMXBean bean =
            ManagementFactory.getPlatformMXBean(FlightRecorderMXBean.class);
    private final Logger log = LoggerFactory.getLogger(getClass());

    // FIXME this is repeated logic shared with Harvester startRecording
    public void startRecording(String templateNameOrLabel) {
        getTemplate(templateNameOrLabel)
                .ifPresentOrElse(
                        c -> {
                            long recordingId = bean.newRecording();
                            bean.setPredefinedConfiguration(recordingId, c.getName());
                            String recoringName =
                                    String.format("cryostat-smart-trigger-%d", recordingId);
                            bean.setRecordingOptions(
                                    recordingId, Map.of("name", recoringName, "disk", "true"));
                            bean.startRecording(recordingId);
                            log.info(
                                    "Started recording \"{}\" using template \"{}\"",
                                    recoringName,
                                    templateNameOrLabel);
                        },
                        () ->
                                log.error(
                                        "Cannot start recording with template named or labelled {}",
                                        templateNameOrLabel));
    }

    public Optional<ConfigurationInfo> getTemplate(String nameOrLabel) {
        return bean.getConfigurations().stream()
                .filter(c -> c.getName().equals(nameOrLabel) || c.getLabel().equals(nameOrLabel))
                .findFirst();
    }

    public boolean isValidTemplate(String nameOrLabel) {
        return getTemplate(nameOrLabel).isPresent();
    }

    public List<RecordingInfo> getRecordings() {
        return FlightRecorder.getFlightRecorder().getRecordings().stream()
                .map(RecordingInfo::new)
                .collect(Collectors.toList());
    }

    @SuppressFBWarnings(value = "URF_UNREAD_FIELD")
    public static class RecordingInfo {

        public final long id;
        public final String name;
        public final String state;
        public final Map<String, String> options;
        public final long startTime;
        public final long duration;
        public final boolean isContinuous;
        public final boolean toDisk;
        public final long maxSize;
        public final long maxAge;

        RecordingInfo(Recording rec) {
            this.id = rec.getId();
            this.name = rec.getName();
            this.state = rec.getState().name();
            this.options = rec.getSettings();
            if (rec.getStartTime() != null) {
                this.startTime = rec.getStartTime().toEpochMilli();
            } else {
                this.startTime = 0;
            }
            this.isContinuous = rec.getDuration() == null;
            this.duration = this.isContinuous ? 0 : rec.getDuration().toMillis();
            this.toDisk = rec.isToDisk();
            this.maxSize = rec.getMaxSize();
            if (rec.getMaxAge() != null) {
                this.maxAge = rec.getMaxAge().toMillis();
            } else {
                this.maxAge = 0;
            }
        }
    }
}
