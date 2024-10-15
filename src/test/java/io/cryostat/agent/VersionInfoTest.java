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

import io.cryostat.agent.VersionInfo.Semver;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class VersionInfoTest {

    static final Semver serverMin = Semver.fromString("1.0.0");
    static final Semver serverMax = Semver.fromString("2.0.0");

    @ParameterizedTest
    @CsvSource({"1.0.0, true", "2.0.0, false", "3.0.0, false", "0.1.0, false", "1.1.0, true"})
    public void test(String serverVersion, boolean inRange) {
        VersionInfo info = new VersionInfo(Semver.UNKNOWN, serverMin, serverMax);
        Semver actual = Semver.fromString(serverVersion);
        MatcherAssert.assertThat(info.validateServerVersion(actual), Matchers.equalTo(inRange));
    }
}
