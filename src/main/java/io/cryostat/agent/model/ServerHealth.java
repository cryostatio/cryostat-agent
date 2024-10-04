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
package io.cryostat.agent.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.cryostat.agent.VersionInfo.Semver;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@SuppressFBWarnings(value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
public class ServerHealth {

    private static final Pattern VERSION_PATTERN =
            Pattern.compile(
                    "^v(?<major>[\\d]+)\\.(?<minor>[\\d]+)\\.(?<patch>[\\d]+)(?:-[a-z0-9\\._-]*)?",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private String cryostatVersion;
    private BuildInfo build;

    public ServerHealth() {}

    public ServerHealth(String cryostatVersion, BuildInfo build) {
        this.cryostatVersion = cryostatVersion;
        this.build = build;
    }

    public void setCryostatVersion(String cryostatVersion) {
        this.cryostatVersion = cryostatVersion;
    }

    public void setBuild(BuildInfo build) {
        this.build = build;
    }

    public String cryostatVersion() {
        return cryostatVersion;
    }

    public Semver cryostatSemver() {
        Matcher m = VERSION_PATTERN.matcher(cryostatVersion());
        if (!m.matches()) {
            return Semver.fromString("0.0.0");
        }
        return new Semver(
                Integer.parseInt(m.group("major")),
                Integer.parseInt(m.group("minor")),
                Integer.parseInt(m.group("patch")));
    }

    public BuildInfo buildInfo() {
        return build;
    }

    @SuppressFBWarnings(value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
    public static class BuildInfo {
        private GitInfo git;

        public BuildInfo() {}

        public BuildInfo(GitInfo git) {
            this.git = git;
        }

        public void setGit(GitInfo git) {
            this.git = git;
        }

        public GitInfo git() {
            return git;
        }
    }

    @SuppressFBWarnings(value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
    public static class GitInfo {
        private String hash;

        public GitInfo() {}

        public GitInfo(String hash) {
            this.hash = hash;
        }

        public void setHash(String hash) {
            this.hash = hash;
        }

        public String hash() {
            return hash;
        }
    }
}
