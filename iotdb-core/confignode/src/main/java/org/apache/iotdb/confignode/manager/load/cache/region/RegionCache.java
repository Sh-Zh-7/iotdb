/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.confignode.manager.load.cache.region;

import org.apache.iotdb.common.rpc.thrift.TConsensusGroupId;
import org.apache.iotdb.commons.cluster.RegionStatus;
import org.apache.iotdb.confignode.manager.load.cache.AbstractHeartbeatSample;
import org.apache.iotdb.confignode.manager.load.cache.AbstractLoadCache;

import org.apache.tsfile.utils.Pair;

import java.util.Collections;
import java.util.List;

/**
 * RegionCache caches the RegionHeartbeatSamples of a Region. Update and cache the current
 * statistics of the Region based on the latest RegionHeartbeatSample.
 */
public class RegionCache extends AbstractLoadCache {
  private final Pair<Integer, TConsensusGroupId> id;

  public RegionCache(int dataNodeId, TConsensusGroupId gid) {
    super();
    this.id = new Pair<>(dataNodeId, gid);
    this.currentStatistics.set(RegionStatistics.generateDefaultRegionStatistics());
  }

  @Override
  public synchronized void updateCurrentStatistics(boolean forceUpdate) {
    RegionHeartbeatSample lastSample;
    List<AbstractHeartbeatSample> history;
    synchronized (slidingWindow) {
      lastSample = (RegionHeartbeatSample) getLastSample();
      history = Collections.unmodifiableList(slidingWindow);

      RegionStatus status;
      long currentNanoTime = System.nanoTime();
      if (lastSample == null) {
        /* First heartbeat not received from this region, status is UNKNOWN */
        status = RegionStatus.Unknown;
      } else if (!failureDetector.isAvailable(id, history)) {
        /* Failure detector decides that this region is UNKNOWN */
        status = RegionStatus.Unknown;
      } else {
        status = lastSample.getStatus();
      }
      this.currentStatistics.set(new RegionStatistics(currentNanoTime, status));
    }
  }

  public RegionStatistics getCurrentStatistics() {
    return (RegionStatistics) currentStatistics.get();
  }

  public synchronized void cacheHeartbeatSample(
      RegionHeartbeatSample newHeartbeatSample, boolean overwrite) {
    if (overwrite || getLastSample() == null) {
      super.cacheHeartbeatSample(newHeartbeatSample);
      return;
    }
    RegionStatus lastStatus = ((RegionHeartbeatSample) getLastSample()).getStatus();
    if (lastStatus.equals(RegionStatus.Adding) || lastStatus.equals(RegionStatus.Removing)) {
      RegionHeartbeatSample fakeHeartbeatSample =
          new RegionHeartbeatSample(newHeartbeatSample.getSampleLogicalTimestamp(), lastStatus);
      super.cacheHeartbeatSample(fakeHeartbeatSample);
    } else {
      super.cacheHeartbeatSample(newHeartbeatSample);
    }
  }
}
