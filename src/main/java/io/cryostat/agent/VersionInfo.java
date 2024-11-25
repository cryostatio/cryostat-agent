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
import java.io.InputStream;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.cryostat.agent.util.ResourcesUtil;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VersionInfo {

    private static final String RESOURCE_LOCATION = "versions.properties";
    static final String AGENT_VERSION_KEY = "cryostat.agent.version";
    static final String MIN_VERSION_KEY = "cryostat.server.version.min";
    static final String MAX_VERSION_KEY = "cryostat.server.version.max";

    private static Logger log = LoggerFactory.getLogger(VersionInfo.class);

    private final Semver agentVersion;
    private final Semver serverMin;
    private final Semver serverMax;

    // testing only
    VersionInfo(Semver agentVersion, Semver serverMin, Semver serverMax) {
        this.agentVersion = agentVersion;
        this.serverMin = serverMin;
        this.serverMax = serverMax;
    }

    public static VersionInfo load() throws IOException {
        Properties prop = new Properties();
        try (InputStream is = ResourcesUtil.getResourceAsStream(RESOURCE_LOCATION)) {
            if (is == null) {
                log.warn("Could not locate resource {}", RESOURCE_LOCATION);
            } else {
                try {
                    prop.load(is);
                } catch (Exception e) {
                    log.warn(String.format("Failed to load resource %s", RESOURCE_LOCATION), e);
                }
            }
        }
        Semver agentVersion = Semver.fromString(prop.getProperty(AGENT_VERSION_KEY));
        Semver serverMin = Semver.fromString(prop.getProperty(MIN_VERSION_KEY));
        Semver serverMax = Semver.fromString(prop.getProperty(MAX_VERSION_KEY));
        return new VersionInfo(agentVersion, serverMin, serverMax);
    }

    public Map<String, String> asMap() {
        return Map.of(
                AGENT_VERSION_KEY, getAgentVersion().toString(),
                MIN_VERSION_KEY, getServerMin().toString(),
                MAX_VERSION_KEY, getServerMax().toString());
    }

    public Semver getAgentVersion() {
        return agentVersion;
    }

    public Semver getServerMin() {
        return serverMin;
    }

    public Semver getServerMax() {
        return serverMax;
    }

    public boolean validateServerVersion(Semver actual) {
        boolean greaterEqualMin = getServerMin().compareTo(actual) <= 0;
        boolean lesserMax = getServerMax().compareTo(actual) > 0;
        return greaterEqualMin && lesserMax;
    }

    public static class Semver implements Comparable<Semver> {

        public static final Pattern VERSION_PATTERN =
                Pattern.compile(
                        "^v?(?<major>[\\d]+)\\.(?<minor>[\\d]+)\\.(?<patch>[\\d]+)(?:-[a-z0-9\\._-]*)?",
                        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

        private static Logger log = LoggerFactory.getLogger(Semver.class);

        public static final Semver UNKNOWN =
                new Semver(0, 0, 0) {
                    @Override
                    public String toString() {
                        return "unknown";
                    }
                };

        private final int major;
        private final int minor;
        private final int patch;

        public Semver(int major, int minor, int patch) {
            this.major = major;
            this.minor = minor;
            this.patch = patch;
        }

        public static Semver fromString(String in) {
            if (StringUtils.isBlank(in)) {
                return UNKNOWN;
            }
            try {
                Matcher m = VERSION_PATTERN.matcher(in);
                if (!m.matches()) {
                    log.warn("Input version \"{}\" did not match expected semver pattern", in);
                    return UNKNOWN;
                }
                return new Semver(
                        Integer.parseInt(m.group("major")),
                        Integer.parseInt(m.group("minor")),
                        Integer.parseInt(m.group("patch")));
            } catch (NumberFormatException nfe) {
                log.error(String.format("Unable to parse input string \"%s\"", in), nfe);
                return UNKNOWN;
            }
        }

        public int getMajor() {
            return major;
        }

        public int getMinor() {
            return minor;
        }

        public int getPatch() {
            return patch;
        }

        @Override
        public String toString() {
            return String.format("%d.%d.%d", major, minor, patch);
        }

        @Override
        public int compareTo(Semver o) {
            return Comparator.comparingInt(Semver::getMajor)
                    .thenComparing(Semver::getMinor)
                    .thenComparing(Semver::getPatch)
                    .compare(this, o);
        }

        @Override
        public int hashCode() {
            return Objects.hash(major, minor, patch);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            Semver other = (Semver) obj;
            return major == other.major && minor == other.minor && patch == other.patch;
        }
    }
}
