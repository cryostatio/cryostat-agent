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

import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.cryostat.agent.FlightRecorderHelper;
import io.cryostat.agent.util.StringUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TriggerParser {

    private static final String EXPRESSION_PATTERN_STRING =
            "\\[(.*(&&)*|(\\|\\|)*)\\]~([\\w\\-]+)(?:\\.jfc)?";
    private static final Pattern EXPRESSION_PATTERN = Pattern.compile(EXPRESSION_PATTERN_STRING);
    private final FlightRecorderHelper flightRecorderHelper;
    private final Logger log = LoggerFactory.getLogger(getClass());

    public TriggerParser(FlightRecorderHelper flightRecorderHelper) {
        this.flightRecorderHelper = flightRecorderHelper;
    }

    public List<SmartTrigger> parse(String str) {
        List<SmartTrigger> triggers = new ArrayList<>();
        if (StringUtils.isBlank(str)) {
            return triggers;
        }

        String[] expressions = str.split(",");
        for (String s : expressions) {
            s = s.replaceAll("\\s", "");
            Matcher m = EXPRESSION_PATTERN.matcher(s);
            if (m.matches()) {
                String constraintString = m.group(1);
                String templateName = m.group(4);
                if (flightRecorderHelper.isValidTemplate(templateName)) {
                    try {
                        SmartTrigger trigger = new SmartTrigger(constraintString, templateName);
                        triggers.add(trigger);
                    } catch (DateTimeParseException dtpe) {
                        log.error("Failed to parse trigger duration constraint", dtpe);
                    }
                } else {
                    log.warn("Template " + templateName + " not found. Skipping trigger.");
                }
            }
        }
        return triggers;
    }
}
