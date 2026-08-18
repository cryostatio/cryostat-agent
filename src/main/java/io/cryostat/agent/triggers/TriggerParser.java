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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.cryostat.agent.FlightRecorderHelper;
import io.cryostat.libcryostat.triggers.SmartTrigger;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TriggerParser {

    private static final String TEMPLATE_PATTERN_STRING = "([\\w\\-]+)(?:\\.jfc)?";
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile(TEMPLATE_PATTERN_STRING);
    private final FlightRecorderHelper flightRecorderHelper;
    private final ObjectMapper mapper;
    private final Optional<Path> triggerPath;
    private final Logger log = LoggerFactory.getLogger(getClass());

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public TriggerParser(
            FlightRecorderHelper flightRecorderHelper,
            Optional<Path> triggerPath,
            ObjectMapper mapper) {
        this.flightRecorderHelper = flightRecorderHelper;
        this.triggerPath = triggerPath;
        this.mapper = mapper;
    }

    public List<SmartTrigger> parseFromFiles() {
        if (triggerPath.isEmpty()) {
            return Collections.emptyList();
        }
        if (triggerPath.isPresent() && !checkDir()) {
            log.warn(
                    "Configuration directory {} doesn't exist or is missing permissions",
                    triggerPath.get().toString());
            return Collections.emptyList();
        }
        try {
            return Files.walk(triggerPath.get())
                    .filter(Files::isRegularFile)
                    .filter(Files::isReadable)
                    .flatMap(path -> parseJsonFromFiles(path).stream())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error(e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<SmartTrigger> parseJsonFromFiles(Path path) {
        try {
            String triggerDefinitions = Files.readString(path);
            return parseFromJson(triggerDefinitions);
        } catch (IOException ioe) {
            log.error(ioe.getMessage());
            return Collections.emptyList();
        }
    }

    private boolean checkDir() {
        return Files.exists(triggerPath.get())
                && Files.isReadable(triggerPath.get())
                && Files.isExecutable(triggerPath.get())
                && Files.isDirectory(triggerPath.get());
    }

    public SmartTrigger parse(SmartTriggerReq req) {
        try {
            if (Objects.isNull(req)) {
                log.warn("Trigger request was null");
            }
            // non-provided fields will already be caught by the
            // ObjectMapper, check their values are valid
            var template = req.getRecordingTemplate().replaceAll("\\s", "");
            Matcher m = TEMPLATE_PATTERN.matcher(template);
            if (!m.matches()) {
                log.warn("Malformed template: {}", req.getRecordingTemplate());
                return null;
            }
            req.setRecordingTemplate(m.group(1));
            if (!isValid(req)) {
                // Log and skip invalid triggers
                log.warn(
                        "Trigger failed validation: {} {} {}",
                        req.getCondition(),
                        req.getDuration(),
                        req.getRecordingTemplate());
                return null;
            }
            try {
                return new SmartTrigger(
                        UUID.randomUUID().toString(),
                        constructExprFromParams(req),
                        req.getRecordingTemplate());
            } catch (DateTimeParseException dtpe) {
                log.error("Failed to parse trigger duration constraint", dtpe);
            }
        } catch (Exception e) {
            log.warn("Exception thrown while parsing triggers");
            log.warn(e.toString());
            return null;
        }
        return null;
    }

    public List<SmartTrigger> parseFromJson(String req) {
        try {
            SmartTriggerReq[] reqs = mapper.readValue(req, SmartTriggerReq[].class);
            var returnVal = new ArrayList<SmartTrigger>();
            for (SmartTriggerReq r : reqs) {
                var parsedRequest = parse(r);
                if (Objects.isNull(parsedRequest)) {
                    log.warn("Trigger request failed to parse");
                    continue;
                }
                returnVal.add(parsedRequest);
            }
            return returnVal;
        } catch (Exception e) {
            log.warn("Exception thrown while parsing triggers");
            log.warn(e.toString());
            return Collections.emptyList();
        }
    }

    public boolean isValid(SmartTriggerReq r) {
        if (Objects.isNull(r.getCondition()) || r.getCondition().isBlank()) {
            log.warn("Trigger condition was blank. Skipping Trigger.");
            return false;
        } else if (Objects.isNull(r.getRecordingTemplate()) || r.getRecordingTemplate().isBlank()) {
            log.warn("Template was blank. Skipping Trigger.");
            return false;
        } else if (Objects.isNull(r.getDuration())) {
            log.warn("Duration expression was null. Skipping Trigger.");
            return false;
        } else if (!flightRecorderHelper.isValidTemplate(r.getRecordingTemplate())) {
            log.warn("Template was invalid. Skipping Trigger.");
            return false;
        }
        return true;
    }

    // The CEL internal representation doesn't need to be exposed
    // to users, we can construct the expression to evaulate
    // from a simple set of properties.
    private String constructExprFromParams(SmartTriggerReq req) {
        return req.getCondition() + constructDurationExprFromRequest(req);
    }

    // Blank Duration indicates the trigger should fire immediately
    // when the condition is met.
    private String constructDurationExprFromRequest(SmartTriggerReq req) {
        return req.getDuration() == 0
                ? ""
                : ";TargetDuration>duration(\""
                        + Duration.ofMillis(req.getDuration()).toSeconds()
                        + "s\")";
    }
}
