package org.apache.iotdb.db.queryengine.execution.operator.process.window.function.value;

import org.apache.iotdb.db.queryengine.execution.operator.process.window.function.WindowFunction;
import org.apache.iotdb.db.queryengine.execution.operator.process.window.partition.Partition;

import org.apache.tsfile.block.column.ColumnBuilder;

public class LeadFunction implements WindowFunction {
  private final int channel;
  private final Integer offset;
  private final Integer defaultVal;
  private final boolean ignoreNull;

  public LeadFunction(int channel, Integer offset, Integer defaultVal, boolean ignoreNull) {
    this.channel = channel;
    this.offset = offset == null ? 1 : offset;
    this.defaultVal = defaultVal;
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
    int length = partition.getPositionCount();

    int pos;
    if (ignoreNull) {
      int nonNullCount = 0;
      pos = index + 1;
      while (pos < length) {
        if (!partition.isNull(channel, pos)) {
          nonNullCount++;
          if (nonNullCount == offset) {
            break;
          }
        }

        pos++;
      }
    } else {
      pos = index + offset;
    }

    if (pos < length) {
      if (!partition.isNull(channel, pos)) {
        partition.writeTo(builder, channel, pos);
      } else {
        builder.appendNull();
      }
    } else if (defaultVal != null) {
      // TODO: Replace write object
      builder.writeObject(defaultVal);
    } else {
      builder.appendNull();
    }
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