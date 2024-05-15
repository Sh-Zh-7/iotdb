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

package org.apache.iotdb.db.queryengine.transformation.datastructure.util.iterator;

import org.apache.iotdb.db.queryengine.transformation.datastructure.row.ElasticSerializableRowRecordList;
import org.apache.iotdb.db.queryengine.transformation.datastructure.row.SerializableRowRecordList;
import org.apache.iotdb.tsfile.read.common.block.column.Column;

import java.io.IOException;
import java.util.List;

// Forward iterator used in ElasticSerializableRowRecordList
// Point to columns(one time column and multiple value columns)
public class RowListForwardIterator implements ListForwardIterator {
  private final ElasticSerializableRowRecordList rowList;

  private int externalIndex; // Which SerializableRowRecordList
  private int internalIndex; // Which columns in SerializableRowRecordList

  // In case of rowList changing
  private int startRowIndex; // Index of first row of the columns

  public RowListForwardIterator(ElasticSerializableRowRecordList rowList) {
    this.rowList = rowList;
    // Point to dummy block for simplicity
    externalIndex = 0;
    internalIndex = -1;
    startRowIndex = -1;
  }

  public RowListForwardIterator(
      ElasticSerializableRowRecordList rowList, int externalIndex, int internalIndex)
      throws IOException {
    this.rowList = rowList;
    this.externalIndex = externalIndex;
    this.internalIndex = internalIndex;
    startRowIndex = rowList.getFirstRowIndex(externalIndex, internalIndex);
  }

  public Column[] currentBlock() throws IOException {
    return rowList.getColumns(externalIndex, internalIndex);
  }

  @Override
  public boolean hasNext() throws IOException {
    // First time call, rowList has no data
    List<SerializableRowRecordList> internalLists = rowList.getInternalRowList();
    if (rowList.getSerializableRowListSize() == 0) {
      return false;
    }
    return externalIndex + 1 < rowList.getSerializableRowListSize()
        || internalIndex + 1 < rowList.getSerializableRowList(externalIndex).getBlockCount();
  }

  @Override
  public void next() throws IOException {
    // Acquire previous columns size
    int prevSize;
    if (externalIndex == 0 && internalIndex == -1) {
      prevSize = 1;
    } else {
      prevSize = rowList.getColumns(externalIndex, internalIndex)[0].getPositionCount();
    }

    // Move forward iterator
    if (internalIndex + 1 == rowList.getSerializableRowList(externalIndex).getBlockCount()) {
      internalIndex = 0;
      externalIndex++;
    } else {
      internalIndex++;
    }

    // Update startRowIndex
    startRowIndex += prevSize;
  }

  // When rowList apply new memory control strategy, the origin iterators become invalid.
  // We can relocate these old iterators by its startPointIndex
  public void adjust() throws IOException {
    int capacity = rowList.getInternalRowListCapacity();

    int externalColumnIndex = startRowIndex / capacity;
    int internalRowIndex = startRowIndex % capacity;
    int internalColumnIndex =
        rowList.getSerializableRowList(externalIndex).getColumnIndex(internalRowIndex);

    this.externalIndex = externalColumnIndex;
    this.internalIndex = internalColumnIndex;
  }
}
