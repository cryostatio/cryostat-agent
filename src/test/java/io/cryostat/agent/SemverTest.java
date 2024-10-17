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

public class SemverTest {

    @ParameterizedTest
    @CsvSource({
        "1.0.0, 1.0.0, 0",
        "2.0.0, 1.0.0, 1",
        "1.0.0, 2.0.0, -1",
        "1.0.0, 1.1.0, -1",
        "1.1.0, 1.0.0, 1",
        "1.0.1, 1.0.0, 1",
        "2.0.0, 1.1.0, 1",
        "1.0.1, 1.1.0, -1",
        "1.1.1, 1.0.1, 1",
        "1.0.0-SNAPSHOT, 1.0.0, 0",
        "v1.0.0-SNAPSHOT, 1.0.0, 0",
        "v1.0.0, 1.0.0, 0",
    })
    public void test(String first, String second, int result) {
        Semver a = Semver.fromString(first);
        Semver b = Semver.fromString(second);
        MatcherAssert.assertThat(a.compareTo(b), Matchers.equalTo(result));
    }
}
