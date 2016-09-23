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

package com.twitter.heron.examples;

import java.util.Map;

import com.twitter.heron.api.Config;
import com.twitter.heron.api.HeronSubmitter;
import com.twitter.heron.api.bolt.BaseRichBolt;
import com.twitter.heron.api.bolt.OutputCollector;
import com.twitter.heron.api.grouping.CustomStreamGrouping;
import com.twitter.heron.api.spout.BaseRichSpout;
import com.twitter.heron.api.spout.SpoutOutputCollector;
import com.twitter.heron.api.topology.OutputFieldsDeclarer;
import com.twitter.heron.api.topology.TopologyBuilder;
import com.twitter.heron.api.topology.TopologyContext;
import com.twitter.heron.api.tuple.Fields;
import com.twitter.heron.api.tuple.Tuple;
import com.twitter.heron.api.tuple.Values;
import com.twitter.heron.grouping.LocalAffinityGrouping;
import com.twitter.heron.grouping.RemoteAffinityGrouping;

import backtype.storm.metric.api.GlobalMetrics;

public final class NetworkBoundDirectTopology {

  private NetworkBoundDirectTopology() {
  }

  public static void main(String[] args) throws Exception {
    TopologyBuilder builder = new TopologyBuilder();

    int noSpouts = Integer.parseInt(args[1]);
    int noBolts = Integer.parseInt(args[2]);
    int localAffinity = Integer.parseInt(args[3]);
    double instancesPerContainer = 4;
    if (args.length >= 5) {
      instancesPerContainer = Integer.parseInt(args[4]);
    }

    CustomStreamGrouping grouping = new RemoteAffinityGrouping();
    if (localAffinity == 1) {
      grouping = new LocalAffinityGrouping();
    }
    System.out.println("Grouping used: " + grouping.getClass().getName());
    builder.setSpout("word", new NetworkBoundTopology.NetworkSpout(), noSpouts);
    builder.setBolt("exclaim1", new NetworkBoundTopology.NetworkBolt(), noBolts).
        customGrouping("word", grouping);

    Config conf = new Config();
    conf.setDebug(true);
    conf.setMaxSpoutPending(10);
    conf.put(Config.TOPOLOGY_WORKER_CHILDOPTS, "-XX:+HeapDumpOnOutOfMemoryError");
    conf.setEnableAcking(true);

    if (args != null && args.length > 0) {
      conf.setNumStmgrs((int) Math.ceil((noBolts + noSpouts)/instancesPerContainer));
      HeronSubmitter.submitTopology(args[0], conf, builder.createTopology());
    }
  }

  public static class NetworkBolt extends BaseRichBolt {

    private static final long serialVersionUID = 1184860508880121352L;
    private long nItems;
    private long startTime;
    private OutputCollector collector;

    @Override
    @SuppressWarnings("rawtypes")
    public void prepare(
        Map conf,
        TopologyContext context,
        OutputCollector col) {
      nItems = 0;
      startTime = System.currentTimeMillis();
      this.collector = col;
    }

    @Override
    public void execute(Tuple tuple) {
      if (++nItems % 100000 == 0) {
        long latency = System.currentTimeMillis() - startTime;
        System.out.println("Bolt processed " + nItems + " tuples in " + latency + " ms");
        GlobalMetrics.incr("selected_items");
        collector.ack(tuple);
      } else {
        collector.ack(tuple);
      }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
      declarer.declare(new Fields("word"));
    }
  }

  public static class NetworkSpout extends BaseRichSpout {

    private static final long serialVersionUID = -3217886193225455451L;
    private SpoutOutputCollector collector;
    private Values tuple;

    public void open(
        Map<String, Object> conf,
        TopologyContext context,
        SpoutOutputCollector acollector) {
      collector = acollector;
      StringBuffer randStr = new StringBuffer();
      for (int i = 0; i < 4096; i++) {
        randStr.append('a');
      }
      tuple = new Values(randStr.toString());
    }

    public void close() {
    }

    public void nextTuple() {
      for (int i = 0; i < 10000; i++) {
        collector.emit(tuple, "MESSAGE_ID");
      }
    }

    public void ack(Object msgId) {
    }

    public void fail(Object msgId) {
    }

    public void declareOutputFields(OutputFieldsDeclarer declarer) {
      declarer.declare(new Fields("word"));
    }
  }
}
