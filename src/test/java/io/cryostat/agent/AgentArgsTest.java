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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.instrument.Instrumentation;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class AgentArgsTest {

    @Test
    public void testArgumentParsing() {
        Instrumentation instr = Mockito.mock(Instrumentation.class);
        // Check that a full argument line key=value![smartTrigger]~template is parsed.
        AgentArgs args = AgentArgs.from(instr, "key=value![smartTrigger]~template");
        assertEquals(args.getProperties(), Map.of("key", "value"));
        assertEquals(args.getSmartTriggers(), "[smartTrigger]~template");
        // Check that a single argument smart trigger definition is parsed
        args = AgentArgs.from(instr, "[smartTriggerDef]~template2");
        assertTrue(args.getProperties().isEmpty());
        assertEquals(args.getSmartTriggers(), "[smartTriggerDef]~template2");
        // Check that a single argument property is parsed
        args = AgentArgs.from(instr, "key2=value2");
        assertEquals(args.getProperties(), Map.of("key2", "value2"));
        assertTrue(args.getSmartTriggers().isBlank());
        // Check that no argument doesn't throw an exception
        args = AgentArgs.from(instr, "");
        assertTrue(args.getProperties().isEmpty());
        assertTrue(args.getSmartTriggers().isBlank());
    }
}
