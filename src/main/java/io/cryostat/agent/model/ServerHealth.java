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

import io.cryostat.agent.VersionInfo.Semver;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@SuppressFBWarnings("EI_EXPOSE_REP")
public class ServerHealth {

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
        return Semver.fromString(cryostatVersion());
    }

    public BuildInfo buildInfo() {
        return build;
    }

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
