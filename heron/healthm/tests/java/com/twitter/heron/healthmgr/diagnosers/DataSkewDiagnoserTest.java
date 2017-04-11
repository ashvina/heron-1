// Copyright 2016 Twitter. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.twitter.heron.healthmgr.diagnosers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.microsoft.dhalion.api.ISensor;
import com.microsoft.dhalion.metrics.ComponentMetricsData;
import com.microsoft.dhalion.metrics.InstanceMetricsData;
import com.microsoft.dhalion.symptom.ComponentSymptom;
import com.microsoft.dhalion.symptom.Diagnosis;

import org.junit.Test;

import com.twitter.heron.healthmgr.common.HealthManagerContstants;
import com.twitter.heron.healthmgr.detectors.BackPressureDetector;
import com.twitter.heron.healthmgr.sensors.ExecuteCountSensor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DataSkewDiagnoserTest {
  @Test
  public void failsIfOnly1of1InstanceInBP() {
    BackPressureDetector bpDetector = createMockBackPressureDetector(123);
    ExecuteCountSensor exeSensor = createMockExecuteCountSensor(5000);

    DataSkewDiagnoser diagnoser = new DataSkewDiagnoser(bpDetector, exeSensor);
    Diagnosis<ComponentSymptom> result = diagnoser.diagnose();
    assertNull(result);
  }

  @Test
  public void diagnoses1DataSkewInstance() {
    BackPressureDetector bpDetector = createMockBackPressureDetector(123, 0, 0);
    // set execute count above 100%, hence diagnosis should be under provisioning
    ExecuteCountSensor exeSensor = createMockExecuteCountSensor(5000, 2000, 2000);

    DataSkewDiagnoser diagnoser = new DataSkewDiagnoser(bpDetector, exeSensor);
    Diagnosis<ComponentSymptom> result = diagnoser.diagnose();
    assertEquals(1, result.getSymptoms().size());
    ComponentMetricsData data = result.getSymptoms().iterator().next().getMetricsData();
    assertEquals(123,
        data.getMetricValue("container_1_bolt_0",
            HealthManagerContstants.METRIC_INSTANCE_BACK_PRESSURE).intValue());
  }

  public static BackPressureDetector createMockBackPressureDetector(int... bpValues) {
    BackPressureDetector bpDetector = mock(BackPressureDetector.class);
    ComponentMetricsData bpMetrics = new ComponentMetricsData("bolt");

    for (int i = 0; i < bpValues.length; i++) {
      addInstanceMetric(bpMetrics, i, bpValues[i],
          HealthManagerContstants.METRIC_INSTANCE_BACK_PRESSURE);
    }

    Collection<ComponentSymptom> bpSymptoms = new ArrayList<>();
    bpSymptoms.add(ComponentSymptom.from(bpMetrics));
    when(bpDetector.detect()).thenReturn(bpSymptoms);
    return bpDetector;
  }

  public static ExecuteCountSensor createMockExecuteCountSensor(double... exeCounts) {
    ExecuteCountSensor exeSensor = mock(ExecuteCountSensor.class);
    return getMockSensor(HealthManagerContstants.METRIC_EXE_COUNT, exeSensor, exeCounts);
  }

  static <T extends ISensor> T getMockSensor(String metric, T sensor, double... values) {
    ComponentMetricsData metrics = new ComponentMetricsData("bolt");

    for (int i = 0; i < values.length; i++) {
      DataSkewDiagnoserTest.addInstanceMetric(metrics, i, values[i], metric);
    }

    Map<String, ComponentMetricsData> resultMap = new HashMap<>();
    resultMap.put("bolt", metrics);
    when(sensor.get("bolt")).thenReturn(resultMap);
    return sensor;
  }

  static void addInstanceMetric(ComponentMetricsData metrics, int i, double value, String metric) {
    String instanceName = "container_1_bolt_" + i;
    InstanceMetricsData instanceMetric = new InstanceMetricsData(instanceName);
    instanceMetric.addMetric(metric, value);
    metrics.addInstanceMetric(instanceMetric);
  }
}
