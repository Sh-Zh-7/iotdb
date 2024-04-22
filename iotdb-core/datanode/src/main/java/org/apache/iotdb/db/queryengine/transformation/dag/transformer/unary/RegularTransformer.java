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

package org.apache.iotdb.db.queryengine.transformation.dag.transformer.unary;

import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.queryengine.transformation.api.LayerPointReader;
import org.apache.iotdb.db.queryengine.transformation.api.LayerReader;
import org.apache.iotdb.tsfile.common.conf.TSFileConfig;
import org.apache.iotdb.tsfile.exception.write.UnSupportedDataTypeException;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.read.common.block.column.Column;
import org.apache.iotdb.tsfile.read.common.block.column.ColumnBuilder;
import org.apache.iotdb.tsfile.utils.Binary;

import java.io.IOException;
import java.util.regex.Pattern;

public class RegularTransformer extends UnaryTransformer {

  private final Pattern pattern;

  public RegularTransformer(LayerReader layerReader, Pattern pattern) {
    super(layerReader);
    this.pattern = pattern;

    if (layerReaderDataType != TSDataType.TEXT) {
      throw new UnSupportedDataTypeException(
          "Unsupported data type: " + layerReaderDataType);
    }
  }

  @Override
  public TSDataType[] getDataTypes() {
    return new TSDataType[]{TSDataType.BOOLEAN};
  }

  @Override
  protected void transform(Column[] columns, ColumnBuilder builder) throws QueryProcessException, IOException {
    int count = columns[0].getPositionCount();
    Binary[] binaries = columns[0].getBinaries();
    boolean[] isNulls = columns[0].isNull();

    for (int i = 0; i < count; i++) {
      if (!isNulls[i]) {
        boolean res = pattern.matcher(binaries[i].getStringValue(TSFileConfig.STRING_CHARSET)).find();
        builder.writeBoolean(res);
      } else {
        builder.appendNull();
      }
    }
  }
}
