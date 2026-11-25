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
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.cryostat.libcryostat.templates.TemplateType;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jdk.jfr.Configuration;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FlightRecorderHelper {

    private static final String START_FLIGHT_RECORDING = "-XX:StartFlightRecording";
    private static final Pattern DUMP_FILE_NAME =
            Pattern.compile(
                    "^hotspot-pid-(\\d+)-id-(\\d+)-\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}\\.jfr$");

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

    /**
     * Finds the file the JVM wrote when a recording started by {@code -XX:StartFlightRecording}
     * with a directory as its {@code filename} was stopped. Such a recording reports no destination
     * through the API, and on newer JDKs it is closed as soon as it has been written out, so the
     * file can only be located by the name the JVM gives it, {@code hotspot-pid-<pid>-id-<recording
     * id>-<timestamp>.jfr}, within the directories named on the JVM command line. A recording
     * started by {@code jcmd} with a directory cannot be found this way, since that directory is
     * not recorded anywhere.
     */
    public Optional<Path> findStartupDumpFile(long recordingId) {
        return findDumpFile(
                getStartupDumpDirectories(), ProcessHandle.current().pid(), recordingId);
    }

    List<Path> getStartupDumpDirectories() {
        return parseStartupDumpDirectories(
                ManagementFactory.getRuntimeMXBean().getInputArguments(),
                ProcessHandle.current().pid());
    }

    static List<Path> parseStartupDumpDirectories(List<String> jvmArguments, long pid) {
        List<Path> directories = new ArrayList<>();
        for (String argument : jvmArguments) {
            if (!argument.startsWith(START_FLIGHT_RECORDING)
                    || argument.length() <= START_FLIGHT_RECORDING.length()) {
                continue;
            }
            char delimiter = argument.charAt(START_FLIGHT_RECORDING.length());
            if (delimiter != '=' && delimiter != ':') {
                continue;
            }
            String options = argument.substring(START_FLIGHT_RECORDING.length() + 1);
            for (String option : splitOptions(options)) {
                int equals = option.indexOf('=');
                if (equals < 0 || !"filename".equals(option.substring(0, equals))) {
                    continue;
                }
                String expanded = expandFilename(unquote(option.substring(equals + 1)), pid);
                if (expanded != null && !expanded.isEmpty()) {
                    try {
                        directories.add(Path.of(expanded).toAbsolutePath().normalize());
                    } catch (InvalidPathException e) {
                        continue;
                    }
                }
            }
        }
        return directories;
    }

    static Optional<Path> findDumpFile(Collection<Path> directories, long pid, long recordingId) {
        List<Path> candidates = new ArrayList<>();
        for (Path directory : directories) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> files = Files.list(directory)) {
                files.filter(Files::isRegularFile)
                        .filter(f -> isDumpFileOf(f, pid, recordingId))
                        .forEach(candidates::add);
            } catch (IOException | RuntimeException e) {
                continue;
            }
        }
        return candidates.stream().max(Comparator.comparing(FlightRecorderHelper::fileNameOf));
    }

    private static String fileNameOf(Path file) {
        Path name = file.getFileName();
        return name == null ? "" : name.toString();
    }

    private static boolean isDumpFileOf(Path file, long pid, long recordingId) {
        Matcher m = DUMP_FILE_NAME.matcher(fileNameOf(file));
        return m.matches()
                && Long.toString(pid).equals(m.group(1))
                && Long.toString(recordingId).equals(m.group(2));
    }

    private static List<String> splitOptions(String options) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < options.length(); i++) {
            char c = options.charAt(i);
            if (quote != 0) {
                if (c == quote && options.charAt(i - 1) != '\\') {
                    quote = 0;
                }
                current.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                current.append(c);
            } else if (c == ',') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            if ((first == '"' || first == '\'') && value.charAt(value.length() - 1) == first) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /**
     * Expands {@code %p} to the PID and {@code %%} to {@code %}, as the JVM does. A name containing
     * {@code %t} depends on the time at which it was expanded and cannot be reproduced, so null is
     * returned for it.
     */
    private static String expandFilename(String filename, long pid) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < filename.length(); i++) {
            char c = filename.charAt(i);
            if (c == '%' && i < filename.length() - 1) {
                char next = filename.charAt(i + 1);
                if (next == 't') {
                    return null;
                }
                if (next == '%') {
                    sb.append('%');
                    i++;
                } else if (next == 'p') {
                    sb.append(pid);
                    i++;
                } else {
                    sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @SuppressFBWarnings({"EI_EXPOSE_REP", "CT_CONSTRUCTOR_THROW"})
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

    @SuppressFBWarnings(
            value = "CT_CONSTRUCTOR_THROW",
            justification =
                    "Constructor only validates and stores immutable configuration metadata")
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
            this(
                    TemplateType.TARGET,
                    requireConfiguration(configuration).getName(),
                    getConfigurationLabel(configuration));
        }

        private static Configuration requireConfiguration(Configuration configuration) {
            return Objects.requireNonNull(configuration);
        }

        private static String getConfigurationLabel(Configuration configuration) {
            return requireConfiguration(configuration).getLabel();
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
