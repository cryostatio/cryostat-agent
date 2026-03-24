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
package io.cryostat.agent.util;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.cryostat.agent.ConfigModule;

import io.smallrye.config.SmallRyeConfig;
import org.apache.commons.lang3.StringUtils;

/**
 * Utility class for resolving the application name from various sources.
 *
 * <p>Resolution priority order:
 *
 * <ol>
 *   <li>Explicit configuration property ({@code cryostat.agent.app.name})
 *   <li>System property {@code sun.java.command} (extracts main class or JAR name)
 *   <li>Environment variable {@code JAVA_MAIN_CLASS}
 *   <li>Fallback to default constant "cryostat-agent"
 * </ol>
 */
@Singleton
public class AppNameResolver {

    private static final String DEFAULT_APP_NAME = "cryostat-agent";
    private static final String SUN_JAVA_COMMAND_PROPERTY = "sun.java.command";
    private static final String JAVA_MAIN_CLASS_ENV = "JAVA_MAIN_CLASS";
    private static final String JAR_FLAG = "-jar";

    @Inject
    public AppNameResolver() {}

    /**
     * Resolves the application name using the configured priority order.
     *
     * @param config the SmallRye configuration instance
     * @return the resolved application name, never null or blank
     */
    public String resolveAppName(SmallRyeConfig config) {
        Optional<String> configured =
                config.getOptionalValue(ConfigModule.CRYOSTAT_AGENT_APP_NAME, String.class);
        if (configured.isPresent() && !StringUtils.isBlank(configured.get())) {
            return configured.get().trim();
        }

        String fromCommand = extractFromJavaCommand();
        if (!StringUtils.isBlank(fromCommand)) {
            return fromCommand;
        }

        String fromEnv = System.getenv(JAVA_MAIN_CLASS_ENV);
        if (!StringUtils.isBlank(fromEnv)) {
            return fromEnv.trim();
        }

        return DEFAULT_APP_NAME;
    }

    /**
     * Extracts application name from the sun.java.command system property.
     *
     * @return the extracted application name, or null if unable to extract
     */
    public String extractFromJavaCommand() {
        String command = System.getProperty(SUN_JAVA_COMMAND_PROPERTY);
        if (StringUtils.isBlank(command)) {
            return null;
        }

        command = command.trim();

        if (command.contains(JAR_FLAG)) {
            return extractJarName(command);
        }

        return extractMainClass(command);
    }

    /**
     * Extracts the JAR name from a command line containing "-jar".
     *
     * <p>Examples:
     *
     * <ul>
     *   <li>"-jar /path/to/my-app.jar" -> "my-app.jar"
     *   <li>"-jar app.jar arg1 arg2" -> "app.jar"
     *   <li>"java -jar /opt/application.jar" -> "application.jar"
     * </ul>
     *
     * @param command the command line string containing "-jar"
     * @return the JAR filename with extension, or null if unable to extract
     */
    String extractJarName(String command) {
        String[] tokens = command.split("\\s+");

        for (int i = 0; i < tokens.length - 1; i++) {
            if (JAR_FLAG.equals(tokens[i])) {
                String jarPath = tokens[i + 1];
                if (StringUtils.isBlank(jarPath)) {
                    return null;
                }

                Path path = Paths.get(jarPath);
                Path fileNamePath = path.getFileName();
                if (fileNamePath == null) {
                    return null;
                }
                String fileName = fileNamePath.toString();

                return StringUtils.isBlank(fileName) ? null : fileName;
            }
        }

        return null;
    }

    /**
     * Extracts the main class name from a command line.
     *
     * <p>Examples:
     *
     * <ul>
     *   <li>"com.example.MyApp arg1 arg2" -> "com.example.MyApp"
     *   <li>"org.springframework.boot.loader.JarLauncher" ->
     *       "org.springframework.boot.loader.JarLauncher"
     *   <li>"MyApp" -> "MyApp"
     * </ul>
     *
     * @param command the command line string
     * @return the fully qualified class name, or null if unable to extract
     */
    String extractMainClass(String command) {
        if (StringUtils.isBlank(command)) {
            return null;
        }

        String[] tokens = command.split("\\s+");
        if (tokens.length == 0) {
            return null;
        }

        String mainClass = tokens[0].trim();
        if (StringUtils.isBlank(mainClass)) {
            return null;
        }

        if (mainClass.equals(".") || mainClass.endsWith(".")) {
            return null;
        }

        return mainClass;
    }
}
