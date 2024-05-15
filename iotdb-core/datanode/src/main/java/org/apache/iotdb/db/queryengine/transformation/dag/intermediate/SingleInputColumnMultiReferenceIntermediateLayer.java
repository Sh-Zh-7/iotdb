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

package org.apache.iotdb.db.queryengine.transformation.dag.intermediate;

import org.apache.iotdb.db.queryengine.plan.expression.Expression;
import org.apache.iotdb.db.queryengine.transformation.api.LayerReader;
import org.apache.iotdb.db.queryengine.transformation.api.LayerRowWindowReader;
import org.apache.iotdb.db.queryengine.transformation.api.YieldableState;
import org.apache.iotdb.db.queryengine.transformation.dag.adapter.ElasticSerializableTVListBackedSingleColumnWindow;
import org.apache.iotdb.db.queryengine.transformation.dag.memory.SafetyLine;
import org.apache.iotdb.db.queryengine.transformation.dag.memory.SafetyLine.SafetyPile;
import org.apache.iotdb.db.queryengine.transformation.dag.util.LayerCacheUtils;
import org.apache.iotdb.db.queryengine.transformation.dag.util.TransformUtils;
import org.apache.iotdb.db.queryengine.transformation.datastructure.tv.ElasticSerializableTVList;
import org.apache.iotdb.db.queryengine.transformation.datastructure.util.ValueRecorder;
import org.apache.iotdb.db.queryengine.transformation.datastructure.util.iterator.TVListForwardIterator;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.read.common.block.column.Column;
import org.apache.iotdb.tsfile.read.common.block.column.TimeColumn;
import org.apache.iotdb.udf.api.access.RowWindow;
import org.apache.iotdb.udf.api.customizer.strategy.SessionTimeWindowAccessStrategy;
import org.apache.iotdb.udf.api.customizer.strategy.SlidingSizeWindowAccessStrategy;
import org.apache.iotdb.udf.api.customizer.strategy.SlidingTimeWindowAccessStrategy;
import org.apache.iotdb.udf.api.customizer.strategy.StateWindowAccessStrategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class SingleInputColumnMultiReferenceIntermediateLayer extends IntermediateLayer {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(SingleInputColumnMultiReferenceIntermediateLayer.class);

  private final LayerReader parentLayerReader;
  private final TSDataType parentLayerReaderDataType;
  private final boolean isParentLayerReaderConstant;
  private final ElasticSerializableTVList tvList;
  private final SafetyLine safetyLine;

  public SingleInputColumnMultiReferenceIntermediateLayer(
      Expression expression,
      String queryId,
      float memoryBudgetInMB,
      LayerReader parentLayerReader) {
    super(expression, queryId, memoryBudgetInMB);
    this.parentLayerReader = parentLayerReader;

    parentLayerReaderDataType = this.parentLayerReader.getDataTypes()[0];
    isParentLayerReaderConstant = this.parentLayerReader.isConstantPointReader();
    tvList =
        ElasticSerializableTVList.construct(
            parentLayerReaderDataType, queryId, memoryBudgetInMB, CACHE_BLOCK_SIZE);
    safetyLine = new SafetyLine();
  }

  @Override
  public LayerReader constructReader() {
    return new LayerReader() {
      private final SafetyPile safetyPile = safetyLine.addSafetyPile();

      private TimeColumn cachedTimes = null;
      private Column cachedValues = null;
      private int cacheConsumed = 0;
      private TVListForwardIterator iterator = tvList.constructIterator();

      @Override
      public boolean isConstantPointReader() {
        return isParentLayerReaderConstant;
      }

      @Override
      public YieldableState yield() throws Exception {
        // Column cached in reader is not yet consumed
        if (cachedTimes != null && cacheConsumed < cachedTimes.getPositionCount()) {
          return YieldableState.YIELDABLE;
        }

        // TVList still has some cached columns
        if (iterator.hasNext()) {
          iterator.next();
          cachedTimes = iterator.currentTimes();
          cachedValues = iterator.currentValues();

          return YieldableState.YIELDABLE;
        }

        // No data cached, yield from parent layer reader
        YieldableState state = LayerCacheUtils.yieldPoints(parentLayerReader, tvList);
        if (state == YieldableState.YIELDABLE) {
          iterator.next();
          cachedTimes = iterator.currentTimes();
          cachedValues = iterator.currentValues();
        }
        return state;
      }

      @Override
      public void consumed(int consumed) {
        assert cacheConsumed + consumed <= cachedTimes.getPositionCount();
        cacheConsumed += consumed;

        safetyPile.moveForward(consumed);
        tvList.setEvictionUpperBound(safetyLine.getSafetyLine());

        // Invalid cache
        if (cacheConsumed == cachedTimes.getPositionCount()) {
          cacheConsumed = 0;
          cachedTimes = null;
          cachedValues = null;
        }
      }

      @Override
      public void consumedAll() {
        int steps = cachedTimes.getPositionCount() - cacheConsumed;
        safetyPile.moveForward(steps);
        tvList.setEvictionUpperBound(safetyLine.getSafetyLine());

        cacheConsumed = 0;
        cachedTimes = null;
        cachedValues = null;
      }

      @Override
      public Column[] current() {
        return cacheConsumed == 0
            ? new Column[] {cachedValues, cachedTimes}
            : new Column[] {
              cachedValues.subColumn(cacheConsumed), cachedTimes.subColumn(cacheConsumed)
            };
      }

      @Override
      public TSDataType[] getDataTypes() {
        return new TSDataType[] {parentLayerReaderDataType};
      }
    };
  }

  @Override
  protected LayerRowWindowReader constructRowSlidingSizeWindowReader(
      SlidingSizeWindowAccessStrategy strategy, float memoryBudgetInMB) {
    return new LayerRowWindowReader() {

      private final int windowSize = strategy.getWindowSize();
      private final int slidingStep = strategy.getSlidingStep();

      private final SafetyPile safetyPile = safetyLine.addSafetyPile();
      private final ElasticSerializableTVListBackedSingleColumnWindow window =
          new ElasticSerializableTVListBackedSingleColumnWindow(tvList);

      private boolean hasCached = false;
      private int beginIndex = -slidingStep;

      @Override
      public YieldableState yield() throws Exception {
        if (hasCached) {
          return YieldableState.YIELDABLE;
        }

        beginIndex += slidingStep;
        int endIndex = beginIndex + windowSize;
        if (beginIndex < 0 || endIndex < 0) {
          LOGGER.warn(
              "LayerRowWindowReader index overflow. beginIndex: {}, endIndex: {}, windowSize: {}.",
              beginIndex,
              endIndex,
              windowSize);
          return YieldableState.NOT_YIELDABLE_NO_MORE_DATA;
        }

        final int pointsToBeCollected = endIndex - tvList.getPointCount();
        if (pointsToBeCollected > 0) {
          final YieldableState yieldableState =
              LayerCacheUtils.yieldPoints(parentLayerReader, tvList, pointsToBeCollected);
          if (yieldableState == YieldableState.NOT_YIELDABLE_WAITING_FOR_DATA) {
            beginIndex -= slidingStep;
            return YieldableState.NOT_YIELDABLE_WAITING_FOR_DATA;
          }

          if (tvList.getPointCount() <= beginIndex) {
            return YieldableState.NOT_YIELDABLE_NO_MORE_DATA;
          }

          // TVList's size may be less than endIndex
          // When parent layer reader has no more data
          endIndex = Math.min(endIndex, tvList.getPointCount());
        }

        window.seek(beginIndex, endIndex, tvList.getTime(beginIndex), tvList.getTime(endIndex - 1));

        hasCached = true;
        return YieldableState.YIELDABLE;
      }

      @Override
      public void readyForNext() {
        hasCached = false;

        safetyPile.moveForwardTo(beginIndex + 1);
        tvList.setEvictionUpperBound(safetyLine.getSafetyLine());
      }

      @Override
      public TSDataType[] getDataTypes() {
        return new TSDataType[] {parentLayerReaderDataType};
      }

      @Override
      public RowWindow currentWindow() {
        return window;
      }
    };
  }

  @Override
  protected LayerRowWindowReader constructRowSlidingTimeWindowReader(
      SlidingTimeWindowAccessStrategy strategy, float memoryBudgetInMB) {

    final long timeInterval = strategy.getTimeInterval();
    final long slidingStep = strategy.getSlidingStep();
    final long displayWindowEnd = strategy.getDisplayWindowEnd();

    final SafetyPile safetyPile = safetyLine.addSafetyPile();
    final ElasticSerializableTVListBackedSingleColumnWindow window =
        new ElasticSerializableTVListBackedSingleColumnWindow(tvList);

    final long nextWindowTimeBeginGivenByStrategy = strategy.getDisplayWindowBegin();

    return new LayerRowWindowReader() {

      private boolean isFirstIteration = true;
      private boolean hasCached = false;
      private long nextWindowTimeBegin = nextWindowTimeBeginGivenByStrategy;
      private int nextIndexBegin = 0;
      private boolean hasAtLeastOneRow;

      @Override
      public YieldableState yield() throws Exception {
        if (isFirstIteration) {
          if (tvList.getPointCount() == 0) {
            final YieldableState yieldableState =
                LayerCacheUtils.yieldPoints(parentLayerReader, tvList);
            if (yieldableState != YieldableState.YIELDABLE) {
              return yieldableState;
            }
          }
          if (nextWindowTimeBeginGivenByStrategy == Long.MIN_VALUE) {
            // display window begin should be set to the same as the min timestamp of the query
            // result set
            nextWindowTimeBegin = tvList.getTime(0);
          }
          hasAtLeastOneRow = tvList.getPointCount() != 0;
          isFirstIteration = false;
        }

        if (hasCached) {
          return YieldableState.YIELDABLE;
        }
        if (!hasAtLeastOneRow || displayWindowEnd <= nextWindowTimeBegin) {
          return YieldableState.NOT_YIELDABLE_NO_MORE_DATA;
        }

        long nextWindowTimeEnd = Math.min(nextWindowTimeBegin + timeInterval, displayWindowEnd);
        while (tvList.getTime(tvList.getPointCount() - 1) < nextWindowTimeEnd) {
          final YieldableState yieldableState =
              LayerCacheUtils.yieldPoints(parentLayerReader, tvList);
          if (yieldableState == YieldableState.NOT_YIELDABLE_WAITING_FOR_DATA) {
            return YieldableState.NOT_YIELDABLE_WAITING_FOR_DATA;
          }
          if (yieldableState == YieldableState.NOT_YIELDABLE_NO_MORE_DATA) {
            break;
          }
        }

        for (int i = nextIndexBegin; i < tvList.getPointCount(); ++i) {
          if (nextWindowTimeBegin <= tvList.getTime(i)) {
            nextIndexBegin = i;
            break;
          }
          if (i == tvList.getPointCount() - 1) {
            nextIndexBegin = tvList.getPointCount();
          }
        }

        int nextIndexEnd = tvList.getPointCount();
        for (int i = nextIndexBegin; i < tvList.getPointCount(); ++i) {
          if (nextWindowTimeEnd <= tvList.getTime(i)) {
            nextIndexEnd = i;
            break;
          }
        }

        if ((nextIndexEnd == nextIndexBegin)
            && nextWindowTimeEnd < tvList.getTime(tvList.getPointCount() - 1)) {
          window.setEmptyWindow(nextWindowTimeBegin, nextWindowTimeEnd);
          return YieldableState.YIELDABLE;
        }

        window.seek(
            nextIndexBegin,
            nextIndexEnd,
            nextWindowTimeBegin,
            nextWindowTimeBegin + timeInterval - 1);

        hasCached = !(nextIndexBegin == nextIndexEnd && nextIndexEnd == tvList.getPointCount());
        return hasCached ? YieldableState.YIELDABLE : YieldableState.NOT_YIELDABLE_NO_MORE_DATA;
      }

      @Override
      public void readyForNext() {
        hasCached = false;
        nextWindowTimeBegin += slidingStep;

        safetyPile.moveForwardTo(nextIndexBegin + 1);
        tvList.setEvictionUpperBound(safetyLine.getSafetyLine());
      }

      @Override
      public TSDataType[] getDataTypes() {
        return new TSDataType[] {parentLayerReaderDataType};
      }

      @Override
      public RowWindow currentWindow() {
        return window;
      }
    };
  }

  @Override
  protected LayerRowWindowReader constructRowSessionTimeWindowReader(
      SessionTimeWindowAccessStrategy strategy, float memoryBudgetInMB) {
    final long displayWindowBegin = strategy.getDisplayWindowBegin();
    final long displayWindowEnd = strategy.getDisplayWindowEnd();
    final long sessionTimeGap = strategy.getSessionTimeGap();

    final SafetyPile safetyPile = safetyLine.addSafetyPile();
    final ElasticSerializableTVListBackedSingleColumnWindow window =
        new ElasticSerializableTVListBackedSingleColumnWindow(tvList);

    return new LayerRowWindowReader() {

      private boolean isFirstIteration = true;
      private boolean hasAtLeastOneRow = false;

      private long nextWindowTimeBegin = displayWindowBegin;
      private long nextWindowTimeEnd = 0;
      private int nextIndexBegin = 0;
      private int nextIndexEnd = 1;

      @Override
      public YieldableState yield() throws Exception {
        if (isFirstIteration) {
          if (tvList.getPointCount() == 0) {
            final YieldableState yieldableState =
                LayerCacheUtils.yieldPoints(parentLayerReader, tvList);
            if (yieldableState != YieldableState.YIELDABLE) {
              return yieldableState;
            }
          }
          nextWindowTimeBegin = Math.max(displayWindowBegin, tvList.getTime(0));
          hasAtLeastOneRow = tvList.getPointCount() != 0;
          isFirstIteration = false;
        }

        if (!hasAtLeastOneRow || displayWindowEnd <= nextWindowTimeBegin) {
          return YieldableState.NOT_YIELDABLE_NO_MORE_DATA;
        }

        // Set nextIndexEnd
        nextIndexEnd++;
        boolean findWindow = false;
        // Find target window or no more data to exit
        while (!findWindow) {
          while (nextIndexEnd < tvList.getPointCount()) {
            long curTime = tvList.getTime(nextIndexEnd - 1);
            long nextTime = tvList.getTime(nextIndexEnd);

            if (curTime >= displayWindowEnd) {
              nextIndexEnd--;
              findWindow = true;
              break;
            }

            if (curTime >= displayWindowBegin && nextTime - curTime > sessionTimeGap) {
              findWindow = true;
              break;
            }
            nextIndexEnd++;
          }

          if (!findWindow) {
            if (tvList.getTime(tvList.getPointCount() - 1) < displayWindowEnd) {
              final YieldableState yieldableState =
                  LayerCacheUtils.yieldPoints(parentLayerReader, tvList);
              if (yieldableState == YieldableState.NOT_YIELDABLE_WAITING_FOR_DATA) {
                return YieldableState.NOT_YIELDABLE_WAITING_FOR_DATA;
              } else if (yieldableState == YieldableState.NOT_YIELDABLE_NO_MORE_DATA) {
                break;
              }
            }
          }
        }

        nextWindowTimeEnd = tvList.getTime(nextIndexEnd - 1);

        if (nextIndexBegin == nextIndexEnd) {
          return YieldableState.NOT_YIELDABLE_NO_MORE_DATA;
        }

        // Only if encounter user set the strategy's displayWindowBegin, which will go into the for
        // loop to find the true index of the first window begin.
        // For other situation, we will only go into if (nextWindowTimeBegin <= tvList.getTime(i))
        // once.
        for (int i = nextIndexBegin; i < tvList.getPointCount(); ++i) {
          if (tvList.getTime(i) >= nextWindowTimeBegin) {
            nextIndexBegin = i;
            break;
          }
          // The first window's beginning time is greater than all the timestamp of the query result
          // set
          if (i == tvList.getPointCount() - 1) {
            return YieldableState.NOT_YIELDABLE_NO_MORE_DATA;
          }
        }

        window.seek(nextIndexBegin, nextIndexEnd, nextWindowTimeBegin, nextWindowTimeEnd);

        return YieldableState.YIELDABLE;
      }

      @Override
      public void readyForNext() throws IOException {
        if (nextIndexEnd < tvList.getPointCount()) {
          nextWindowTimeBegin = tvList.getTime(nextIndexEnd);
        }
        safetyPile.moveForwardTo(nextIndexBegin + 1);
        tvList.setEvictionUpperBound(safetyLine.getSafetyLine());
        nextIndexBegin = nextIndexEnd;
      }

      @Override
      public TSDataType[] getDataTypes() {
        return new TSDataType[] {parentLayerReaderDataType};
      }

      @Override
      public RowWindow currentWindow() {
        return window;
      }
    };
  }

  @Override
  protected LayerRowWindowReader constructRowStateWindowReader(
      StateWindowAccessStrategy strategy, float memoryBudgetInMB) {
    final long displayWindowBegin = strategy.getDisplayWindowBegin();
    final long displayWindowEnd = strategy.getDisplayWindowEnd();
    final double delta = strategy.getDelta();

    final SafetyPile safetyPile = safetyLine.addSafetyPile();
    final ElasticSerializableTVListBackedSingleColumnWindow window =
        new ElasticSerializableTVListBackedSingleColumnWindow(tvList);

    return new LayerRowWindowReader() {

      private boolean isFirstIteration = true;
      private boolean hasAtLeastOneRow = false;

      private long nextWindowTimeBegin = displayWindowBegin;
      private long nextWindowTimeEnd = 0;
      private int nextIndexBegin = 0;
      private int nextIndexEnd = 1;

      private ValueRecorder valueRecorder = new ValueRecorder();

      @Override
      public YieldableState yield() throws Exception {
        if (isFirstIteration) {
          if (tvList.getPointCount() == 0) {
            final YieldableState yieldableState =
                LayerCacheUtils.yieldPoints(parentLayerReader, tvList);
            if (yieldableState != YieldableState.YIELDABLE) {
              return yieldableState;
            }
          }
          nextWindowTimeBegin = Math.max(displayWindowBegin, tvList.getTime(0));
          hasAtLeastOneRow = tvList.getPointCount() != 0;
          isFirstIteration = false;
        }

        if (!hasAtLeastOneRow || displayWindowEnd <= nextWindowTimeBegin) {
          return YieldableState.NOT_YIELDABLE_NO_MORE_DATA;
        }

        // Set nextIndexEnd
        nextIndexEnd++;
        boolean findWindow = false;
        // Find target window or no more data to exit
        while (!findWindow) {
          while (nextIndexEnd < tvList.getPointCount()) {
            long curTime = tvList.getTime(nextIndexEnd - 1);

            if (curTime >= displayWindowEnd) {
              nextIndexEnd--;
              findWindow = true;
              break;
            }

            if (curTime >= displayWindowBegin
                && TransformUtils.splitWindowForStateWindow(
                    parentLayerReaderDataType, valueRecorder, delta, tvList, nextIndexEnd)) {
              findWindow = true;
              break;
            }
            nextIndexEnd++;
          }

          if (!findWindow) {
            if (tvList.getTime(tvList.getPointCount() - 1) < displayWindowEnd) {
              final YieldableState yieldableState =
                  LayerCacheUtils.yieldPoints(parentLayerReader, tvList);
              if (yieldableState == YieldableState.NOT_YIELDABLE_WAITING_FOR_DATA) {
                return YieldableState.NOT_YIELDABLE_WAITING_FOR_DATA;
              } else if (yieldableState == YieldableState.NOT_YIELDABLE_NO_MORE_DATA) {
                break;
              }
            }
          }
        }

        nextWindowTimeEnd = tvList.getTime(nextIndexEnd - 1);

        if (nextIndexBegin == nextIndexEnd) {
          return YieldableState.NOT_YIELDABLE_NO_MORE_DATA;
        }

        // Only if encounter user set the strategy's displayWindowBegin, which will go into the for
        // loop to find the true index of the first window begin.
        // For other situation, we will only go into if (nextWindowTimeBegin <= tvList.getTime(i))
        // once.
        for (int i = nextIndexBegin; i < tvList.getPointCount(); ++i) {
          if (nextWindowTimeBegin <= tvList.getTime(i)) {
            nextIndexBegin = i;
            break;
          }
          // The first window's beginning time is greater than all the timestamp of the query result
          // set
          if (i == tvList.getPointCount() - 1) {
            return YieldableState.NOT_YIELDABLE_NO_MORE_DATA;
          }
        }

        window.seek(nextIndexBegin, nextIndexEnd, nextWindowTimeBegin, nextWindowTimeEnd);

        return YieldableState.YIELDABLE;
      }

      @Override
      public void readyForNext() throws IOException {
        if (nextIndexEnd < tvList.getPointCount()) {
          nextWindowTimeBegin = tvList.getTime(nextIndexEnd);
        }
        safetyPile.moveForwardTo(nextIndexBegin + 1);
        tvList.setEvictionUpperBound(safetyLine.getSafetyLine());
        nextIndexBegin = nextIndexEnd;
      }

      @Override
      public TSDataType[] getDataTypes() {
        return new TSDataType[] {parentLayerReaderDataType};
      }

      @Override
      public RowWindow currentWindow() {
        return window;
      }
    };
  }
}
