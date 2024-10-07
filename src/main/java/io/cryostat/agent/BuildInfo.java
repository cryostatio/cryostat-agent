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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import io.cryostat.agent.util.ResourcesUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// FIXME this is adapted from Cryostat. Extract this to a reusable utility in libcryostat?
public class BuildInfo {

    private static Logger log = LoggerFactory.getLogger(BuildInfo.class);
    private static final String RESOURCE_LOCATION = "META-INF/gitinfo";

    private final GitInfo gitinfo = new GitInfo();

    public GitInfo getGitInfo() {
        return gitinfo;
    }

    public static class GitInfo {
        public String getHash() {
            try (BufferedReader br =
                    new BufferedReader(
                            new InputStreamReader(
                                    ResourcesUtil.getResourceAsStream(RESOURCE_LOCATION),
                                    StandardCharsets.UTF_8))) {
                return br.lines()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                String.format(
                                                        "Resource file %s is empty",
                                                        RESOURCE_LOCATION)))
                        .trim();
            } catch (Exception e) {
                log.warn("Version retrieval exception", e);
                return "unknown";
            }
        }
    }
}
