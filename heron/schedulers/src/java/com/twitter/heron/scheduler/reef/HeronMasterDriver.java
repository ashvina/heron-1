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

package com.twitter.heron.scheduler.reef;

import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.inject.Inject;

import org.apache.reef.driver.context.ActiveContext;
import org.apache.reef.driver.context.ContextConfiguration;
import org.apache.reef.driver.evaluator.AllocatedEvaluator;
import org.apache.reef.driver.evaluator.EvaluatorRequest;
import org.apache.reef.driver.evaluator.EvaluatorRequestor;
import org.apache.reef.driver.evaluator.FailedEvaluator;
import org.apache.reef.driver.task.TaskConfiguration;
import org.apache.reef.runtime.common.files.REEFFileNames;
import org.apache.reef.tang.Configuration;
import org.apache.reef.tang.annotations.Parameter;
import org.apache.reef.tang.annotations.Unit;
import org.apache.reef.wake.EventHandler;
import org.apache.reef.wake.time.event.StartTime;

import com.twitter.heron.scheduler.SchedulerMain;
import com.twitter.heron.scheduler.reef.HeronConfigurationOptions.Cluster;
import com.twitter.heron.scheduler.reef.HeronConfigurationOptions.Environ;
import com.twitter.heron.scheduler.reef.HeronConfigurationOptions.HeronCorePackageName;
import com.twitter.heron.scheduler.reef.HeronConfigurationOptions.HttpPort;
import com.twitter.heron.scheduler.reef.HeronConfigurationOptions.Role;
import com.twitter.heron.scheduler.reef.HeronConfigurationOptions.TopologyJar;
import com.twitter.heron.scheduler.reef.HeronConfigurationOptions.TopologyName;
import com.twitter.heron.scheduler.reef.HeronConfigurationOptions.TopologyPackageName;
import com.twitter.heron.spi.common.PackingPlan;
import com.twitter.heron.spi.common.PackingPlan.ContainerPlan;
import com.twitter.heron.spi.common.PackingPlan.Resource;
import com.twitter.heron.spi.utils.TopologyUtils;

/**
 * {@link HeronMasterDriver} serves Heron Scheduler by managing containers / processes for Heron
 * TMaster and workers using REEF framework. This includes making container request for topology,
 * providing bits to start Heron components and killing containers.
 */
@Unit
public class HeronMasterDriver {
  static final int TM_MEM_SIZE_MB = 1024;
  static final String TMASTER_CONTAINER_ID = "0";
  static final int MB = 1024 * 1024;
  private static final Logger LOG = Logger.getLogger(HeronMasterDriver.class.getName());
  private final String topologyPackageName;
  private final String heronCorePackageName;
  private final EvaluatorRequestor requestor;
  private final REEFFileNames reefFileNames;
  private final String localHeronConfDir;
  private final String cluster;
  private final String role;
  private final String topologyName;
  private final String env;
  private final String topologyJar;
  private final int httpPort;
  // TODO: Currently REEF does not support YARN AM HA (REEF-345). In absence of this, YARN will kill
  // TODO: all containers if AM container is lost. Subsequently handle precise reconstruction of it
  // TODO: after AM is restarted?
  // Once topology is killed, no more activeContexts should be allowed. This could happen when
  // container allocation is happening and topology is killed concurrently.
  private Map<String, ActiveContext> activeContexts = new ConcurrentHashMap<>();
  // TODO: Let the PackingAlgorithm return homogeneous containers to avoid allocation latencies.
  // TODO: Get rid of this lock. Add a request queue for new and failed Contexts. Avoid blocking any
  // TODO: event thread.
  // Currently yarn does not support mapping container requests to allocation (YARN-4879). As a
  // result it is not possible to concurrently start containers of different sizes. This Queue will
  // ensures containers are started serially. Since one container is allocated at a time, the
  // capacity of this queue is 1. The capacity can be increased once the jira above is fixed.
  private BlockingQueue<AllocatedEvaluator> allocatedContainerQ = new LinkedBlockingDeque<>(1);

  // This map will be needed to make container requests for a failed heron executor
  private Map<String, String> reefContainerToHeronExecutorMap = new ConcurrentHashMap<>();
  private PackingPlan packing;

  @Inject
  public HeronMasterDriver(EvaluatorRequestor requestor,
                           final REEFFileNames fileNames,
                           @Parameter(Cluster.class) String cluster,
                           @Parameter(Role.class) String role,
                           @Parameter(TopologyName.class) String topologyName,
                           @Parameter(Environ.class) String env,
                           @Parameter(TopologyJar.class) String topologyJar,
                           @Parameter(TopologyPackageName.class) String topologyPackageName,
                           @Parameter(HeronCorePackageName.class) String heronCorePackageName,
                           @Parameter(HttpPort.class) int httpPort) throws IOException {

    // REEF related initialization
    this.requestor = requestor;
    this.reefFileNames = fileNames;

    // Heron related initialization
    this.localHeronConfDir = ".";
    this.cluster = cluster;
    this.role = role;
    this.topologyName = topologyName;
    this.topologyPackageName = topologyPackageName;
    this.heronCorePackageName = heronCorePackageName;
    this.env = env;
    this.topologyJar = topologyJar;
    this.httpPort = httpPort;

    // This instance of Driver will be used for managing topology containers
    HeronMasterDriverProvider.setInstance(this);
  }

  /**
   * Requests container for TMaster as container/executor id 0.
   */
  void scheduleTMasterContainer() {
    LOG.log(Level.INFO, "Scheduling container for TM: {0}", topologyName);
    try {
      // TODO: Co-locate TMaster and Scheduler to avoid issues caused by network partitioning.
      launchContainerForExecutor(TMASTER_CONTAINER_ID, 1, TM_MEM_SIZE_MB);
    } catch (InterruptedException e) {
      // Deployment of topology fails if there is a error starting TMaster
      throw new RuntimeException("Error while waiting for topology master container allocation", e);
    }
  }

  /**
   * Container allocation is asynchronous. Request containers serially to ensure allocated resources
   * match the required resources
   */
  void scheduleHeronWorkers(PackingPlan topologyPacking) {
    this.packing = topologyPacking;
    for (Entry<String, ContainerPlan> entry : topologyPacking.containers.entrySet()) {
      Resource reqResource = entry.getValue().resource;

      int mem = getMemInMBForExecutor(reqResource);
      try {
        launchContainerForExecutor(entry.getKey(), getCpuForExecutor(reqResource), mem);
      } catch (InterruptedException e) {
        LOG.log(Level.WARNING, "Error while waiting for container allocation for workers; "
            + "Continue container request for remaining workers", e);
        // TODO: Just log a WARNING without any actions for now. Need to resubmit failed requests
        // TODO: and track number of retries
      }
    }
  }

  public void killTopology() {
    LOG.log(Level.INFO, "Kill topology: {0}", topologyName);
    Map<String, ActiveContext> contexts = clearActiveContexts();

    for (Entry<String, ActiveContext> entry : contexts.entrySet()) {
      LOG.log(Level.INFO, "Close context: {0}", entry.getKey());
      entry.getValue().close();
    }
  }

  private void launchContainerForExecutor(String executorId, int cpu, int mem)
      throws InterruptedException {
    AllocatedEvaluator evaluator = allocateContainer(executorId, cpu, mem);

    Configuration context = createContextConfig(executorId);

    LOG.log(Level.INFO,
        "Activating container {0} for heron executor, id: {1}",
        new Object[]{evaluator.getId(), executorId});
    evaluator.submitContext(context);
    reefContainerToHeronExecutorMap.put(evaluator.getId(), executorId);
  }

  synchronized AllocatedEvaluator allocateContainer(String id, int cpu, int mem)
      throws InterruptedException {
    EvaluatorRequest evaluatorRequest = createEvaluatorRequest(cpu, mem);

    LOG.log(Level.INFO, "Requesting container for executor, id: {0}", id);
    requestor.submit(evaluatorRequest);

    AllocatedEvaluator evaluator = allocatedContainerQ.take();
    LOG.log(Level.INFO,
        "Container {0} is allocated for executor, id: {1}",
        new Object[]{evaluator.getId(), id});
    return evaluator;
  }

  Configuration createContextConfig(String executorId) {
    return ContextConfiguration.CONF
        .set(ContextConfiguration.IDENTIFIER, executorId)
        .build();
  }

  EvaluatorRequest createEvaluatorRequest(int cpu, int mem) {
    return EvaluatorRequest
        .newBuilder()
        .setNumber(1)
        .setMemory(mem)
        .setNumberOfCores(cpu)
        .build();
  }

  private int getCpuForExecutor(Resource resource) {
    return resource.cpu.intValue();
  }

  private int getMemInMBForExecutor(Resource resource) {
    Long ram = resource.ram / MB;
    return ram.intValue();
  }

  String getPackingAsString() {
    return TopologyUtils.packingToString(packing);
  }

  synchronized boolean addActiveContext(ActiveContext context) {
    if (activeContexts == null) {
      LOG.log(Level.WARNING, "Topology has been killed, new context ignored and closed.");
      context.close();
      return false;
    }

    String key = context.getId();
    ActiveContext orphanedContext = activeContexts.get(key);
    if (orphanedContext != null) {
      LOG.log(Level.WARNING, "Found orphaned context for id: {0}, will close it", key);
      orphanedContext.close();
    }

    activeContexts.put(key, context);
    return true;
  }

  synchronized Map<String, ActiveContext> clearActiveContexts() {
    Map<String, ActiveContext> result = activeContexts;
    activeContexts = null;
    return result;
  }

  /**
   * {@link HeronSchedulerLauncher} is the first class initialized on the server by REEF. This is
   * responsible for unpacking binaries and launching Heron Scheduler.
   */
  class HeronSchedulerLauncher implements EventHandler<StartTime> {
    @Override
    public void onNext(StartTime value) {
      String globalFolder = reefFileNames.getGlobalFolder().getPath();

      HeronReefUtils.extractPackageInSandbox(globalFolder, topologyPackageName, localHeronConfDir);
      HeronReefUtils.extractPackageInSandbox(globalFolder, heronCorePackageName, localHeronConfDir);

      launchScheduler();
    }

    private void launchScheduler() {
      try {
        // initialize the scheduler with the options
        SchedulerMain schedulerMain = new SchedulerMain(cluster,
            role,
            env,
            topologyName,
            topologyJar,
            httpPort);
        schedulerMain.runScheduler();
      } catch (IOException e) {
        throw new RuntimeException("Failed to launch Heron Scheduler", e);
      }
    }
  }

  /**
   * Initializes worker on the allocated container
   */
  class HeronContainerAllocationHandler implements EventHandler<AllocatedEvaluator> {
    @Override
    public void onNext(AllocatedEvaluator evaluator) {
      try {
        allocatedContainerQ.put(evaluator);
      } catch (InterruptedException e) {
        LOG.log(Level.WARNING, "Unexpected error while waiting for consumer to use allocated"
            + " container. Evaluator will be destroyed " + evaluator.getId(), e);
        evaluator.close();
        // TODO: Just log a WARNING without any actions for now. This should not happen as only one
        // TODO: container request is created at a time.
      }
    }
  }

  /**
   * Initializes worker on the allocated container
   */
  class HeronExecutorContainerErrorHandler implements EventHandler<FailedEvaluator> {
    @Override
    public void onNext(FailedEvaluator evaluator) {
      synchronized (HeronMasterDriver.class) {
        String executorId = reefContainerToHeronExecutorMap.get(evaluator.getId());
        LOG.log(Level.WARNING,
            "Container:{0} executor:{1} failed",
            new Object[]{evaluator.getId(), executorId});
        if (executorId == null) {
          LOG.log(Level.SEVERE,
              "Unknown executorId for failed container: {0}, skip renew action",
              evaluator.getId());
          return;
        }

        // TODO verify if this thread can be used to submit a new request
        try {
          if (executorId.equals(TMASTER_CONTAINER_ID)) {
            launchContainerForExecutor(TMASTER_CONTAINER_ID, 1, TM_MEM_SIZE_MB);
          } else {
            if (packing.containers.get(executorId) == null) {
              LOG.log(Level.SEVERE,
                  "Missing container {0} in packing, skipping container request",
                  executorId);
              return;
            }
            Resource reqResource = packing.containers.get(executorId).resource;
            launchContainerForExecutor(executorId,
                getCpuForExecutor(reqResource),
                getMemInMBForExecutor(reqResource));
          }
        } catch (InterruptedException e) {
          LOG.log(Level.WARNING, "Error waiting for container allocation for failed executor; "
              + "Assuming request was submitted and continuing" + executorId, e);
        }
      }
    }
  }

  /**
   * Once the container starts, this class starts Heron's executor process. Heron executor is
   * started as a task. This task can be killed and the container can be reused.
   */
  public final class HeronExecutorLauncher implements EventHandler<ActiveContext> {
    @Override
    public void onNext(ActiveContext context) {
      boolean result = addActiveContext(context);
      if (!result) {
        return;
      }

      String id = context.getId();
      LOG.log(Level.INFO, "Submitting evaluator task for id: {0}", id);

      // topologyName and other configurations are required by Heron Executor and Task to load
      // configuration files. Using REEF configuration model is better than depending on external
      // persistence.
      final Configuration taskConf = HeronTaskConfiguration.CONF
          .set(TaskConfiguration.TASK, HeronExecutorTask.class)
          .set(TaskConfiguration.IDENTIFIER, id)
          .set(HeronTaskConfiguration.TOPOLOGY_NAME, topologyName)
          .set(HeronTaskConfiguration.TOPOLOGY_JAR, topologyJar)
          .set(HeronTaskConfiguration.TOPOLOGY_PACKAGE_NAME, topologyPackageName)
          .set(HeronTaskConfiguration.HERON_CORE_PACKAGE_NAME, heronCorePackageName)
          .set(HeronTaskConfiguration.ROLE, role)
          .set(HeronTaskConfiguration.ENV, env)
          .set(HeronTaskConfiguration.CLUSTER, cluster)
          .set(HeronTaskConfiguration.PACKED_PLAN, getPackingAsString())
          .set(HeronTaskConfiguration.CONTAINER_ID, id)
          .build();
      context.submitTask(taskConf);
    }
  }
}
