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

package com.twitter.heron.healthmgr;

import java.util.ArrayList;
import java.util.List;

import com.microsoft.dhalion.detector.Symptom;
import com.microsoft.dhalion.metrics.ComponentMetrics;
import com.microsoft.dhalion.metrics.InstanceMetrics;

import static com.twitter.heron.healthmgr.common.HealthMgrConstants.METRIC_BACK_PRESSURE;
import static com.twitter.heron.healthmgr.common.HealthMgrConstants.METRIC_BUFFER_SIZE;
import static com.twitter.heron.healthmgr.common.HealthMgrConstants.METRIC_EXE_COUNT;
import static com.twitter.heron.healthmgr.common.HealthMgrConstants.SYMPTOM_BACK_PRESSURE;
import static com.twitter.heron.healthmgr.common.HealthMgrConstants.SYMPTOM_GROWING_WAIT_Q;
import static com.twitter.heron.healthmgr.common.HealthMgrConstants.SYMPTOM_LARGE_WAIT_Q;
import static com.twitter.heron.healthmgr.common.HealthMgrConstants.SYMPTOM_PROCESSING_RATE_SKEW;
import static com.twitter.heron.healthmgr.common.HealthMgrConstants.SYMPTOM_SMALL_WAIT_Q;
import static com.twitter.heron.healthmgr.common.HealthMgrConstants.SYMPTOM_UNSATURATEDCOMP_HIGHCONF;
import static com.twitter.heron.healthmgr.common.HealthMgrConstants.SYMPTOM_UNSATURATEDCOMP_LOWCONF;
import static com.twitter.heron.healthmgr.common.HealthMgrConstants.SYMPTOM_WAIT_Q_DISPARITY;


public final class TestUtils {

  private TestUtils() {

  }

  public static List<Symptom> createBpSymptomList(int... bpValues) {
    return createListFromSymptom(createBPSymptom(bpValues));
  }

  public static Symptom createExeCountSymptom(int... exeCounts) {
    return createSymptom(SYMPTOM_PROCESSING_RATE_SKEW, METRIC_EXE_COUNT, exeCounts);
  }

  public static Symptom createWaitQueueDisparitySymptom(int... bufferSizes) {
    return createSymptom(SYMPTOM_WAIT_Q_DISPARITY, METRIC_BUFFER_SIZE, bufferSizes);
  }

  public static Symptom createLargeWaitQSymptom(int... bufferSizes) {
    return createSymptom(SYMPTOM_LARGE_WAIT_Q, METRIC_BUFFER_SIZE, bufferSizes);
  }

  public static Symptom createSmallWaitQSymptom(int... bufferSizes) {
    return createSymptom(SYMPTOM_SMALL_WAIT_Q, METRIC_BUFFER_SIZE, bufferSizes);
  }

  public static Symptom createHighConfUnsaturatedComponentSymptom(int... exeCounts) {
    return createSymptom(SYMPTOM_UNSATURATEDCOMP_HIGHCONF, METRIC_EXE_COUNT, exeCounts);
  }

  public static Symptom createLowConfUnsaturatedComponentSymptom(int... exeCounts) {
    return createSymptom(SYMPTOM_UNSATURATEDCOMP_LOWCONF, METRIC_EXE_COUNT, exeCounts);
  }

  public static Symptom createGrowingWaitingQueueSymptom(int... bufferSizes) {
    return createSymptom(SYMPTOM_GROWING_WAIT_Q, METRIC_BUFFER_SIZE, bufferSizes);
  }

  public static Symptom createBPSymptom(int... bpValues) {
    return createSymptom(SYMPTOM_BACK_PRESSURE, METRIC_BACK_PRESSURE, bpValues);
  }

  public static void addInstanceMetric(ComponentMetrics metrics, int i, double val, String metric) {
    InstanceMetrics instanceMetric = new InstanceMetrics("container_1_bolt_" + i, metric, val);
    metrics.addInstanceMetric(instanceMetric);
  }

  private static Symptom createSymptom(String symptomName, String metricName, int... values) {
    ComponentMetrics compMetrics = new ComponentMetrics("bolt");
    for (int i = 0; i < values.length; i++) {
      addInstanceMetric(compMetrics, i, values[i], metricName);
    }
    return new Symptom(symptomName, compMetrics);
  }

  private static List<Symptom> createListFromSymptom(Symptom symptom) {
    List<Symptom> symptoms = new ArrayList<>();
    symptoms.add(symptom);
    return symptoms;
  }
}
