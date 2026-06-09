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

import java.util.Objects;

/**
 * Represents information about a Java process, providing a replacement for VirtualMachineDescriptor
 * that works without the Attach API.
 */
class JavaProcessInfo {
    private final String pid;
    private final String displayName;

    JavaProcessInfo(String pid, String displayName) {
        this.pid = Objects.requireNonNull(pid, "pid must not be null");
        this.displayName = Objects.requireNonNull(displayName, "displayName must not be null");
    }

    /**
     * Returns the process ID as a String.
     *
     * @return the process ID
     */
    public String id() {
        return pid;
    }

    /**
     * Returns the display name of the process, typically the main class name or JAR file name.
     *
     * @return the display name
     */
    public String displayName() {
        return displayName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JavaProcessInfo)) return false;
        JavaProcessInfo that = (JavaProcessInfo) o;
        return pid.equals(that.pid);
    }

    @Override
    public int hashCode() {
        return pid.hashCode();
    }

    @Override
    public String toString() {
        return String.format("JavaProcessInfo{pid='%s', displayName='%s'}", pid, displayName);
    }
}
