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
package io.cryostat.agent.shaded;

import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.LogRecord;

// Maven Shade plugin rewrites java.util.logging.Logger classes to reference this one instead.
// This is part of the encapsulation of the Agent's logging to separate it from the attached
// application's logging.
public class ShadeLogger {

    public static ShadeLogger getLogger(String name) {
        return new ShadeLogger();
    }

    public static ShadeLogger getLogger(String name, String resourceBundleName) {
        return new ShadeLogger();
    }

    public static ShadeLogger getAnonymousLogger() {
        return new ShadeLogger();
    }

    public static ShadeLogger getAnonymousLogger(String resourceBundleName) {
        return new ShadeLogger();
    }

    public String getName() {
        return "";
    }

    public void log(LogRecord record) {}

    public void log(Level level, String msg) {}

    public void log(Level level, String msg, Object arg1) {}

    public void log(Level level, String msg, Object[] args) {}

    public void log(Level level, String msg, Throwable t) {}

    public void logp(Level level, String klazz, String mtd, String msg) {}

    public void logp(Level level, String klazz, String mtd, String msg, Object arg1) {}

    public void logp(Level level, String klazz, String mtd, String msg, Object[] args) {}

    public void logp(Level level, String klazz, String mtd, String msg, Throwable t) {}

    public void logrb(Level level, String klazz, String mtd, String bundle, String msg) {}

    public void logrb(
            Level level, String klazz, String mtd, String bundle, String msg, Object arg1) {}

    public void logrb(
            Level level, String klazz, String mtd, String bundle, String msg, Object[] args) {}

    public void logrb(
            Level level,
            String klazz,
            String mtd,
            ResourceBundle bundle,
            String msg,
            Object... args) {}

    public void logrb(
            Level level, String klazz, String mtd, String bundle, String msg, Throwable t) {}

    public void logrb(
            Level level,
            String klazz,
            String mtd,
            ResourceBundle bundle,
            String msg,
            Throwable t) {}

    public void severe(String msg) {}

    public void warning(String msg) {}

    public void info(String msg) {}

    public void config(String msg) {}

    public void fine(String msg) {}

    public void finer(String msg) {}

    public void finest(String msg) {}

    public void throwing(String klazz, String mtd, Throwable t) {}

    public void setLevel(Level newLevel) throws SecurityException {}

    public boolean isLoggable(Level level) {
        return false;
    }
}
