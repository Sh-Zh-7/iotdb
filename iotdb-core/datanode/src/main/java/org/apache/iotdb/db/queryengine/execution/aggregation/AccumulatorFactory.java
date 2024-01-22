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

package org.apache.iotdb.db.queryengine.execution.aggregation;

import org.apache.iotdb.common.rpc.thrift.TAggregationType;
import org.apache.iotdb.commons.utils.TestOnly;
import org.apache.iotdb.db.queryengine.plan.expression.Expression;
import org.apache.iotdb.db.queryengine.plan.expression.binary.CompareBinaryExpression;
import org.apache.iotdb.db.queryengine.plan.expression.leaf.ConstantOperand;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Preconditions.checkArgument;

public class AccumulatorFactory {

  public static Accumulator createAccumulator(
      String functionName,
      TAggregationType aggregationType,
      List<TSDataType> inputDataTypes,
      List<Expression> inputExpressions,
      Map<String, String> inputAttributes,
      boolean ascending) {
    return isMultiInputAggregation(aggregationType)
        ? createAccumulatorWithMultiInput(aggregationType, inputDataTypes)
        : createSingleInputAccumulator(
            functionName, aggregationType, inputDataTypes.get(0), inputExpressions, inputAttributes, ascending);
  }

  private static Accumulator createSingleInputAccumulator(
      String functionName,
      TAggregationType aggregationType,
      TSDataType tsDataType,
      List<Expression> inputExpressions,
      Map<String, String> inputAttributes,
      boolean ascending) {
    switch (aggregationType) {
      case COUNT:
        return new CountAccumulator();
      case AVG:
        return new AvgAccumulator(tsDataType);
      case SUM:
        return new SumAccumulator(tsDataType);
      case EXTREME:
        return new ExtremeAccumulator(tsDataType);
      case MAX_TIME:
        return ascending ? new MaxTimeAccumulator() : new MaxTimeDescAccumulator();
      case MIN_TIME:
        return ascending ? new MinTimeAccumulator() : new MinTimeDescAccumulator();
      case MAX_VALUE:
        return new MaxValueAccumulator(tsDataType);
      case MIN_VALUE:
        return new MinValueAccumulator(tsDataType);
      case LAST_VALUE:
        return ascending
            ? new LastValueAccumulator(tsDataType)
            : new LastValueDescAccumulator(tsDataType);
      case FIRST_VALUE:
        return ascending
            ? new FirstValueAccumulator(tsDataType)
            : new FirstValueDescAccumulator(tsDataType);
      case COUNT_IF:
        return new CountIfAccumulator(
            initKeepEvaluator(inputExpressions.get(1)),
            Boolean.parseBoolean(inputAttributes.getOrDefault("ignoreNull", "true")));
      case TIME_DURATION:
        return new TimeDurationAccumulator();
      case MODE:
        return crateModeAccumulator(tsDataType);
      case COUNT_TIME:
        return new CountTimeAccumulator();
      case STDDEV:
      case STDDEV_SAMP:
        return new VarianceAccumulator(tsDataType, VarianceAccumulator.VarianceType.STDDEV_SAMP);
      case STDDEV_POP:
        return new VarianceAccumulator(tsDataType, VarianceAccumulator.VarianceType.STDDEV_POP);
      case VARIANCE:
      case VAR_SAMP:
        return new VarianceAccumulator(tsDataType, VarianceAccumulator.VarianceType.VAR_SAMP);
      case VAR_POP:
        return new VarianceAccumulator(tsDataType, VarianceAccumulator.VarianceType.VAR_POP);
      case UDAF:
        return new UDAFAccumulator(functionName, inputExpressions, tsDataType, inputAttributes);
      default:
        throw new IllegalArgumentException("Invalid Aggregation function: " + aggregationType);
    }
  }

  private static Accumulator crateModeAccumulator(TSDataType tsDataType) {
    switch (tsDataType) {
      case BOOLEAN:
        return new BooleanModeAccumulator();
      case TEXT:
        return new BinaryModeAccumulator();
      case INT32:
        return new IntModeAccumulator();
      case INT64:
        return new LongModeAccumulator();
      case FLOAT:
        return new FloatModeAccumulator();
      case DOUBLE:
        return new DoubleModeAccumulator();
      default:
        throw new IllegalArgumentException("Unknown data type: " + tsDataType);
    }
  }

  public static Accumulator createAccumulatorWithMultiInput(
      TAggregationType aggregationType, List<TSDataType> inputDataTypes) {
    switch (aggregationType) {
      case MAX_BY:
        checkState(inputDataTypes.size() == 2, "Wrong inputDataTypes size.");
        return new MaxByAccumulator(inputDataTypes.get(0), inputDataTypes.get(1));
      default:
        throw new IllegalArgumentException("Invalid Aggregation function: " + aggregationType);
    }
  }

  public static boolean isMultiInputAggregation(TAggregationType aggregationType) {
    switch (aggregationType) {
      case MAX_BY:
        return true;
      default:
        return false;
    }
  }

  @TestOnly
  public static List<Accumulator> createAccumulators(
      List<String> aggregationNames,
      List<TAggregationType> aggregationTypes,
      TSDataType tsDataType,
      List<Expression> inputExpressions,
      Map<String, String> inputAttributes,
      boolean ascending) {
    checkArgument(
        aggregationNames.size() == aggregationTypes.size(),
        "The number of aggregation names does not match aggregation types!");
    List<Accumulator> accumulators = new ArrayList<>();
    for (int i = 0; i < aggregationNames.size(); i++) {
      accumulators.add(
          createAccumulator(
              aggregationNames.get(i),
              aggregationTypes.get(i),
              Collections.singletonList(tsDataType),
              inputExpressions,
              inputAttributes,
              ascending));
    }
    return accumulators;
  }

  @FunctionalInterface
  public interface KeepEvaluator {
    boolean apply(long keep);
  }

  public static KeepEvaluator initKeepEvaluator(Expression keepExpression) {
    // We have check semantic in FE,
    // keep expression must be ConstantOperand or CompareBinaryExpression here
    if (keepExpression instanceof ConstantOperand) {
      return keep -> keep >= Long.parseLong(keepExpression.getExpressionString());
    } else {
      long constant =
          Long.parseLong(
              ((CompareBinaryExpression) keepExpression)
                  .getRightExpression()
                  .getExpressionString());
      switch (keepExpression.getExpressionType()) {
        case LESS_THAN:
          return keep -> keep < constant;
        case LESS_EQUAL:
          return keep -> keep <= constant;
        case GREATER_THAN:
          return keep -> keep > constant;
        case GREATER_EQUAL:
          return keep -> keep >= constant;
        case EQUAL_TO:
          return keep -> keep == constant;
        case NON_EQUAL:
          return keep -> keep != constant;
        default:
          throw new IllegalArgumentException(
              "unsupported expression type: " + keepExpression.getExpressionType());
      }
    }
  }
}
