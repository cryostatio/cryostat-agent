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

import java.io.IOException;
import java.io.StringReader;
import java.text.ParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import io.cryostat.libcryostat.templates.TemplateType;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jdk.jfr.Configuration;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FlightRecorderHelper {

    private final Logger log = LoggerFactory.getLogger(getClass());

    public Optional<Recording> createSnapshot() {
        Recording snapshot = FlightRecorder.getFlightRecorder().takeSnapshot();
        if (snapshot.getSize() == 0) {
            log.warn("No active recordings");
            snapshot.close();
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    public Recording createRecordingWithCustomTemplate(String template)
            throws IOException, ParseException {
        Recording recording = new Recording(Configuration.create(new StringReader(template)));
        recording.setToDisk(true);
        return recording;
    }

    public Optional<TemplatedRecording> createRecordingWithPredefinedTemplate(
            String templateNameOrLabel) {
        if ("ALL".equals(templateNameOrLabel)) {
            Map<String, String> allTemplate = new HashMap<>();
            FlightRecorder.getFlightRecorder()
                    .getEventTypes()
                    .forEach(
                            e -> {
                                e.getSettingDescriptors()
                                        .forEach(
                                                s -> {
                                                    allTemplate.put(
                                                            e.getName() + "#" + s.getName(),
                                                            s.getDefaultValue());
                                                });
                                allTemplate.put(e.getName() + "#enabled", "true");
                            });

            Recording recording = new Recording(allTemplate);
            recording.setToDisk(true);

            return Optional.of(
                    new TemplatedRecording(
                            new ConfigurationInfo(TemplateType.TARGET, "ALL", "ALL"), recording));
        } else {
            Optional<Configuration> opt = getTemplate(templateNameOrLabel);
            if (opt.isEmpty()) {
                log.error(
                        "Cannot start recording with template named or labelled {}",
                        templateNameOrLabel);
                return Optional.empty();
            }

            Configuration configuration = opt.get();
            Recording recording = new Recording(configuration.getSettings());
            recording.setToDisk(true);

            return Optional.of(
                    new TemplatedRecording(new ConfigurationInfo(configuration), recording));
        }
    }

    public Optional<Configuration> getTemplate(String nameOrLabel) {
        Objects.requireNonNull(nameOrLabel);
        return Configuration.getConfigurations().stream()
                .filter(c -> c.getName().equals(nameOrLabel) || c.getLabel().equals(nameOrLabel))
                .findFirst();
    }

    public boolean isValidTemplate(String nameOrLabel) {
        Objects.requireNonNull(nameOrLabel);
        return getTemplate(nameOrLabel).isPresent();
    }

    public List<Recording> getRecordings() {
        return getRecordings(r -> true);
    }

    public List<Recording> getRecordings(Predicate<Recording> predicate) {
        if (!FlightRecorder.isAvailable()) {
            log.error("FlightRecorder is unavailable");
            return List.of();
        }
        return FlightRecorder.getFlightRecorder().getRecordings().stream()
                .filter(predicate)
                .collect(Collectors.toList());
    }

    public Optional<Recording> getRecording(long id) {
        return getRecordings(r -> r.getId() == id).stream().findFirst();
    }

    @SuppressFBWarnings(value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
    public static class TemplatedRecording {
        private final ConfigurationInfo configuration;
        private final Recording recording;

        public TemplatedRecording(ConfigurationInfo configuration, Recording recording) {
            this.configuration = Objects.requireNonNull(configuration);
            this.recording = Objects.requireNonNull(recording);
        }

        public ConfigurationInfo getConfigurationInfo() {
            return configuration;
        }

        public Recording getRecording() {
            return recording;
        }
    }

    public static class ConfigurationInfo {
        private final String name;
        private final String label;
        private final TemplateType type;

        public ConfigurationInfo(TemplateType type, String name, String label) {
            this.type = Objects.requireNonNull(type);
            this.name = Objects.requireNonNull(name);
            this.label = Objects.requireNonNull(label);
        }

        public ConfigurationInfo(Configuration configuration) {
            this(TemplateType.TARGET, configuration.getName(), configuration.getLabel());
        }

        public String getName() {
            return name;
        }

        public String getLabel() {
            return label;
        }

        public TemplateType getType() {
            return type;
        }
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
