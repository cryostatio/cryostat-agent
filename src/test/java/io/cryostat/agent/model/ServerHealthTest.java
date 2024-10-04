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
import io.cryostat.agent.model.ServerHealth.BuildInfo;
import io.cryostat.agent.model.ServerHealth.GitInfo;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

public class ServerHealthTest {

    @Test
    public void test() {
        GitInfo git = new GitInfo("abcd1234");
        BuildInfo build = new BuildInfo(git);
        ServerHealth health = new ServerHealth("v1.2.3-snapshot", build);
        MatcherAssert.assertThat(
                health.cryostatSemver(), Matchers.equalTo(Semver.fromString("1.2.3")));
    }
}
