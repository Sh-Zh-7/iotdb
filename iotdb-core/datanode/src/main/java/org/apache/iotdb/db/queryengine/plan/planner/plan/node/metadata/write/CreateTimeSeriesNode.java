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

package org.apache.iotdb.db.queryengine.plan.planner.plan.node.metadata.write;

import org.apache.iotdb.common.rpc.thrift.TRegionReplicaSet;
import org.apache.iotdb.commons.exception.IllegalPathException;
import org.apache.iotdb.commons.path.MeasurementPath;
import org.apache.iotdb.db.queryengine.plan.analyze.IAnalysis;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.PlanNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.PlanNodeId;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.PlanNodeType;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.PlanVisitor;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.WritePlanNode;
import org.apache.iotdb.db.schemaengine.schemaregion.write.req.ICreateTimeSeriesPlan;

import com.google.common.collect.ImmutableList;
import org.apache.tsfile.common.conf.TSFileConfig;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.exception.NotImplementedException;
import org.apache.tsfile.file.metadata.enums.CompressionType;
import org.apache.tsfile.file.metadata.enums.TSEncoding;
import org.apache.tsfile.utils.ReadWriteIOUtils;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public class CreateTimeSeriesNode extends WritePlanNode implements ICreateTimeSeriesPlan {
  private MeasurementPath path;
  private TSDataType dataType;
  private TSEncoding encoding;
  private CompressionType compressor;
  private String alias;
  private Map<String, String> props = null;
  private Map<String, String> tags;
  private Map<String, String> attributes;

  // only used inside schemaRegion to be serialized to mlog, no need to be serialized for
  // queryengine
  // transport
  private long tagOffset = -1;

  private TRegionReplicaSet regionReplicaSet;

  public CreateTimeSeriesNode(
      final PlanNodeId id,
      final MeasurementPath path,
      final TSDataType dataType,
      final TSEncoding encoding,
      final CompressionType compressor,
      final Map<String, String> props,
      final Map<String, String> tags,
      final Map<String, String> attributes,
      final String alias) {
    super(id);
    this.path = path;
    this.dataType = dataType;
    this.encoding = encoding;
    this.compressor = compressor;
    this.tags = tags;
    this.attributes = attributes;
    this.alias = alias;
    if (props != null) {
      this.props = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      this.props.putAll(props);
    }
  }

  public MeasurementPath getPath() {
    return path;
  }

  public void setPath(final MeasurementPath path) {
    this.path = path;
  }

  public TSDataType getDataType() {
    return dataType;
  }

  public void setDataType(final TSDataType dataType) {
    this.dataType = dataType;
  }

  public CompressionType getCompressor() {
    return compressor;
  }

  public void setCompressor(final CompressionType compressor) {
    this.compressor = compressor;
  }

  public TSEncoding getEncoding() {
    return encoding;
  }

  public void setEncoding(final TSEncoding encoding) {
    this.encoding = encoding;
  }

  public Map<String, String> getAttributes() {
    return attributes;
  }

  public void setAttributes(final Map<String, String> attributes) {
    this.attributes = attributes;
  }

  public String getAlias() {
    return alias;
  }

  public void setAlias(final String alias) {
    this.alias = alias;
  }

  public Map<String, String> getTags() {
    return tags;
  }

  public void setTags(final Map<String, String> tags) {
    this.tags = tags;
  }

  public Map<String, String> getProps() {
    return props;
  }

  public void setProps(final Map<String, String> props) {
    this.props = props;
  }

  @Override
  public long getTagOffset() {
    return tagOffset;
  }

  @Override
  public void setTagOffset(final long tagOffset) {
    this.tagOffset = tagOffset;
  }

  @Override
  public List<PlanNode> getChildren() {
    return new ArrayList<>();
  }

  @Override
  public void addChild(final PlanNode child) {}

  @Override
  public PlanNodeType getType() {
    return PlanNodeType.CREATE_TIME_SERIES;
  }

  @Override
  public PlanNode clone() {
    throw new NotImplementedException("Clone of CreateTimeSeriesNode is not implemented");
  }

  @Override
  public int allowedChildCount() {
    return NO_CHILD_ALLOWED;
  }

  @Override
  public List<String> getOutputColumnNames() {
    return null;
  }

  public static CreateTimeSeriesNode deserialize(final ByteBuffer byteBuffer) {
    final String id;
    final MeasurementPath path;
    final TSDataType dataType;
    final TSEncoding encoding;
    final CompressionType compressor;
    String alias = null;
    Map<String, String> props = null;
    Map<String, String> tags = null;
    Map<String, String> attributes = null;

    final int length = byteBuffer.getInt();
    final byte[] bytes = new byte[length];
    byteBuffer.get(bytes);
    try {
      path = new MeasurementPath(new String(bytes, TSFileConfig.STRING_CHARSET));
    } catch (IllegalPathException e) {
      throw new IllegalArgumentException("Cannot deserialize CreateTimeSeriesNode", e);
    }
    dataType = TSDataType.values()[byteBuffer.get()];
    encoding = TSEncoding.values()[byteBuffer.get()];
    compressor = CompressionType.deserialize(byteBuffer.get());

    // alias
    if (byteBuffer.get() == 1) {
      alias = ReadWriteIOUtils.readString(byteBuffer);
    }

    byte label = byteBuffer.get();
    // props
    if (label == 0) {
      props = new HashMap<>();
    } else if (label == 1) {
      props = ReadWriteIOUtils.readMap(byteBuffer);
    }

    // tags
    label = byteBuffer.get();
    if (label == 0) {
      tags = new HashMap<>();
    } else if (label == 1) {
      tags = ReadWriteIOUtils.readMap(byteBuffer);
    }

    // attributes
    label = byteBuffer.get();
    if (label == 0) {
      attributes = new HashMap<>();
    } else if (label == 1) {
      attributes = ReadWriteIOUtils.readMap(byteBuffer);
    }

    id = ReadWriteIOUtils.readString(byteBuffer);
    return new CreateTimeSeriesNode(
        new PlanNodeId(id), path, dataType, encoding, compressor, props, tags, attributes, alias);
  }

  @Override
  protected void serializeAttributes(final ByteBuffer byteBuffer) {
    PlanNodeType.CREATE_TIME_SERIES.serialize(byteBuffer);

    final byte[] bytes = path.getFullPath().getBytes();
    byteBuffer.putInt(bytes.length);
    byteBuffer.put(bytes);
    byteBuffer.put((byte) dataType.ordinal());
    byteBuffer.put((byte) encoding.ordinal());
    byteBuffer.put(compressor.serialize());

    // alias
    if (alias != null) {
      byteBuffer.put((byte) 1);
      ReadWriteIOUtils.write(alias, byteBuffer);
    } else {
      byteBuffer.put((byte) 0);
    }

    // props
    if (props == null) {
      byteBuffer.put((byte) -1);
    } else if (props.isEmpty()) {
      byteBuffer.put((byte) 0);
    } else {
      byteBuffer.put((byte) 1);
      ReadWriteIOUtils.write(props, byteBuffer);
    }

    // tags
    if (tags == null) {
      byteBuffer.put((byte) -1);
    } else if (tags.isEmpty()) {
      byteBuffer.put((byte) 0);
    } else {
      byteBuffer.put((byte) 1);
      ReadWriteIOUtils.write(tags, byteBuffer);
    }

    // attributes
    if (attributes == null) {
      byteBuffer.put((byte) -1);
    } else if (attributes.isEmpty()) {
      byteBuffer.put((byte) 0);
    } else {
      byteBuffer.put((byte) 1);
      ReadWriteIOUtils.write(attributes, byteBuffer);
    }
  }

  @Override
  protected void serializeAttributes(final DataOutputStream stream) throws IOException {
    PlanNodeType.CREATE_TIME_SERIES.serialize(stream);

    final byte[] bytes = path.getFullPath().getBytes();
    stream.writeInt(bytes.length);
    stream.write(bytes);
    stream.write((byte) dataType.ordinal());
    stream.write((byte) encoding.ordinal());
    stream.write(compressor.serialize());

    // alias
    if (alias != null) {
      stream.write((byte) 1);
      ReadWriteIOUtils.write(alias, stream);
    } else {
      stream.write((byte) 0);
    }

    // props
    if (props == null) {
      stream.write((byte) -1);
    } else if (props.isEmpty()) {
      stream.write((byte) 0);
    } else {
      stream.write((byte) 1);
      ReadWriteIOUtils.write(props, stream);
    }

    // tags
    if (tags == null) {
      stream.write((byte) -1);
    } else if (tags.isEmpty()) {
      stream.write((byte) 0);
    } else {
      stream.write((byte) 1);
      ReadWriteIOUtils.write(tags, stream);
    }

    // attributes
    if (attributes == null) {
      stream.write((byte) -1);
    } else if (attributes.isEmpty()) {
      stream.write((byte) 0);
    } else {
      stream.write((byte) 1);
      ReadWriteIOUtils.write(attributes, stream);
    }
  }

  @Override
  public <R, C> R accept(final PlanVisitor<R, C> visitor, final C schemaRegion) {
    return visitor.visitCreateTimeSeries(this, schemaRegion);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    final CreateTimeSeriesNode that = (CreateTimeSeriesNode) o;
    return path.equals(that.path)
        && dataType == that.dataType
        && encoding == that.encoding
        && compressor == that.compressor
        && ((alias == null && that.alias == null) || (alias != null && alias.equals(that.alias)))
        && ((props == null && that.props == null) || (props != null && props.equals(that.props)))
        && ((tags == null && that.tags == null) || (tags != null && tags.equals(that.tags)))
        && ((attributes == null && that.attributes == null)
            || (attributes != null && attributes.equals(that.attributes)));
  }

  @Override
  public int hashCode() {
    return Objects.hash(path, dataType, encoding, compressor, alias, props, tags, attributes);
  }

  @Override
  public TRegionReplicaSet getRegionReplicaSet() {
    return regionReplicaSet;
  }

  @Override
  public List<WritePlanNode> splitByPartition(final IAnalysis analysis) {
    final TRegionReplicaSet regionReplicaSet =
        analysis.getSchemaPartitionInfo().getSchemaRegionReplicaSet(path.getIDeviceID());
    setRegionReplicaSet(regionReplicaSet);
    return ImmutableList.of(this);
  }

  public void setRegionReplicaSet(final TRegionReplicaSet regionReplicaSet) {
    this.regionReplicaSet = regionReplicaSet;
  }
}
