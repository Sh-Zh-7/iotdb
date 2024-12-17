package org.apache.iotdb.db.queryengine.execution.operator.process.window.function.rank;

import org.apache.iotdb.db.queryengine.execution.operator.process.window.function.WindowFunction;

import org.apache.iotdb.db.queryengine.execution.operator.process.window.partition.Partition;
import org.apache.tsfile.block.column.Column;
import org.apache.tsfile.block.column.ColumnBuilder;

public class CumeDistFunction implements WindowFunction {
  private long count;
  private long currentPeerGroupStart;

  public CumeDistFunction() {
    reset();
  }

  @Override
  public void reset() {
    count = 0;
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
    if (currentPeerGroupStart != peerGroupStart) {
      // New peer group
      currentPeerGroupStart = peerGroupStart;
      count += (peerGroupEnd - peerGroupStart + 1);
    }

    builder.writeDouble(((double) count) / partition.getPositionCount());
  }

  @Override
  public boolean needFrame() {
    return false;
  }
}
