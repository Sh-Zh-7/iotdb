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
package org.apache.iotdb.db.storageengine.dataregion.memtable;

import org.apache.iotdb.commons.concurrent.IoTDBThreadPoolFactory;
import org.apache.iotdb.commons.exception.IllegalPathException;
import org.apache.iotdb.commons.exception.MetadataException;
import org.apache.iotdb.commons.path.AlignedFullPath;
import org.apache.iotdb.commons.path.MeasurementPath;
import org.apache.iotdb.commons.path.NonAlignedFullPath;
import org.apache.iotdb.commons.path.PartialPath;
import org.apache.iotdb.db.conf.IoTDBConfig;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.exception.WriteProcessException;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.queryengine.common.FragmentInstanceId;
import org.apache.iotdb.db.queryengine.common.PlanFragmentId;
import org.apache.iotdb.db.queryengine.common.QueryId;
import org.apache.iotdb.db.queryengine.exception.CpuNotEnoughException;
import org.apache.iotdb.db.queryengine.exception.MemoryNotEnoughException;
import org.apache.iotdb.db.queryengine.execution.driver.IDriver;
import org.apache.iotdb.db.queryengine.execution.exchange.MPPDataExchangeManager;
import org.apache.iotdb.db.queryengine.execution.exchange.sink.ISink;
import org.apache.iotdb.db.queryengine.execution.fragment.FragmentInstanceContext;
import org.apache.iotdb.db.queryengine.execution.fragment.FragmentInstanceExecution;
import org.apache.iotdb.db.queryengine.execution.fragment.FragmentInstanceStateMachine;
import org.apache.iotdb.db.queryengine.execution.fragment.QueryContext;
import org.apache.iotdb.db.queryengine.execution.schedule.IDriverScheduler;
import org.apache.iotdb.db.queryengine.plan.planner.memory.MemoryReservationManager;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.PlanNodeId;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.write.InsertTabletNode;
import org.apache.iotdb.db.storageengine.dataregion.DataRegion;
import org.apache.iotdb.db.storageengine.dataregion.modification.ModEntry;
import org.apache.iotdb.db.storageengine.dataregion.modification.TreeDeletionEntry;
import org.apache.iotdb.db.storageengine.dataregion.wal.utils.WALByteBufferForTest;
import org.apache.iotdb.db.utils.MathUtils;
import org.apache.iotdb.db.utils.datastructure.TVList;

import org.apache.tsfile.common.conf.TSFileConfig;
import org.apache.tsfile.common.conf.TSFileDescriptor;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.file.metadata.IDeviceID;
import org.apache.tsfile.file.metadata.enums.CompressionType;
import org.apache.tsfile.file.metadata.enums.TSEncoding;
import org.apache.tsfile.read.TimeValuePair;
import org.apache.tsfile.read.reader.IPointReader;
import org.apache.tsfile.utils.Binary;
import org.apache.tsfile.utils.Pair;
import org.apache.tsfile.utils.TsPrimitiveType;
import org.apache.tsfile.write.UnSupportedDataTypeException;
import org.apache.tsfile.write.schema.IMeasurementSchema;
import org.apache.tsfile.write.schema.MeasurementSchema;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;

import static org.apache.iotdb.db.queryengine.execution.fragment.FragmentInstanceContext.createFragmentInstanceContext;

public class PrimitiveMemTableTest {
  private static final IoTDBConfig conf = IoTDBDescriptor.getInstance().getConfig();
  private static final int dataNodeId = 0;

  String database = "root.test";
  String dataRegionId = "1";
  double delta;

  private IDeviceID deviceID = IDeviceID.Factory.DEFAULT_FACTORY.create("d1");

  NonAlignedFullPath nonAlignedFullPath =
      new NonAlignedFullPath(
          deviceID,
          new MeasurementSchema(
              "s0",
              TSDataType.INT32,
              TSEncoding.RLE,
              CompressionType.UNCOMPRESSED,
              Collections.emptyMap()));

  AlignedFullPath alignedFullPath =
      new AlignedFullPath(
          deviceID,
          Collections.singletonList("s0"),
          Collections.singletonList(
              new MeasurementSchema(
                  "s0",
                  TSDataType.INT32,
                  TSEncoding.RLE,
                  CompressionType.UNCOMPRESSED,
                  Collections.emptyMap())));

  @Before
  public void setUp() {
    delta = Math.pow(0.1, TSFileDescriptor.getInstance().getConfig().getFloatPrecision());
    conf.setDataNodeId(dataNodeId);
  }

  @Test
  public void memSeriesSortIteratorTest() throws IOException, QueryProcessException {
    TSDataType dataType = TSDataType.INT32;
    WritableMemChunk series =
        new WritableMemChunk(new MeasurementSchema("s1", dataType, TSEncoding.PLAIN));
    int count = 1000;
    for (int i = 0; i < count; i++) {
      series.writeNonAlignedPoint(i, i);
    }
    Map<TVList, Integer> tvListQueryMap = new HashMap<>();
    for (TVList tvList : series.getSortedList()) {
      tvListQueryMap.put(tvList, tvList.rowCount());
    }
    tvListQueryMap.put(series.getWorkingTVList(), series.getWorkingTVList().rowCount());
    ReadOnlyMemChunk readableChunk =
        new ReadOnlyMemChunk(
            new QueryContext(), "s1", dataType, TSEncoding.PLAIN, tvListQueryMap, null, null);
    IPointReader it = readableChunk.getPointReader();
    int i = 0;
    while (it.hasNextTimeValuePair()) {
      Assert.assertEquals(i, it.nextTimeValuePair().getTimestamp());
      i++;
    }
    Assert.assertEquals(count, i);
  }

  @Test
  public void memSeriesToStringTest() throws IOException {
    TSDataType dataType = TSDataType.INT32;
    WritableMemChunk series =
        new WritableMemChunk(new MeasurementSchema("s1", dataType, TSEncoding.PLAIN));
    int count = 100;
    for (int i = 0; i < count; i++) {
      series.writeNonAlignedPoint(i, i);
    }
    series.writeNonAlignedPoint(0, 21);
    series.writeNonAlignedPoint(99, 20);
    series.writeNonAlignedPoint(20, 21);
    String str = series.toString();
    Assert.assertFalse(series.getWorkingTVList().isSorted());
    Assert.assertEquals(
        "MemChunk Size: 103"
            + System.lineSeparator()
            + "Data type:INT32"
            + System.lineSeparator()
            + "First point:0 : 0"
            + System.lineSeparator()
            + "Last point:99 : 20"
            + System.lineSeparator(),
        str);
  }

  @Test
  public void simpleTest() throws IOException, QueryProcessException, MetadataException {
    IMemTable memTable = new PrimitiveMemTable(database, dataRegionId);
    int count = 10;
    String[] measurementId = new String[count];
    for (int i = 0; i < measurementId.length; i++) {
      measurementId[i] = "s" + i;
    }

    int dataSize = 10000;
    for (int i = 0; i < dataSize; i++) {
      memTable.write(
          deviceID,
          Collections.singletonList(
              new MeasurementSchema(measurementId[0], TSDataType.INT32, TSEncoding.PLAIN)),
          dataSize - i - 1,
          new Object[] {i + 10});
    }
    for (int i = 0; i < dataSize; i++) {
      memTable.write(
          deviceID,
          Collections.singletonList(
              new MeasurementSchema(measurementId[0], TSDataType.INT32, TSEncoding.PLAIN)),
          i,
          new Object[] {i});
    }

    ReadOnlyMemChunk memChunk =
        memTable.query(new QueryContext(), nonAlignedFullPath, Long.MIN_VALUE, null, null);
    IPointReader iterator = memChunk.getPointReader();
    for (int i = 0; i < dataSize; i++) {
      iterator.hasNextTimeValuePair();
      TimeValuePair timeValuePair = iterator.nextTimeValuePair();
      Assert.assertEquals(i, timeValuePair.getTimestamp());
      Assert.assertEquals(i, timeValuePair.getValue().getValue());
    }
  }

  @Test
  public void totalSeriesNumberTest() throws IOException, QueryProcessException, MetadataException {
    IoTDBConfig conf = IoTDBDescriptor.getInstance().getConfig();
    int dataNodeId = 0;
    conf.setDataNodeId(dataNodeId);

    IMemTable memTable = new PrimitiveMemTable(database, dataRegionId);
    int count = 10;
    String deviceId = "d1";
    String[] measurementId = new String[count];
    for (int i = 0; i < measurementId.length; i++) {
      measurementId[i] = "s" + i;
    }
    List<IMeasurementSchema> schemaList = new ArrayList<>();
    schemaList.add(new MeasurementSchema(measurementId[0], TSDataType.INT32, TSEncoding.PLAIN));
    schemaList.add(new MeasurementSchema(measurementId[1], TSDataType.INT32, TSEncoding.PLAIN));
    int dataSize = 10000;
    for (int i = 0; i < dataSize; i++) {
      memTable.write(
          DeviceIDFactory.getInstance().getDeviceID(new PartialPath(deviceId)),
          Collections.singletonList(
              new MeasurementSchema(measurementId[0], TSDataType.INT32, TSEncoding.PLAIN)),
          i,
          new Object[] {i});
    }
    deviceId = "d2";
    for (int i = 0; i < dataSize; i++) {
      memTable.write(
          DeviceIDFactory.getInstance().getDeviceID(new PartialPath(deviceId)),
          schemaList,
          i,
          new Object[] {i, i});
    }
    Assert.assertEquals(3, memTable.getSeriesNumber());
    // aligned
    deviceId = "d3";

    for (int i = 0; i < dataSize; i++) {
      memTable.writeAlignedRow(
          DeviceIDFactory.getInstance().getDeviceID(new PartialPath(deviceId)),
          schemaList,
          i,
          new Object[] {i, i});
    }
    Assert.assertEquals(5, memTable.getSeriesNumber());
    memTable.writeAlignedRow(
        DeviceIDFactory.getInstance().getDeviceID(new PartialPath(deviceId)),
        Collections.singletonList(
            new MeasurementSchema(measurementId[2], TSDataType.INT32, TSEncoding.PLAIN)),
        0,
        new Object[] {0});
    Assert.assertEquals(6, memTable.getSeriesNumber());
  }

  @Test
  public void queryWithDeletionTest() throws IOException, QueryProcessException, MetadataException {
    IMemTable memTable = new PrimitiveMemTable(database, dataRegionId);
    int count = 10;
    String[] measurementId = new String[count];
    for (int i = 0; i < measurementId.length; i++) {
      measurementId[i] = "s" + i;
    }

    int dataSize = 10000;
    for (int i = 0; i < dataSize; i++) {
      memTable.write(
          deviceID,
          Collections.singletonList(
              new MeasurementSchema(measurementId[0], TSDataType.INT32, TSEncoding.PLAIN)),
          dataSize - i - 1,
          new Object[] {i + 10});
    }
    for (int i = 0; i < dataSize; i++) {
      memTable.write(
          deviceID,
          Collections.singletonList(
              new MeasurementSchema(measurementId[0], TSDataType.INT32, TSEncoding.PLAIN)),
          i,
          new Object[] {i});
    }
    List<Pair<ModEntry, IMemTable>> modsToMemtable = new ArrayList<>();
    ModEntry deletion =
        new TreeDeletionEntry(new MeasurementPath(deviceID, measurementId[0]), 10, dataSize);
    modsToMemtable.add(new Pair<>(deletion, memTable));
    ReadOnlyMemChunk memChunk =
        memTable.query(
            new QueryContext(), nonAlignedFullPath, Long.MIN_VALUE, modsToMemtable, null);
    IPointReader iterator = memChunk.getPointReader();
    int cnt = 0;
    while (iterator.hasNextTimeValuePair()) {
      TimeValuePair timeValuePair = iterator.nextTimeValuePair();
      Assert.assertEquals(cnt, timeValuePair.getTimestamp());
      Assert.assertEquals(cnt, timeValuePair.getValue().getValue());
      cnt++;
    }
    Assert.assertEquals(10, cnt);
  }

  @Test
  public void queryAlignChuckWithDeletionTest()
      throws IOException, QueryProcessException, MetadataException {
    IMemTable memTable = new PrimitiveMemTable(database, dataRegionId);
    int count = 10;
    String[] measurementId = new String[count];
    for (int i = 0; i < measurementId.length; i++) {
      measurementId[i] = "s" + i;
    }

    int dataSize = 10000;
    for (int i = 0; i < dataSize; i++) {
      memTable.writeAlignedRow(
          deviceID,
          Collections.singletonList(
              new MeasurementSchema(measurementId[0], TSDataType.INT32, TSEncoding.PLAIN)),
          dataSize - i - 1,
          new Object[] {i + 10});
    }
    for (int i = 0; i < dataSize; i++) {
      memTable.writeAlignedRow(
          deviceID,
          Collections.singletonList(
              new MeasurementSchema(measurementId[0], TSDataType.INT32, TSEncoding.PLAIN)),
          i,
          new Object[] {i});
    }

    List<Pair<ModEntry, IMemTable>> modsToMemtable = new ArrayList<>();
    ModEntry deletion =
        new TreeDeletionEntry(new MeasurementPath(deviceID, measurementId[0]), 10, dataSize);
    modsToMemtable.add(new Pair<>(deletion, memTable));
    ReadOnlyMemChunk memChunk =
        memTable.query(new QueryContext(), alignedFullPath, Long.MIN_VALUE, modsToMemtable, null);
    IPointReader iterator = memChunk.getPointReader();
    int cnt = 0;
    while (iterator.hasNextTimeValuePair()) {
      TimeValuePair timeValuePair = iterator.nextTimeValuePair();
      Assert.assertEquals(cnt, timeValuePair.getTimestamp());
      Assert.assertEquals(cnt, timeValuePair.getValue().getVector()[0].getInt());
      cnt++;
    }
    Assert.assertEquals(10, cnt);
  }

  private void write(
      IMemTable memTable,
      String deviceId,
      String sensorId,
      TSDataType dataType,
      TSEncoding encoding,
      int size)
      throws IOException, QueryProcessException, MetadataException {
    TimeValuePair[] ret = genTimeValuePair(size, dataType);

    for (TimeValuePair aRet : ret) {
      memTable.write(
          DeviceIDFactory.getInstance().getDeviceID(new PartialPath(deviceId)),
          Collections.singletonList(new MeasurementSchema(sensorId, dataType, encoding)),
          aRet.getTimestamp(),
          new Object[] {aRet.getValue().getValue()});
    }
    NonAlignedFullPath fullPath =
        new NonAlignedFullPath(
            IDeviceID.Factory.DEFAULT_FACTORY.create(deviceId),
            new MeasurementSchema(
                sensorId,
                dataType,
                encoding,
                CompressionType.UNCOMPRESSED,
                Collections.emptyMap()));
    IPointReader tvPair =
        memTable.query(new QueryContext(), fullPath, Long.MIN_VALUE, null, null).getPointReader();
    Arrays.sort(ret);
    TimeValuePair last = null;
    for (int i = 0; i < ret.length; i++) {
      while (last != null && (i < ret.length && last.getTimestamp() == ret[i].getTimestamp())) {
        i++;
      }
      if (i >= ret.length) {
        break;
      }
      TimeValuePair pair = ret[i];
      last = pair;
      tvPair.hasNextTimeValuePair();
      TimeValuePair next = tvPair.nextTimeValuePair();
      Assert.assertEquals(pair.getTimestamp(), next.getTimestamp());
      if (dataType == TSDataType.DOUBLE) {
        Assert.assertEquals(
            pair.getValue().getDouble(),
            MathUtils.roundWithGivenPrecision(next.getValue().getDouble()),
            delta);
      } else if (dataType == TSDataType.FLOAT) {
        float expected = pair.getValue().getFloat();
        float actual = MathUtils.roundWithGivenPrecision(next.getValue().getFloat());
        Assert.assertEquals(expected, actual, delta + Float.MIN_NORMAL);
      } else {
        Assert.assertEquals(pair.getValue(), next.getValue());
      }
    }
  }

  private void writeVector(IMemTable memTable)
      throws IOException, QueryProcessException, MetadataException, WriteProcessException {
    memTable.insertAlignedTablet(genInsertTableNode(), 0, 100, null);

    IDeviceID tmpDeviceId = IDeviceID.Factory.DEFAULT_FACTORY.create("root.sg.device5");

    AlignedFullPath tmpAlignedFullPath =
        new AlignedFullPath(
            tmpDeviceId,
            Collections.singletonList("sensor1"),
            Collections.singletonList(
                new MeasurementSchema(
                    "sensor1",
                    TSDataType.INT64,
                    TSEncoding.GORILLA,
                    CompressionType.UNCOMPRESSED,
                    Collections.emptyMap())));
    IPointReader tvPair =
        memTable
            .query(new QueryContext(), tmpAlignedFullPath, Long.MIN_VALUE, null, null)
            .getPointReader();
    for (int i = 0; i < 100; i++) {
      tvPair.hasNextTimeValuePair();
      TimeValuePair next = tvPair.nextTimeValuePair();
      Assert.assertEquals(i, next.getTimestamp());
      Assert.assertEquals(i, next.getValue().getVector()[0].getLong());
    }

    tmpAlignedFullPath =
        new AlignedFullPath(
            tmpDeviceId,
            Arrays.asList("sensor0", "sensor1"),
            Arrays.asList(
                new MeasurementSchema(
                    "sensor0",
                    TSDataType.BOOLEAN,
                    TSEncoding.PLAIN,
                    CompressionType.UNCOMPRESSED,
                    Collections.emptyMap()),
                new MeasurementSchema(
                    "sensor1",
                    TSDataType.INT64,
                    TSEncoding.GORILLA,
                    CompressionType.UNCOMPRESSED,
                    Collections.emptyMap())));

    tvPair =
        memTable
            .query(new QueryContext(), tmpAlignedFullPath, Long.MIN_VALUE, null, null)
            .getPointReader();
    for (int i = 0; i < 100; i++) {
      tvPair.hasNextTimeValuePair();
      TimeValuePair next = tvPair.nextTimeValuePair();
      Assert.assertEquals(i, next.getTimestamp());
      Assert.assertEquals(i, next.getValue().getVector()[1].getLong());
    }
  }

  @Test
  public void testFloatType() throws IOException, QueryProcessException, MetadataException {
    IMemTable memTable = new PrimitiveMemTable(database, dataRegionId);
    String deviceId = "d1";
    int size = 100;
    write(memTable, deviceId, "s1", TSDataType.FLOAT, TSEncoding.RLE, size);
  }

  @Test
  public void testAllType()
      throws IOException, QueryProcessException, MetadataException, WriteProcessException {
    IMemTable memTable = new PrimitiveMemTable(database, dataRegionId);
    int count = 10;
    String deviceId = "d1";
    String[] measurementId = new String[count];
    for (int i = 0; i < measurementId.length; i++) {
      measurementId[i] = "s" + i;
    }
    int index = 0;

    int size = 10000;
    write(memTable, deviceId, measurementId[index++], TSDataType.BOOLEAN, TSEncoding.RLE, size);
    write(memTable, deviceId, measurementId[index++], TSDataType.INT32, TSEncoding.RLE, size);
    write(memTable, deviceId, measurementId[index++], TSDataType.INT64, TSEncoding.RLE, size);
    write(memTable, deviceId, measurementId[index++], TSDataType.FLOAT, TSEncoding.RLE, size);
    write(memTable, deviceId, measurementId[index++], TSDataType.DOUBLE, TSEncoding.RLE, size);
    write(memTable, deviceId, measurementId[index++], TSDataType.TEXT, TSEncoding.PLAIN, size);
    writeVector(memTable);
  }

  private TimeValuePair[] genTimeValuePair(int size, TSDataType dataType) {
    TimeValuePair[] ret = new TimeValuePair[size];
    Random rand = new Random();
    for (int i = 0; i < size; i++) {
      switch (dataType) {
        case BOOLEAN:
          ret[i] = new TimeValuePair(rand.nextLong(), TsPrimitiveType.getByType(dataType, true));
          break;
        case INT32:
          ret[i] =
              new TimeValuePair(
                  rand.nextLong(), TsPrimitiveType.getByType(dataType, rand.nextInt()));
          break;
        case INT64:
          ret[i] =
              new TimeValuePair(
                  rand.nextLong(), TsPrimitiveType.getByType(dataType, rand.nextLong()));
          break;
        case FLOAT:
          ret[i] =
              new TimeValuePair(
                  rand.nextLong(), TsPrimitiveType.getByType(dataType, rand.nextFloat()));
          break;
        case DOUBLE:
          ret[i] =
              new TimeValuePair(
                  rand.nextLong(), TsPrimitiveType.getByType(dataType, rand.nextDouble()));
          break;
        case TEXT:
          ret[i] =
              new TimeValuePair(
                  rand.nextLong(),
                  TsPrimitiveType.getByType(
                      dataType, new Binary("a" + rand.nextDouble(), TSFileConfig.STRING_CHARSET)));
          break;
        default:
          throw new UnSupportedDataTypeException("Unsupported data type:" + dataType);
      }
    }
    return ret;
  }

  private static InsertTabletNode genInsertTableNode() throws IllegalPathException {
    String[] measurements = new String[2];
    measurements[0] = "sensor0";
    measurements[1] = "sensor1";
    String deviceId = "root.sg.device5";
    TSDataType[] dataTypes = new TSDataType[2];
    dataTypes[0] = TSDataType.BOOLEAN;
    dataTypes[1] = TSDataType.INT64;
    long[] times = new long[100];
    Object[] columns = new Object[2];
    columns[0] = new boolean[100];
    columns[1] = new long[100];

    for (long r = 0; r < 100; r++) {
      times[(int) r] = r;
      ((boolean[]) columns[0])[(int) r] = false;
      ((long[]) columns[1])[(int) r] = r;
    }
    TSEncoding[] encodings = new TSEncoding[2];
    encodings[0] = TSEncoding.PLAIN;
    encodings[1] = TSEncoding.GORILLA;

    MeasurementSchema[] schemas = new MeasurementSchema[2];
    schemas[0] = new MeasurementSchema(measurements[0], dataTypes[0], encodings[0]);
    schemas[1] = new MeasurementSchema(measurements[1], dataTypes[1], encodings[1]);

    InsertTabletNode node =
        new InsertTabletNode(
            new PlanNodeId("0"),
            new PartialPath(deviceId),
            true,
            measurements,
            dataTypes,
            times,
            null,
            columns,
            times.length);
    node.setMeasurementSchemas(schemas);
    return node;
  }

  @Test
  public void testSerializeSize()
      throws IOException, QueryProcessException, MetadataException, WriteProcessException {
    IMemTable memTable = new PrimitiveMemTable(database, dataRegionId);
    int count = 10;
    String deviceId = "d1";
    String[] measurementId = new String[count];
    for (int i = 0; i < measurementId.length; i++) {
      measurementId[i] = "s" + i;
    }
    int index = 0;

    int size = 10000;
    write(memTable, deviceId, measurementId[index++], TSDataType.BOOLEAN, TSEncoding.RLE, size);
    write(memTable, deviceId, measurementId[index++], TSDataType.INT32, TSEncoding.RLE, size);
    write(memTable, deviceId, measurementId[index++], TSDataType.INT64, TSEncoding.RLE, size);
    write(memTable, deviceId, measurementId[index++], TSDataType.FLOAT, TSEncoding.RLE, size);
    write(memTable, deviceId, measurementId[index++], TSDataType.DOUBLE, TSEncoding.RLE, size);
    write(memTable, deviceId, measurementId[index++], TSDataType.TEXT, TSEncoding.PLAIN, size);
    writeVector(memTable);

    int serializedSize = memTable.serializedSize();
    WALByteBufferForTest walBuffer = new WALByteBufferForTest(ByteBuffer.allocate(serializedSize));
    memTable.serializeToWAL(walBuffer);
    // TODO: revert until TsFile is updated
    // assertEquals(0, walBuffer.getBuffer().remaining());
  }

  @Test
  public void testReleaseWithNotEnoughMemory() throws CpuNotEnoughException {
    TSDataType dataType = TSDataType.INT32;
    WritableMemChunk series =
        new WritableMemChunk(new MeasurementSchema("s1", dataType, TSEncoding.PLAIN));
    int count = 100;
    for (int i = 0; i < count; i++) {
      series.writeNonAlignedPoint(i, i);
    }

    // mock MemoryNotEnoughException exception
    TVList list = series.getWorkingTVList();

    // mock MemoryReservationManager
    MemoryReservationManager memoryReservationManager =
        Mockito.mock(MemoryReservationManager.class);
    Mockito.doThrow(new MemoryNotEnoughException(""))
        .when(memoryReservationManager)
        .reserveMemoryCumulatively(list.calculateRamSize());

    // create FragmentInstanceId
    QueryId queryId = new QueryId("stub_query");
    FragmentInstanceId instanceId =
        new FragmentInstanceId(new PlanFragmentId(queryId, 0), "stub-instance");
    ExecutorService instanceNotificationExecutor =
        IoTDBThreadPoolFactory.newFixedThreadPool(1, "test-instance-notification");
    FragmentInstanceStateMachine stateMachine =
        new FragmentInstanceStateMachine(instanceId, instanceNotificationExecutor);
    FragmentInstanceContext queryContext =
        createFragmentInstanceContext(instanceId, stateMachine, memoryReservationManager);
    queryContext.initializeNumOfDrivers(1);
    DataRegion dataRegion = Mockito.mock(DataRegion.class);
    queryContext.setDataRegion(dataRegion);

    list.getQueryContextSet().add(queryContext);
    Map<TVList, Integer> tvlistMap = new HashMap<>();
    tvlistMap.put(list, 100);
    queryContext.addTVListToSet(tvlistMap);

    // fragment instance execution
    IDriverScheduler scheduler = Mockito.mock(IDriverScheduler.class);
    List<IDriver> drivers = Collections.emptyList();
    ISink sinkHandle = Mockito.mock(ISink.class);
    MPPDataExchangeManager exchangeManager = Mockito.mock(MPPDataExchangeManager.class);
    FragmentInstanceExecution execution =
        FragmentInstanceExecution.createFragmentInstanceExecution(
            scheduler,
            instanceId,
            queryContext,
            drivers,
            sinkHandle,
            stateMachine,
            -1,
            false,
            exchangeManager);

    queryContext.decrementNumOfUnClosedDriver();
    series.release();
  }
}
