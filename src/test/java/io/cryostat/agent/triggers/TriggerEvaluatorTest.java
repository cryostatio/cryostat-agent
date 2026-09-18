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
package io.cryostat.agent.triggers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

import io.cryostat.agent.CryostatClient;
import io.cryostat.agent.FlightRecorderHelper;
import io.cryostat.agent.FlightRecorderHelper.TemplatedRecording;
import io.cryostat.agent.harvest.Harvester;
import io.cryostat.libcryostat.triggers.SmartTrigger;
import io.cryostat.libcryostat.triggers.SmartTrigger.TriggerState;

import jdk.jfr.Recording;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.projectnessie.cel.tools.Script;
import org.projectnessie.cel.tools.ScriptException;
import org.projectnessie.cel.tools.ScriptHost;
import org.projectnessie.cel.tools.ScriptHost.ScriptBuilder;

@ExtendWith(MockitoExtension.class)
class TriggerEvaluatorTest {

    @Mock ScheduledExecutorService executor;
    @Mock ScriptHost scriptHost;
    @Mock TriggerParser parser;
    @Mock FlightRecorderHelper helper;
    @Mock Path triggerPath;
    @Mock Harvester harvester;
    @Mock CryostatClient client;
    TriggerEvaluator triggerEvaluator;

    @BeforeEach
    public void setup() {
        triggerEvaluator =
                new TriggerEvaluator(
                        executor, scriptHost, "", parser, helper, harvester, 1000, client);
    }

    @Test
    public void testAppendSimple() {
        SmartTriggerReq[] req = {
            new SmartTriggerReq(
                    "ProcessCpuLoad>0.1", 1000, "ProcessCpuLoad<0.2", 1000, 0, "template.jfc")
        };
        SmartTrigger trigger =
                new SmartTrigger(
                        "foo",
                        "ProcessCpuLoad>0.1",
                        "ProcessCpuLoad<0.2",
                        1000,
                        1000,
                        0,
                        "template.jfc");
        when(parser.isValid(any(SmartTriggerReq.class))).thenReturn(true);
        when(parser.parseAttributesFromCondition(anyString())).thenReturn(List.of("ProcessCpuLoad"));
        when(parser.parse(any(SmartTriggerReq.class))).thenReturn(trigger);
        MatcherAssert.assertThat(triggerEvaluator.append(req), Matchers.equalTo(List.of("foo")));
    }

    @Test
    public void testAppendDoesNotRegisterInvalidTriggers() {
        SmartTriggerReq[] req = {
            new SmartTriggerReq(
                    "ProcessCpuLoad>0.1", 1000, "ProcessCpuLoad<0.2", 1000, 0, "template.jfc")
        };
        when(parser.isValid(any(SmartTriggerReq.class))).thenReturn(false);
        when(parser.parseAttributesFromCondition(anyString())).thenReturn(List.of("ProcessCpuLoad"));
        MatcherAssert.assertThat(triggerEvaluator.append(req), Matchers.equalTo(List.of()));
    }

    @Test
    public void testRemove() {
        SmartTriggerReq[] req = {
            new SmartTriggerReq(
                    "ProcessCpuLoad>0.1", 1000, "ProcessCpuLoad<0.2", 1000, 0, "template.jfc")
        };
        SmartTrigger trigger =
                new SmartTrigger(
                        "foo",
                        "ProcessCpuLoad>0.1",
                        "ProcessCpuLoad<0.2",
                        1000,
                        1000,
                        0,
                        "template.jfc");
        when(parser.isValid(any(SmartTriggerReq.class))).thenReturn(true);
        when(parser.parse(any(SmartTriggerReq.class))).thenReturn(trigger);
        when(parser.parseAttributesFromCondition(anyString())).thenReturn(List.of("ProcessCpuLoad"));
        MatcherAssert.assertThat(triggerEvaluator.append(req), Matchers.equalTo(List.of("foo")));
        MatcherAssert.assertThat(triggerEvaluator.remove("foo"), Matchers.equalTo(true));
        MatcherAssert.assertThat(triggerEvaluator.getDefinitions(), Matchers.equalTo(List.of()));
    }

    @Test
    public void testRemoveNonexistent() {
        MatcherAssert.assertThat(triggerEvaluator.remove("foo"), Matchers.equalTo(false));
        MatcherAssert.assertThat(triggerEvaluator.getDefinitions(), Matchers.equalTo(List.of()));
    }

    @Test
    public void testSimpleTrigger() throws ScriptException {
        SmartTriggerReq[] req = {
            new SmartTriggerReq(
                    "ProcessCpuLoad>0.1", 1000, "ProcessCpuLoad<0.2", 1000, 0, "template.jfc")
        };
        SmartTrigger trigger =
                new SmartTrigger("foo", "ProcessCpuLoad>0.1", "", 0, 0, 1, "template.jfc");
        when(parser.isValid(any(SmartTriggerReq.class))).thenReturn(true);
        when(parser.parseAttributesFromCondition(anyString())).thenReturn(List.of("ProcessCpuLoad"));
        when(parser.parse(any(SmartTriggerReq.class))).thenReturn(trigger);

        // Trigger should start in NEW state
        MatcherAssert.assertThat(triggerEvaluator.append(req), Matchers.equalTo(List.of("foo")));
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.NEW));
        MatcherAssert.assertThat(trigger.isSimple(), Matchers.equalTo(true));

        Script s = Mockito.mock(Script.class);
        ScriptBuilder builder = Mockito.mock(ScriptBuilder.class);
        when(scriptHost.buildScript(anyString())).thenReturn(builder);
        when(builder.withDeclarations(anyList())).thenReturn(builder);
        when(builder.build()).thenReturn(s);

        TemplatedRecording rec = Mockito.mock(TemplatedRecording.class);
        Recording recording = Mockito.mock(Recording.class);
        when(helper.createRecordingWithPredefinedTemplate(anyString()))
                .thenReturn(Optional.of(rec));
        when(rec.getRecording()).thenReturn(recording);
        Mockito.doNothing().when(recording).setName(anyString());
        Mockito.doNothing().when(recording).start();
        when(recording.getId()).thenReturn(1234l);
        trigger.setTimeConditionFirstMet(new Date(0));
        when(s.execute(any(), anyMap())).thenReturn(true);

        // Trigger has a condition and no duration specified
        // NEW -> RECORDING ACTIVE
        triggerEvaluator.evaluate();
        MatcherAssert.assertThat(
                trigger.getState(), Matchers.equalTo(TriggerState.RECORDING_ACTIVE));
        verify(helper, Mockito.atLeastOnce()).createRecordingWithPredefinedTemplate(anyString());
        verify(recording, Mockito.atLeastOnce()).start();
    }

    @Test
    public void testNewToWaitingHigh() throws ScriptException {
        SmartTriggerReq[] req = {
            new SmartTriggerReq(
                    "ProcessCpuLoad>0.1", 1000, "ProcessCpuLoad<0.2", 1000, 0, "template.jfc")
        };
        SmartTrigger trigger =
                new SmartTrigger(
                        "foo",
                        "ProcessCpuLoad>0.1",
                        "ProcessCpuLoad<0.2",
                        1000,
                        1000,
                        0,
                        "template.jfc");
        when(parser.isValid(any(SmartTriggerReq.class))).thenReturn(true);
        when(parser.parseAttributesFromCondition(anyString())).thenReturn(List.of("ProcessCpuLoad"));
        when(parser.parse(any(SmartTriggerReq.class))).thenReturn(trigger);
        // Trigger should start in NEW state
        MatcherAssert.assertThat(triggerEvaluator.append(req), Matchers.equalTo(List.of("foo")));
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.NEW));
        Script s = Mockito.mock(Script.class);
        ScriptBuilder builder = Mockito.mock(ScriptBuilder.class);
        when(scriptHost.buildScript(anyString())).thenReturn(builder);
        when(builder.withDeclarations(anyList())).thenReturn(builder);
        when(builder.build()).thenReturn(s);
        // Trigger has a duration, and condition is met on first run
        // NEW -> WAITING HIGH
        when(s.execute(any(), anyMap())).thenReturn(true);
        triggerEvaluator.evaluate();
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.WAITING_HIGH));
    }

    @Test
    public void testWaitingHighToWaitingLow() throws ScriptException {
        SmartTriggerReq[] req = {
            new SmartTriggerReq(
                    "ProcessCpuLoad>0.1", 1000, "ProcessCpuLoad<0.2", 1000, 0, "template.jfc")
        };
        SmartTrigger trigger =
                new SmartTrigger(
                        "foo",
                        "ProcessCpuLoad>0.1",
                        "ProcessCpuLoad<0.2",
                        1000,
                        1000,
                        0,
                        "template.jfc");
        when(parser.isValid(any(SmartTriggerReq.class))).thenReturn(true);
        when(parser.parseAttributesFromCondition(anyString())).thenReturn(List.of("ProcessCpuLoad"));
        when(parser.parse(any(SmartTriggerReq.class))).thenReturn(trigger);
        // Trigger should start in NEW state
        MatcherAssert.assertThat(triggerEvaluator.append(req), Matchers.equalTo(List.of("foo")));
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.NEW));
        Script s = Mockito.mock(Script.class);
        ScriptBuilder builder = Mockito.mock(ScriptBuilder.class);
        when(scriptHost.buildScript(anyString())).thenReturn(builder);
        when(builder.withDeclarations(anyList())).thenReturn(builder);
        when(builder.build()).thenReturn(s);
        // Trigger has a duration, and condition is met on first run
        // NEW -> WAITING HIGH
        when(s.execute(any(), anyMap())).thenReturn(true);
        triggerEvaluator.evaluate();
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.WAITING_HIGH));
        // Next Run, condition is not met anymore
        // WAITING HIGH -> WAITING LOW
        when(s.execute(any(), anyMap())).thenReturn(false);
        triggerEvaluator.evaluate();
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.WAITING_LOW));
    }

    @Test
    public void testWaitingLowToWaitingHigh() throws ScriptException {
        SmartTriggerReq[] req = {
            new SmartTriggerReq(
                    "ProcessCpuLoad>0.1", 1000, "ProcessCpuLoad<0.2", 1000, 0, "template.jfc")
        };
        SmartTrigger trigger =
                new SmartTrigger(
                        "foo",
                        "ProcessCpuLoad>0.1",
                        "ProcessCpuLoad<0.2",
                        1000,
                        1000,
                        0,
                        "template.jfc");
        when(parser.isValid(any(SmartTriggerReq.class))).thenReturn(true);
        when(parser.parseAttributesFromCondition(anyString())).thenReturn(List.of("ProcessCpuLoad"));
        when(parser.parse(any(SmartTriggerReq.class))).thenReturn(trigger);

        // Trigger should start in NEW state
        MatcherAssert.assertThat(triggerEvaluator.append(req), Matchers.equalTo(List.of("foo")));
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.NEW));
        Script s = Mockito.mock(Script.class);
        ScriptBuilder builder = Mockito.mock(ScriptBuilder.class);
        when(scriptHost.buildScript(anyString())).thenReturn(builder);
        when(builder.withDeclarations(anyList())).thenReturn(builder);
        when(builder.build()).thenReturn(s);

        // Trigger has a duration, and condition is met on first run
        // NEW -> WAITING HIGH
        when(s.execute(any(), anyMap())).thenReturn(true);
        triggerEvaluator.evaluate();
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.WAITING_HIGH));

        // Next Run, condition is not met anymore
        // WAITING HIGH -> WAITING LOW
        when(s.execute(any(), anyMap())).thenReturn(false);
        triggerEvaluator.evaluate();
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.WAITING_LOW));

        // Condition is met again
        // WAITING LOW -> WAITING HIGH
        when(s.execute(any(), anyMap())).thenReturn(true);
        triggerEvaluator.evaluate();
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.WAITING_HIGH));
    }

    @Test
    public void testWaitingHighToRecordingActive() throws ScriptException {
        SmartTriggerReq[] req = {
            new SmartTriggerReq(
                    "ProcessCpuLoad>0.1", 1000, "ProcessCpuLoad<0.2", 1000, 0, "template.jfc")
        };
        SmartTrigger trigger =
                new SmartTrigger(
                        "foo",
                        "ProcessCpuLoad>0.1",
                        "ProcessCpuLoad<0.2",
                        1000,
                        1000,
                        0,
                        "template.jfc");
        when(parser.isValid(any(SmartTriggerReq.class))).thenReturn(true);
        when(parser.parseAttributesFromCondition(anyString())).thenReturn(List.of("ProcessCpuLoad"));
        when(parser.parse(any(SmartTriggerReq.class))).thenReturn(trigger);

        // Trigger should start in NEW state
        MatcherAssert.assertThat(triggerEvaluator.append(req), Matchers.equalTo(List.of("foo")));
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.NEW));
        Script s = Mockito.mock(Script.class);
        ScriptBuilder builder = Mockito.mock(ScriptBuilder.class);
        when(scriptHost.buildScript(anyString())).thenReturn(builder);
        when(builder.withDeclarations(anyList())).thenReturn(builder);
        when(builder.build()).thenReturn(s);

        // Trigger has a duration, and condition is met on first run
        // NEW -> WAITING HIGH
        when(s.execute(any(), anyMap())).thenReturn(true);
        triggerEvaluator.evaluate();
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.WAITING_HIGH));

        // Duration is met and Condition is met
        // WAITING HIGH -> RECORDING ACTIVE
        TemplatedRecording rec = Mockito.mock(TemplatedRecording.class);
        Recording recording = Mockito.mock(Recording.class);
        when(helper.createRecordingWithPredefinedTemplate(anyString()))
                .thenReturn(Optional.of(rec));
        when(rec.getRecording()).thenReturn(recording);
        Mockito.doNothing().when(recording).setName(anyString());
        Mockito.doNothing().when(recording).start();
        when(recording.getId()).thenReturn(1234l);
        trigger.setTimeConditionFirstMet(new Date(0));
        when(s.execute(any(), anyMap())).thenReturn(true);
        triggerEvaluator.evaluate();
        MatcherAssert.assertThat(
                trigger.getState(), Matchers.equalTo(TriggerState.RECORDING_ACTIVE));
        verify(helper, Mockito.atLeastOnce()).createRecordingWithPredefinedTemplate(anyString());
        verify(recording, Mockito.atLeastOnce()).start();
    }

    @Test
    public void testRecordingActiveToRecordingStopping() throws ScriptException {
        SmartTriggerReq[] req = {
            new SmartTriggerReq(
                    "ProcessCpuLoad>0.1", 1000, "ProcessCpuLoad<0.2", 1000, 0, "template.jfc")
        };
        SmartTrigger trigger =
                new SmartTrigger(
                        "foo",
                        "ProcessCpuLoad>0.1",
                        "ProcessCpuLoad<0.2",
                        1000,
                        1000,
                        0,
                        "template.jfc");
        when(parser.isValid(any(SmartTriggerReq.class))).thenReturn(true);
        when(parser.parseAttributesFromCondition(anyString())).thenReturn(List.of("ProcessCpuLoad"));
        when(parser.parse(any(SmartTriggerReq.class))).thenReturn(trigger);

        // Trigger should start in NEW state
        MatcherAssert.assertThat(triggerEvaluator.append(req), Matchers.equalTo(List.of("foo")));
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.NEW));
        Script s = Mockito.mock(Script.class);
        ScriptBuilder builder = Mockito.mock(ScriptBuilder.class);
        when(scriptHost.buildScript(anyString())).thenReturn(builder);
        when(builder.withDeclarations(anyList())).thenReturn(builder);
        when(builder.build()).thenReturn(s);

        // Trigger has a duration, and condition is met on first run
        // NEW -> WAITING HIGH
        when(s.execute(any(), anyMap())).thenReturn(true);
        triggerEvaluator.evaluate();
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.WAITING_HIGH));

        // Duration is met and Condition is met
        // WAITING HIGH -> RECORDING ACTIVE
        TemplatedRecording rec = Mockito.mock(TemplatedRecording.class);
        Recording recording = Mockito.mock(Recording.class);
        when(helper.createRecordingWithPredefinedTemplate(anyString()))
                .thenReturn(Optional.of(rec));
        when(rec.getRecording()).thenReturn(recording);
        Mockito.doNothing().when(recording).setName(anyString());
        Mockito.doNothing().when(recording).start();
        when(recording.getId()).thenReturn(1234l);
        trigger.setTimeConditionFirstMet(new Date(0));
        when(s.execute(any(), anyMap())).thenReturn(true);
        triggerEvaluator.evaluate();
        MatcherAssert.assertThat(
                trigger.getState(), Matchers.equalTo(TriggerState.RECORDING_ACTIVE));
        verify(helper, Mockito.atLeastOnce()).createRecordingWithPredefinedTemplate(anyString());
        verify(recording, Mockito.atLeastOnce()).start();

        // Stop Condition is met
        // RECORDING ACTIVE -> RECORDING STOPPING
        when(s.execute(any(), anyMap())).thenReturn(true);
        triggerEvaluator.evaluate();
        MatcherAssert.assertThat(
                trigger.getState(), Matchers.equalTo(TriggerState.RECORDING_STOPPING));
    }

    @Test
    public void testLifeCycleWithSingleInvocation() throws ScriptException {
        // Invocation count target is set to 1,
        // Expect this trigger to follow the life cycle:
        // NEW -> WAITING HIGH -> RECORDING ACTIVE
        // -> RECORDING STOPPING -> COMPLETE
        SmartTriggerReq[] req = {
            new SmartTriggerReq(
                    "ProcessCpuLoad>0.1", 1000, "ProcessCpuLoad<0.2", 1000, 0, "template.jfc")
        };
        SmartTrigger trigger =
                new SmartTrigger(
                        "foo",
                        "ProcessCpuLoad>0.1",
                        "ProcessCpuLoad<0.2",
                        1000,
                        1000,
                        1,
                        "template.jfc");
        when(parser.isValid(any(SmartTriggerReq.class))).thenReturn(true);
        when(parser.parseAttributesFromCondition(anyString())).thenReturn(List.of("ProcessCpuLoad"));
        when(parser.parse(any(SmartTriggerReq.class))).thenReturn(trigger);

        // Trigger should start in NEW state
        MatcherAssert.assertThat(triggerEvaluator.append(req), Matchers.equalTo(List.of("foo")));
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.NEW));
        Script s = Mockito.mock(Script.class);
        ScriptBuilder builder = Mockito.mock(ScriptBuilder.class);
        when(scriptHost.buildScript(anyString())).thenReturn(builder);
        when(builder.withDeclarations(anyList())).thenReturn(builder);
        when(builder.build()).thenReturn(s);

        // Trigger has a duration, and condition is met on first run
        // NEW -> WAITING HIGH
        when(s.execute(any(), anyMap())).thenReturn(true);
        triggerEvaluator.evaluate();
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.WAITING_HIGH));

        // Duration is met and Condition is met
        // WAITING HIGH -> RECORDING ACTIVE
        TemplatedRecording rec = Mockito.mock(TemplatedRecording.class);
        Recording recording = Mockito.mock(Recording.class);
        when(helper.createRecordingWithPredefinedTemplate(anyString()))
                .thenReturn(Optional.of(rec));
        when(rec.getRecording()).thenReturn(recording);
        Mockito.doNothing().when(recording).setName(anyString());
        Mockito.doNothing().when(recording).start();
        when(recording.getId()).thenReturn(1234l);
        trigger.setTimeConditionFirstMet(new Date(0));
        when(s.execute(any(), anyMap())).thenReturn(true);
        triggerEvaluator.evaluate();
        MatcherAssert.assertThat(
                trigger.getState(), Matchers.equalTo(TriggerState.RECORDING_ACTIVE));
        verify(helper, Mockito.atLeastOnce()).createRecordingWithPredefinedTemplate(anyString());
        verify(recording, Mockito.atLeastOnce()).start();

        // Stop Condition is met
        // RECORDING ACTIVE -> RECORDING STOPPING
        when(s.execute(any(), anyMap())).thenReturn(true);
        triggerEvaluator.evaluate();
        MatcherAssert.assertThat(
                trigger.getState(), Matchers.equalTo(TriggerState.RECORDING_STOPPING));

        // Duration and Stop Condition are met
        // RECORDING STOPPING
        trigger.setTimeStopConditionFirstMet(new Date(0));
        when(s.execute(any(), anyMap())).thenReturn(true);
        when(recording.stop()).thenReturn(true);
        when(recording.getName()).thenReturn("bar");
        // Activation Count was set to 1, so this trigger should complete
        triggerEvaluator.evaluate();
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.COMPLETE));
        verify(recording, Mockito.atLeastOnce()).stop();
    }

    @Test
    public void testLifeCycleWithInvocationCountTarget() throws ScriptException {
        // Invocation count target is set above 1,
        // Expect this trigger to follow the life cycle:
        // NEW -> WAITING HIGH -> RECORDING ACTIVE
        // -> RECORDING STOPPING -> NEW
        SmartTriggerReq[] req = {
            new SmartTriggerReq(
                    "ProcessCpuLoad>0.1", 1000, "ProcessCpuLoad<0.2", 1000, 0, "template.jfc")
        };
        SmartTrigger trigger =
                new SmartTrigger(
                        "foo",
                        "ProcessCpuLoad>0.1",
                        "ProcessCpuLoad<0.2",
                        1000,
                        1000,
                        100,
                        "template.jfc");
        when(parser.isValid(any(SmartTriggerReq.class))).thenReturn(true);
        when(parser.parseAttributesFromCondition(anyString())).thenReturn(List.of("ProcessCpuLoad"));
        when(parser.parse(any(SmartTriggerReq.class))).thenReturn(trigger);

        // Trigger should start in NEW state
        MatcherAssert.assertThat(triggerEvaluator.append(req), Matchers.equalTo(List.of("foo")));
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.NEW));
        Script s = Mockito.mock(Script.class);
        ScriptBuilder builder = Mockito.mock(ScriptBuilder.class);
        when(scriptHost.buildScript(anyString())).thenReturn(builder);
        when(builder.withDeclarations(anyList())).thenReturn(builder);
        when(builder.build()).thenReturn(s);

        // Trigger has a duration, and condition is met on first run
        // NEW -> WAITING HIGH
        when(s.execute(any(), anyMap())).thenReturn(true);
        triggerEvaluator.evaluate();
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.WAITING_HIGH));

        // Duration is met and Condition is met
        // WAITING HIGH -> RECORDING ACTIVE
        TemplatedRecording rec = Mockito.mock(TemplatedRecording.class);
        Recording recording = Mockito.mock(Recording.class);
        when(helper.createRecordingWithPredefinedTemplate(anyString()))
                .thenReturn(Optional.of(rec));
        when(rec.getRecording()).thenReturn(recording);
        Mockito.doNothing().when(recording).setName(anyString());
        Mockito.doNothing().when(recording).start();
        when(recording.getId()).thenReturn(1234l);
        trigger.setTimeConditionFirstMet(new Date(0));
        when(s.execute(any(), anyMap())).thenReturn(true);
        triggerEvaluator.evaluate();
        MatcherAssert.assertThat(
                trigger.getState(), Matchers.equalTo(TriggerState.RECORDING_ACTIVE));
        verify(helper, Mockito.atLeastOnce()).createRecordingWithPredefinedTemplate(anyString());
        verify(recording, Mockito.atLeastOnce()).start();

        // Stop Condition is met
        // RECORDING ACTIVE -> RECORDING STOPPING
        when(s.execute(any(), anyMap())).thenReturn(true);
        triggerEvaluator.evaluate();
        MatcherAssert.assertThat(
                trigger.getState(), Matchers.equalTo(TriggerState.RECORDING_STOPPING));

        // Duration and Stop Condition are met
        // RECORDING STOPPING
        trigger.setTimeStopConditionFirstMet(new Date(0));
        when(s.execute(any(), anyMap())).thenReturn(true);
        when(recording.stop()).thenReturn(true);
        when(recording.getName()).thenReturn("bar");

        // Activation Count was set above 1, so this trigger should return to NEW
        triggerEvaluator.evaluate();
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.NEW));
        verify(recording, Mockito.atLeastOnce()).stop();
    }
}
