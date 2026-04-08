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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppNameResolverTest {

    AppNameResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AppNameResolver();
    }

    @Test
    void testExtractMainClassFromSimpleClassName() {
        String result = resolver.extractMainClass("MyApp");
        assertEquals("MyApp", result);
    }

    @Test
    void testExtractMainClassFromFullyQualifiedClassName() {
        String result = resolver.extractMainClass("com.example.MyApp");
        assertEquals("com.example.MyApp", result);
    }

    @Test
    void testExtractMainClassFromClassNameWithArguments() {
        String result = resolver.extractMainClass("com.example.MyApp arg1 arg2 arg3");
        assertEquals("com.example.MyApp", result);
    }

    @Test
    void testExtractMainClassFromSpringBootLoader() {
        String result =
                resolver.extractMainClass(
                        "org.springframework.boot.loader.JarLauncher"
                                + " --spring.profiles.active=prod");
        assertEquals("org.springframework.boot.loader.JarLauncher", result);
    }

    @Test
    void testExtractMainClassFromBlankString() {
        String result = resolver.extractMainClass("");
        assertNull(result);
    }

    @Test
    void testExtractMainClassFromNull() {
        String result = resolver.extractMainClass(null);
        assertNull(result);
    }

    @Test
    void testExtractMainClassFromWhitespaceOnly() {
        String result = resolver.extractMainClass("   ");
        assertNull(result);
    }

    @Test
    void testExtractJarNameFromSimpleJarPath() {
        String result = resolver.extractJarName("-jar myapp.jar");
        assertEquals("myapp.jar", result);
    }

    @Test
    void testExtractJarNameFromAbsolutePath() {
        String result = resolver.extractJarName("-jar /opt/applications/my-application.jar");
        assertEquals("my-application.jar", result);
    }

    @Test
    void testExtractJarNameFromRelativePath() {
        String result = resolver.extractJarName("-jar ./target/app.jar");
        assertEquals("app.jar", result);
    }

    @Test
    void testExtractJarNameWithArguments() {
        String result =
                resolver.extractJarName("-jar /path/to/service.jar --server.port=8080 --debug");
        assertEquals("service.jar", result);
    }

    @Test
    void testExtractJarNameFromJavaCommand() {
        String result = resolver.extractJarName("java -Xmx512m -jar /opt/myapp.jar --config=prod");
        assertEquals("myapp.jar", result);
    }

    @Test
    void testExtractJarNameWithoutExtension() {
        String result = resolver.extractJarName("-jar /path/to/application");
        assertEquals("application", result);
    }

    @Test
    void testExtractJarNameWithUppercaseExtension() {
        String result = resolver.extractJarName("-jar MyApp.JAR");
        assertEquals("MyApp.JAR", result);
    }

    @Test
    void testExtractJarNameWithMixedCaseExtension() {
        String result = resolver.extractJarName("-jar MyApp.JaR");
        assertEquals("MyApp.JaR", result);
    }

    @Test
    void testExtractJarNameWhenNoJarFlag() {
        String result = resolver.extractJarName("com.example.MyApp");
        assertNull(result);
    }

    @Test
    void testExtractJarNameWhenJarFlagIsLast() {
        String result = resolver.extractJarName("java -Xmx512m -jar");
        assertNull(result);
    }

    @Test
    void testExtractJarNameWhenJarPathIsBlank() {
        String result = resolver.extractJarName("-jar    ");
        assertNull(result);
    }

    @Test
    void testExtractFromJavaCommandWithMainClass() {
        String originalProperty = System.getProperty("sun.java.command");
        try {
            System.setProperty("sun.java.command", "com.example.MyApplication arg1 arg2");
            String result = resolver.extractFromJavaCommand();
            assertEquals("com.example.MyApplication", result);
        } finally {
            if (originalProperty != null) {
                System.setProperty("sun.java.command", originalProperty);
            } else {
                System.clearProperty("sun.java.command");
            }
        }
    }

    @Test
    void testExtractFromJavaCommandWithJar() {
        String originalProperty = System.getProperty("sun.java.command");
        try {
            System.setProperty("sun.java.command", "-jar /opt/my-service.jar --port=8080");
            String result = resolver.extractFromJavaCommand();
            assertEquals("my-service.jar", result);
        } finally {
            if (originalProperty != null) {
                System.setProperty("sun.java.command", originalProperty);
            } else {
                System.clearProperty("sun.java.command");
            }
        }
    }

    @Test
    void testExtractFromJavaCommandWhenPropertyNotSet() {
        String originalProperty = System.getProperty("sun.java.command");
        try {
            System.clearProperty("sun.java.command");
            String result = resolver.extractFromJavaCommand();
            assertNull(result);
        } finally {
            if (originalProperty != null) {
                System.setProperty("sun.java.command", originalProperty);
            }
        }
    }

    @Test
    void testExtractFromJavaCommandWhenPropertyIsBlank() {
        String originalProperty = System.getProperty("sun.java.command");
        try {
            System.setProperty("sun.java.command", "   ");
            String result = resolver.extractFromJavaCommand();
            assertNull(result);
        } finally {
            if (originalProperty != null) {
                System.setProperty("sun.java.command", originalProperty);
            } else {
                System.clearProperty("sun.java.command");
            }
        }
    }

    @Test
    void testExtractMainClassWithMultipleSpaces() {
        String result = resolver.extractMainClass("com.example.App    arg1    arg2");
        assertEquals("com.example.App", result);
    }

    @Test
    void testExtractMainClassWithTabs() {
        String result = resolver.extractMainClass("com.example.App\targ1\targ2");
        assertEquals("com.example.App", result);
    }

    @Test
    void testExtractJarNameWithComplexPath() {
        String result =
                resolver.extractJarName("-jar /var/lib/apps/production/v1.2.3/my-app-1.2.3.jar");
        assertEquals("my-app-1.2.3.jar", result);
    }

    @Test
    void testExtractMainClassEndsWithDot() {
        String result = resolver.extractMainClass("com.example.");
        assertNull(result);
    }

    @Test
    void testExtractMainClassSingleDot() {
        String result = resolver.extractMainClass(".");
        assertNull(result);
    }
}
