/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.queryengine.execution.operator.process.window.function.rank;

import org.apache.iotdb.db.queryengine.execution.operator.process.window.function.WindowFunction;
import org.apache.iotdb.db.queryengine.execution.operator.process.window.partition.Partition;

import org.apache.tsfile.block.column.ColumnBuilder;

public class PercentRankFunction implements WindowFunction {
  private long rank;
  private long count;
  private long currentPeerGroupStart;

  public PercentRankFunction() {
    reset();
  }

  @Override
  public void reset() {
    rank = 0;
    count = 1;
    currentPeerGroupStart = -1;
  }

  @Override
  public void transform(
      Partition partition,
      ColumnBuilder builder,
      int index,
      int frameStart,
      int frameEnd,
      int peerGroupStart,
      int peerGroupEnd) {
    int total = partition.getPositionCount();
    if (total == 1) {
      builder.writeDouble(0);
      return;
    }

    if (currentPeerGroupStart != peerGroupStart) {
      // New peer group
      currentPeerGroupStart = peerGroupStart;
      rank += count;
      count = 1;
    } else {
      count++;
    }

    builder.writeDouble(((double) (rank - 1)) / (total - 1));
  }

  @Override
  public boolean needFrame() {
    return false;
  }
}