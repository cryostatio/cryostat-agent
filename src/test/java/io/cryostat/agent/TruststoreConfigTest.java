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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class TruststoreConfigTest {

    @Test
    public void shouldBuildTruststoreConfigWhenAllFieldsPresent() {
        TruststoreConfig config =
                new TruststoreConfig.Builder()
                        .withAlias("alias")
                        .withPath("/tmp/truststore.p12")
                        .withType("PKCS12")
                        .build();

        assertEquals("alias", config.getAlias());
        assertEquals("/tmp/truststore.p12", config.getPath());
        assertEquals("PKCS12", config.getType());
    }

    @Test
    public void shouldRejectMissingAliasBeforeConstruction() {
        NullPointerException exception =
                assertThrows(
                        NullPointerException.class,
                        () ->
                                new TruststoreConfig.Builder()
                                        .withPath("/tmp/truststore.p12")
                                        .withType("PKCS12")
                                        .build());

        assertEquals(
                "Imported certs for the agent's truststore must include a certificate alias",
                exception.getMessage());
    }
}
