package org.apache.iotdb.db.queryengine.execution.operator.process.window.function.value;

import org.apache.iotdb.db.queryengine.execution.operator.process.window.TableWindowOperatorTestUtils;
import org.apache.iotdb.db.queryengine.execution.operator.process.window.function.FunctionTestUtils;
import org.apache.iotdb.db.queryengine.execution.operator.process.window.partition.PartitionExecutor;
import org.apache.iotdb.db.queryengine.execution.operator.process.window.partition.frame.FrameInfo;
import org.apache.tsfile.block.column.Column;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.read.common.block.TsBlock;
import org.apache.tsfile.read.common.block.TsBlockBuilder;
import org.apache.tsfile.read.common.block.column.RunLengthEncodedColumn;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.apache.iotdb.db.queryengine.execution.operator.source.relational.TableScanOperator.TIME_COLUMN_TEMPLATE;

public class FirstValueFunctionTest {
  private final List<TSDataType> inputDataTypes = Collections.singletonList(TSDataType.INT32);
  // Inputs element less than 0 means this pos is null
  private final int[] inputs = {0, -1, -1, 1, 2, -1, 3, 4, -1, 5, 6, -1, -1, -1, -1, -1};

  private final List<TSDataType> outputDataTypes = Arrays.asList(TSDataType.INT32, TSDataType.INT32);

  @Test
  public void testFirstValueFunctionIgnoreNull() {
    int[] expected = {0, 0, 0, 1, 1, 1, 2, 3, 3, 4, 5, 5, 6, -1, -1, -1};

    TsBlock tsBlock = TableWindowOperatorTestUtils.createIntsTsBlockWithoutNulls(inputs);
    FirstValueFunction function = new FirstValueFunction(0, true);
    FrameInfo frameInfo = new FrameInfo(FrameInfo.FrameType.ROWS, FrameInfo.FrameBoundType.PRECEDING, 2, FrameInfo.FrameBoundType.FOLLOWING, 2);
    PartitionExecutor partitionExecutor = FunctionTestUtils.createPartitionExecutor(tsBlock, inputDataTypes, function, frameInfo);

    TsBlockBuilder tsBlockBuilder = new TsBlockBuilder(expected.length, outputDataTypes);
    while (partitionExecutor.hasNext()) {
      partitionExecutor.processNextRow(tsBlockBuilder);
    }

    TsBlock result = tsBlockBuilder.build(
        new RunLengthEncodedColumn(
            TIME_COLUMN_TEMPLATE, tsBlockBuilder.getPositionCount()));
    Column column = result.getColumn(1);

    Assert.assertEquals(column.getPositionCount(), expected.length);
    for (int i = 0; i < expected.length; i++) {
      if (expected[i] < 0) {
        Assert.assertTrue(column.isNull(i));
      } else {
        Assert.assertEquals(column.getInt(i), expected[i]);
      }
    }
  }

  @Test
  public void testFirstValueFunctionNotIgnoreNull() {
    int[] expected = {0, 0, 0, -1, -1, 1, 2, -1, 3, 4, -1, 5, 6, -1, -1, -1};

    TsBlock tsBlock = TableWindowOperatorTestUtils.createIntsTsBlockWithoutNulls(inputs);
    FirstValueFunction function = new FirstValueFunction(0, false);
    FrameInfo frameInfo = new FrameInfo(FrameInfo.FrameType.ROWS, FrameInfo.FrameBoundType.PRECEDING, 2, FrameInfo.FrameBoundType.FOLLOWING, 2);
    PartitionExecutor partitionExecutor = FunctionTestUtils.createPartitionExecutor(tsBlock, inputDataTypes, function, frameInfo);

    TsBlockBuilder tsBlockBuilder = new TsBlockBuilder(expected.length, outputDataTypes);
    while (partitionExecutor.hasNext()) {
      partitionExecutor.processNextRow(tsBlockBuilder);
    }

    TsBlock result = tsBlockBuilder.build(
        new RunLengthEncodedColumn(
            TIME_COLUMN_TEMPLATE, tsBlockBuilder.getPositionCount()));
    Column column = result.getColumn(1);

    Assert.assertEquals(column.getPositionCount(), expected.length);
    for (int i = 0; i < expected.length; i++) {
      if (expected[i] < 0) {
        Assert.assertTrue(column.isNull(i));
      } else {
        Assert.assertEquals(column.getInt(i), expected[i]);
      }
    }
  }
}
