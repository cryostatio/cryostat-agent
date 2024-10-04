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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class VersionInfoTest {

    static final Semver serverMin = Semver.fromString("1.0.0");
    static final Semver serverMax = Semver.fromString("2.0.0");

    @Test
    public void testActualEqualsMin() {
        VersionInfo info = new VersionInfo("", serverMin, serverMax);
        Semver actual = Semver.fromString("1.0.0");
        Assertions.assertTrue(info.validateServerVersion(actual));
    }

    @Test
    public void testActualEqualsMax() {
        VersionInfo info = new VersionInfo("", serverMin, serverMax);
        Semver actual = Semver.fromString("2.0.0");
        Assertions.assertFalse(info.validateServerVersion(actual));
    }

    @Test
    public void testActualGreaterMax() {
        VersionInfo info = new VersionInfo("", serverMin, serverMax);
        Semver actual = Semver.fromString("3.0.0");
        Assertions.assertFalse(info.validateServerVersion(actual));
    }

    @Test
    public void testActualLesserMin() {
        VersionInfo info = new VersionInfo("", serverMin, serverMax);
        Semver actual = Semver.fromString("0.1.0");
        Assertions.assertFalse(info.validateServerVersion(actual));
    }

    @Test
    public void testActualInRange() {
        VersionInfo info = new VersionInfo("", serverMin, serverMax);
        Semver actual = Semver.fromString("1.1.0");
        Assertions.assertTrue(info.validateServerVersion(actual));
    }
}
