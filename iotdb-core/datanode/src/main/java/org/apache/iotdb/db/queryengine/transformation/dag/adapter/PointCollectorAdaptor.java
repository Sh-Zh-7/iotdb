package org.apache.iotdb.db.queryengine.transformation.dag.adapter;

import org.apache.iotdb.commons.udf.utils.UDFBinaryTransformer;
import org.apache.iotdb.tsfile.read.common.block.column.Column;
import org.apache.iotdb.tsfile.read.common.block.column.ColumnBuilder;
import org.apache.iotdb.tsfile.read.common.block.column.TimeColumn;
import org.apache.iotdb.tsfile.utils.BytesUtils;
import org.apache.iotdb.udf.api.collector.PointCollector;
import org.apache.iotdb.udf.api.type.Binary;

import java.io.IOException;

public class PointCollectorAdaptor implements PointCollector {
  private final ColumnBuilder timeColumnBuilder;

  private final ColumnBuilder valueColumnBuilder;

  public PointCollectorAdaptor(ColumnBuilder timeColumnBuilder, ColumnBuilder valueColumnBuilder) {
    this.timeColumnBuilder = timeColumnBuilder;
    this.valueColumnBuilder = valueColumnBuilder;
  }

  @Override
  public void putInt(long timestamp, int value) throws IOException {
    timeColumnBuilder.writeLong(timestamp);
    valueColumnBuilder.writeInt(value);
  }

  @Override
  public void putLong(long timestamp, long value) throws IOException {
    timeColumnBuilder.writeLong(timestamp);
    valueColumnBuilder.writeLong(value);
  }

  @Override
  public void putFloat(long timestamp, float value) throws IOException {
    timeColumnBuilder.writeLong(timestamp);
    valueColumnBuilder.writeFloat(value);
  }

  @Override
  public void putDouble(long timestamp, double value) throws IOException {
    timeColumnBuilder.writeLong(timestamp);
    valueColumnBuilder.writeDouble(value);
  }

  @Override
  public void putBoolean(long timestamp, boolean value) throws IOException {
    timeColumnBuilder.writeLong(timestamp);
    valueColumnBuilder.writeBoolean(value);
  }

  @Override
  public void putBinary(long timestamp, Binary value) throws IOException {
    timeColumnBuilder.writeLong(timestamp);
    valueColumnBuilder.writeBinary(UDFBinaryTransformer.transformToBinary(value));
  }

  @Override
  public void putString(long timestamp, String value) throws IOException {
    timeColumnBuilder.writeLong(timestamp);
    valueColumnBuilder.writeBinary(BytesUtils.valueOf(value));
  }

  public void putNull(long timestamp) {
    timeColumnBuilder.writeLong(timestamp);
    valueColumnBuilder.appendNull();
  }

  public TimeColumn buildTimeColumn() {
    return (TimeColumn) timeColumnBuilder.build();
  }

  public Column buildValueColumn() {
    return valueColumnBuilder.build();
  }
}
