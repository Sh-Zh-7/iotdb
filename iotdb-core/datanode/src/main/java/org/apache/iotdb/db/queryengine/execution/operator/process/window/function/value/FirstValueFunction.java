package org.apache.iotdb.db.queryengine.execution.operator.process.window.function.value;

import org.apache.iotdb.db.queryengine.execution.operator.process.window.function.WindowFunction;

import org.apache.iotdb.db.queryengine.execution.operator.process.window.partition.Partition;
import org.apache.tsfile.block.column.Column;
import org.apache.tsfile.block.column.ColumnBuilder;

public class FirstValueFunction implements WindowFunction {
  private final int channel;
  private final boolean ignoreNull;

  public FirstValueFunction(int channel, boolean ignoreNull) {
    this.channel = channel;
    this.ignoreNull = ignoreNull;
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
    // Empty frame
    if (frameStart < 0) {
      builder.appendNull();
      return;
    }

    if (ignoreNull) {
      // Handle nulls
      int pos = index;
      while (pos <= frameEnd && partition.isNull(channel, pos)) {
        pos++;
      }

      if (pos > frameEnd) {
        builder.appendNull();
      } else {
        partition.writeTo(builder, channel, pos);
      }
    } else {
      partition.writeTo(builder, channel, frameEnd);
    }
  }

  @Override
  public boolean needPeerGroup() {
    return false;
  }
}
