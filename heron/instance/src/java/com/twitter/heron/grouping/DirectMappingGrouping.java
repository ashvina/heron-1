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
package com.twitter.heron.grouping;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Logger;

import com.twitter.heron.api.grouping.CustomStreamGrouping;
import com.twitter.heron.api.topology.TopologyContext;

public class DirectMappingGrouping implements CustomStreamGrouping {
  private static final Logger LOG = Logger.getLogger(DirectMappingGrouping.class.getName());
  private static final long serialVersionUID = 1913733461146490337L;

  private List<Integer> localTargetTasksIds = new ArrayList<>();
  private List<Integer> remoteTargetTaskIds = new ArrayList<>();
  private int roundRobinIndex = 0;

  @Override
  public void prepare(TopologyContext context,
                      String component,
                      String streamId,
                      List<Integer> targetTasks) {
    HashSet<Integer> localTasksIds = new HashSet<>();
    try {
      BufferedReader br = new BufferedReader(new FileReader("global_task_id_file"));
      String id;
      while ((id = br.readLine()) != null) {
        localTasksIds.add(Integer.parseInt(id));
      }
      System.out.println("Task ids local to this container: " + localTasksIds);
      br.close();
    } catch (IOException e) {
      e.printStackTrace();
    } catch (NumberFormatException e) {
      e.printStackTrace();
    }

    for (int targetTask : targetTasks) {
      if (localTasksIds.contains(targetTask)) {
        localTargetTasksIds.add(targetTask);
      } else {
        remoteTargetTaskIds.add(targetTask);
      }
    }
  }

  @Override
  public List<Integer> chooseTasks(List<Object> values) {
    List<Integer> result = new ArrayList<>();
    int size = localTargetTasksIds.size();
    if (size > 0) {
      result.add(localTargetTasksIds.get(roundRobinIndex++));
      roundRobinIndex %= size;
    } else {
      size = remoteTargetTaskIds.size();
      result.add(remoteTargetTaskIds.get(roundRobinIndex++));
      roundRobinIndex %= size;
    }

    return result;
  }
}
