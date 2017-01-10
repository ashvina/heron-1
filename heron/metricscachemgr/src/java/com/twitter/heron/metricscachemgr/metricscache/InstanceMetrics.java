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
package com.twitter.heron.metricscachemgr.metricscache;


import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import com.twitter.heron.proto.tmaster.TopologyMaster;
import com.twitter.heron.spi.metricsmgr.metrics.MetricsFilter.MetricAggregationType;

// Most granualar metrics/exception store level. This store exception and metrics
// associated with an instance.
public class InstanceMetrics {
  private static final Logger LOG = Logger.getLogger(InstanceMetrics.class.getName());
  private String instanceId;
  private int nbuckets;
  private int bucketInterval;
  // map between metric name and its values
  private Map<String, Metric> metrics;

  // ctor. '_instanceId' is the id generated by heron. '_nbuckets' number of metrics buckets
  // stored for instances belonging to this component.
  public InstanceMetrics(String instanceId, int nbuckets, int bucketInterval) {
    this.instanceId = instanceId;
    this.nbuckets = nbuckets;
    this.bucketInterval = bucketInterval;

    metrics = new HashMap<>();
  }

  // Clear old metrics associated with this instance.
  public void Purge() {
    for (Metric m : metrics.values()) {
      m.Purge();
    }
  }

  // Add metrics with name '_name' of type '_type' and value _value.
  public void AddMetricWithName(String name, MetricAggregationType type,
                                String value) {
    Metric metricData = GetOrCreateMetric(name, type);
    LOG.info("AddMetricWithName name " + name + "; type " + type + "; value " + value);
    metricData.AddValueToMetric(value);
  }

  // Returns the metric metrics. Doesn't own _response.
  public void GetMetrics(TopologyMaster.MetricRequest request, long startTime, long endTime,
                         TopologyMaster.MetricResponse.Builder builder) {
    TopologyMaster.MetricResponse.TaskMetric.Builder tm =
        TopologyMaster.MetricResponse.TaskMetric.newBuilder();

    tm.setInstanceId(instanceId);
    for (int i = 0; i < request.getMetricCount(); ++i) {
      String id = request.getMetric(i);
      if (metrics.containsKey(id)) {
        TopologyMaster.MetricResponse.IndividualMetric im =
            metrics.get(id).GetMetrics(request.getMinutely(), startTime, endTime);
        tm.addMetric(im);
      }
    }

    builder.addMetric(tm);
  }

  // Create or return existing Metric. Retains ownership of Metric object returned.
  private Metric GetOrCreateMetric(String name, MetricAggregationType type) {
    if (!metrics.containsKey(name)) {
      metrics.put(name, new Metric(name, type, nbuckets, bucketInterval));
    }
    return metrics.get(name);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("instance: " + instanceId).append(", nbuckets: " + nbuckets)
        .append(", interval: " + bucketInterval).append(", data:\n");
    for (String k : metrics.keySet()) {
      sb.append("{" + k + " ::> \n").append(metrics.get(k)).append("}");
    }
    return sb.toString();
  }

  public void GetMetrics(MetricsCacheQueryUtils.MetricCacheRequest request, long startTime, long endTime,
                         MetricsCacheQueryUtils.MetricCacheResponse response) {
    MetricsCacheQueryUtils.TaskMetric tm =
        new MetricsCacheQueryUtils.TaskMetric();

    tm.instanceId = instanceId;
    for (int i = 0; i < request.metric.size(); ++i) {
      String id = request.metric.get(i);
      if (metrics.containsKey(id)) {
        MetricsCacheQueryUtils.IndividualMetric im = new MetricsCacheQueryUtils.IndividualMetric();
        metrics.get(id).GetMetrics(request.minutely, startTime, endTime, im);
        tm.metric.add(im);
      }
    }

    response.metric.add(tm);
  }
}
