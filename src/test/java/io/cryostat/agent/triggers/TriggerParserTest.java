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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.cryostat.agent.FlightRecorderHelper;
import io.cryostat.agent.triggers.SmartTrigger.TriggerState;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TriggerParserTest {

    @Mock FlightRecorderHelper helper;

    TriggerParser parser;

    @BeforeEach
    void setup() {
        this.parser = new TriggerParser(helper);
    }

    @ParameterizedTest
    @MethodSource("emptyCases")
    @NullSource
    void testEmptyCases(List<String> args) {
        MatcherAssert.assertThat(
                parser.parse(args == null ? null : args.toArray(new String[0])),
                Matchers.equalTo(List.of()));
    }

    @Test
    void testSingleSimpleTrigger() {
        Mockito.when(helper.isValidTemplate(Mockito.anyString())).thenReturn(true);
        String[] in = new String[] {"[ProcessCpuLoad>0.2]~profile"};
        List<SmartTrigger> out = parser.parse(in);

        MatcherAssert.assertThat(out, Matchers.hasSize(1));
        SmartTrigger trigger = out.get(0);

        MatcherAssert.assertThat(trigger.getExpression(), Matchers.equalTo("ProcessCpuLoad>0.2"));
        MatcherAssert.assertThat(trigger.getRecordingTemplateName(), Matchers.equalTo("profile"));
        MatcherAssert.assertThat(trigger.getDurationConstraint(), Matchers.emptyString());
        MatcherAssert.assertThat(
                trigger.getTriggerCondition(), Matchers.equalTo("ProcessCpuLoad>0.2"));
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.NEW));
        MatcherAssert.assertThat(
                trigger.getTargetDuration(), Matchers.equalTo(Duration.ofSeconds(0)));
        MatcherAssert.assertThat(trigger.getTimeConditionFirstMet(), Matchers.nullValue());
    }

    @Test
    void testSingleComplexTrigger() {
        Mockito.when(helper.isValidTemplate(Mockito.anyString())).thenReturn(true);
        String[] in =
                new String[] {"[ProcessCpuLoad>0.2;TargetDuration>duration(\"30s\")]~profile"};
        List<SmartTrigger> out = parser.parse(in);

        MatcherAssert.assertThat(out, Matchers.hasSize(1));
        SmartTrigger trigger = out.get(0);

        MatcherAssert.assertThat(
                trigger.getExpression(),
                Matchers.equalTo("ProcessCpuLoad>0.2;TargetDuration>duration(\"30s\")"));
        MatcherAssert.assertThat(trigger.getRecordingTemplateName(), Matchers.equalTo("profile"));
        MatcherAssert.assertThat(
                trigger.getDurationConstraint(),
                Matchers.equalTo("TargetDuration>duration(\"30s\")"));
        MatcherAssert.assertThat(
                trigger.getTriggerCondition(), Matchers.equalTo("ProcessCpuLoad>0.2"));
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.NEW));
        MatcherAssert.assertThat(
                trigger.getTargetDuration(), Matchers.equalTo(Duration.ofSeconds(30)));
        MatcherAssert.assertThat(trigger.getTimeConditionFirstMet(), Matchers.nullValue());
    }

    @Test
    void testSingleComplexTriggerWithWhitespace() {
        Mockito.when(helper.isValidTemplate(Mockito.anyString())).thenReturn(true);
        String[] in =
                new String[] {
                    "[ProcessCpuLoad > 0.2 ; TargetDuration > duration(\"30s\")]~profile"
                };
        List<SmartTrigger> out = parser.parse(in);

        MatcherAssert.assertThat(out, Matchers.hasSize(1));
        SmartTrigger trigger = out.get(0);

        MatcherAssert.assertThat(
                trigger.getExpression(),
                Matchers.equalTo("ProcessCpuLoad>0.2;TargetDuration>duration(\"30s\")"));
        MatcherAssert.assertThat(trigger.getRecordingTemplateName(), Matchers.equalTo("profile"));
        MatcherAssert.assertThat(
                trigger.getDurationConstraint(),
                Matchers.equalTo("TargetDuration>duration(\"30s\")"));
        MatcherAssert.assertThat(
                trigger.getTriggerCondition(), Matchers.equalTo("ProcessCpuLoad>0.2"));
        MatcherAssert.assertThat(trigger.getState(), Matchers.equalTo(TriggerState.NEW));
        MatcherAssert.assertThat(
                trigger.getTargetDuration(), Matchers.equalTo(Duration.ofSeconds(30)));
        MatcherAssert.assertThat(trigger.getTimeConditionFirstMet(), Matchers.nullValue());
    }

    @Test
    void testMultipleComplexTriggerWithWhitespace() {
        Mockito.when(helper.isValidTemplate(Mockito.anyString())).thenReturn(true);
        String[] in =
                new String[] {
                    "[ProcessCpuLoad>0.2 ; TargetDuration>duration(\"30s\")]~profile,"
                            + " [(HeapMemoryUsagePercent > 50 && NonHeapMemoryUsage > 1) ||"
                            + " SystemCpuLoad > 4 ; TargetDuration > duration(\"2m\")]~default.jfc"
                };
        List<SmartTrigger> out = parser.parse(in);

        MatcherAssert.assertThat(out, Matchers.hasSize(2));

        SmartTrigger trigger1 = out.get(0);
        MatcherAssert.assertThat(
                trigger1.getExpression(),
                Matchers.equalTo("ProcessCpuLoad>0.2;TargetDuration>duration(\"30s\")"));
        MatcherAssert.assertThat(trigger1.getRecordingTemplateName(), Matchers.equalTo("profile"));
        MatcherAssert.assertThat(
                trigger1.getDurationConstraint(),
                Matchers.equalTo("TargetDuration>duration(\"30s\")"));
        MatcherAssert.assertThat(
                trigger1.getTriggerCondition(), Matchers.equalTo("ProcessCpuLoad>0.2"));
        MatcherAssert.assertThat(trigger1.getState(), Matchers.equalTo(TriggerState.NEW));
        MatcherAssert.assertThat(
                trigger1.getTargetDuration(), Matchers.equalTo(Duration.ofSeconds(30)));
        MatcherAssert.assertThat(trigger1.getTimeConditionFirstMet(), Matchers.nullValue());

        SmartTrigger trigger2 = out.get(1);
        MatcherAssert.assertThat(
                trigger2.getExpression(),
                Matchers.equalTo(
                        "(HeapMemoryUsagePercent>50&&NonHeapMemoryUsage>1)||SystemCpuLoad>4;TargetDuration>duration(\"2m\")"));
        MatcherAssert.assertThat(trigger2.getRecordingTemplateName(), Matchers.equalTo("default"));
        MatcherAssert.assertThat(
                trigger2.getDurationConstraint(),
                Matchers.equalTo("TargetDuration>duration(\"2m\")"));
        MatcherAssert.assertThat(
                trigger2.getTriggerCondition(),
                Matchers.equalTo(
                        "(HeapMemoryUsagePercent>50&&NonHeapMemoryUsage>1)||SystemCpuLoad>4"));
        MatcherAssert.assertThat(trigger2.getState(), Matchers.equalTo(TriggerState.NEW));
        MatcherAssert.assertThat(
                trigger2.getTargetDuration(), Matchers.equalTo(Duration.ofMinutes(2)));
        MatcherAssert.assertThat(trigger2.getTimeConditionFirstMet(), Matchers.nullValue());
    }

    @Test
    void testMultipleComplexTriggerWithWhitespaceWhenOnlyOneTemplateValid() {
        Mockito.when(helper.isValidTemplate(Mockito.anyString()))
                .thenReturn(true)
                .thenReturn(false);
        String[] in =
                new String[] {
                    "[ProcessCpuLoad>0.2 ; TargetDuration>duration(\"30s\")]~profile,"
                            + " [(HeapMemoryUsagePercent > 50 && NonHeapMemoryUsage > 1) ||"
                            + " SystemCpuLoad > 4 ; TargetDuration > duration(\"2m\")]~default.jfc"
                };
        List<SmartTrigger> out = parser.parse(in);

        MatcherAssert.assertThat(out, Matchers.hasSize(1));

        SmartTrigger trigger1 = out.get(0);
        MatcherAssert.assertThat(
                trigger1.getExpression(),
                Matchers.equalTo("ProcessCpuLoad>0.2;TargetDuration>duration(\"30s\")"));
        MatcherAssert.assertThat(trigger1.getRecordingTemplateName(), Matchers.equalTo("profile"));
        MatcherAssert.assertThat(
                trigger1.getDurationConstraint(),
                Matchers.equalTo("TargetDuration>duration(\"30s\")"));
        MatcherAssert.assertThat(
                trigger1.getTriggerCondition(), Matchers.equalTo("ProcessCpuLoad>0.2"));
        MatcherAssert.assertThat(trigger1.getState(), Matchers.equalTo(TriggerState.NEW));
        MatcherAssert.assertThat(
                trigger1.getTargetDuration(), Matchers.equalTo(Duration.ofSeconds(30)));
        MatcherAssert.assertThat(trigger1.getTimeConditionFirstMet(), Matchers.nullValue());
    }

    static List<List<String>> emptyCases() {
        List<List<String>> l = new ArrayList<>();
        l.add(List.of());
        l.add(List.of(""));
        l.add(List.of(" "));
        return l;
    }
}
