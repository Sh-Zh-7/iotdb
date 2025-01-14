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

public class NTileFunction implements WindowFunction {
  private final int n;

  public NTileFunction(int n) {
    this.n = n;
  }

  @Override
  public void reset() {}

  @Override
  public void transform(
      Partition partition,
      ColumnBuilder builder,
      int index,
      int frameStart,
      int frameEnd,
      int peerGroupStart,
      int peerGroupEnd) {
    builder.writeLong(bucket(n, index, partition.getPositionCount()) + 1);
  }

  private long bucket(long buckets, int index, int count) {
    if (count < buckets) {
      return index;
    }

    long remainderRows = count % buckets;
    long rowsPerBucket = count / buckets;

    if (index < ((rowsPerBucket + 1) * remainderRows)) {
      return index / (rowsPerBucket + 1);
    }

    return (index - remainderRows) / rowsPerBucket;
  }

  @Override
  public boolean needPeerGroup() {
    return false;
  }

  @Override
  public boolean needFrame() {
    return false;
  }
}