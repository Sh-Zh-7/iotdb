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

package org.apache.iotdb.db.queryengine.plan.relational.planner.optimizations;

import org.apache.iotdb.commons.schema.table.column.TsTableColumnCategory;
import org.apache.iotdb.db.queryengine.common.MPPQueryContext;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.PlanNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.PlanVisitor;
import org.apache.iotdb.db.queryengine.plan.relational.analyzer.Analysis;
import org.apache.iotdb.db.queryengine.plan.relational.metadata.ColumnSchema;
import org.apache.iotdb.db.queryengine.plan.relational.planner.OrderingScheme;
import org.apache.iotdb.db.queryengine.plan.relational.planner.Symbol;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.AggregationNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.AggregationTableScanNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.DeviceTableScanNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.GroupNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.InformationSchemaTableScanNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.SortNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.StreamSortNode;

import java.util.Map;

import static org.apache.iotdb.db.queryengine.plan.relational.planner.node.DeviceTableScanNode.isTimeColumn;

/**
 * <b>Optimization phase:</b> Logical plan planning.
 *
 * <p>This optimize rule implement the rules below.
 * <li>When the sort order is `IDColumns,Time` or `IDColumns,Others` in SortNode, SortNode can be
 *     transformed to StreamSortNode.
 * <li>Set value to `orderByAllIdsAndTime`.
 */
public class TransformSortToStreamSort implements PlanOptimizer {

  @Override
  public PlanNode optimize(PlanNode plan, PlanOptimizer.Context context) {
    if (!context.getAnalysis().hasSortNode()) {
      return plan;
    }

    return plan.accept(
        new Rewriter(context.getAnalysis(), context.getQueryContext()), new Context());
  }

  private static class Rewriter extends PlanVisitor<PlanNode, Context> {
    private final Analysis analysis;
    private final MPPQueryContext queryContext;

    public Rewriter(Analysis analysis, MPPQueryContext queryContext) {
      this.analysis = analysis;
      this.queryContext = queryContext;
    }

    @Override
    public PlanNode visitPlan(PlanNode node, Context context) {
      PlanNode newNode = node.clone();
      for (PlanNode child : node.getChildren()) {
        newNode.addChild(child.accept(this, context));
      }
      return newNode;
    }

    @Override
    public PlanNode visitSort(SortNode node, Context context) {

      PlanNode child = node.getChild().accept(this, context);

      // sort in outer query cannot use StreamSort
      if (!context.canTransform()) {
        node.setChild(child);
        return node;
      }
      context.setCanTransform(false);

      DeviceTableScanNode deviceTableScanNode = context.getTableScanNode();
      Map<Symbol, ColumnSchema> tableColumnSchema =
          analysis.getTableColumnSchema(deviceTableScanNode.getQualifiedObjectName());

      OrderingScheme orderingScheme = node.getOrderingScheme();
      int streamSortIndex = -1;
      for (Symbol orderBy : orderingScheme.getOrderBy()) {
        if (!tableColumnSchema.containsKey(orderBy)
            || tableColumnSchema.get(orderBy).getColumnCategory() == TsTableColumnCategory.FIELD
            || tableColumnSchema.get(orderBy).getColumnCategory() == TsTableColumnCategory.TIME) {
          break;
        } else {
          streamSortIndex++;
        }
      }

      if (streamSortIndex >= 0) {
        boolean orderByAllIdsAndTime =
            isOrderByAllIdsAndTime(
                tableColumnSchema,
                deviceTableScanNode.getAssignments(),
                orderingScheme,
                streamSortIndex);

        return new StreamSortNode(
            queryContext.getQueryId().genPlanNodeId(),
            child,
            node.getOrderingScheme(),
            node.isPartial(),
            orderByAllIdsAndTime,
            streamSortIndex);
      }

      return node;
    }

    @Override
    public PlanNode visitGroup(GroupNode node, Context context) {
      return visitSingleChildProcess(node, context);
    }

    @Override
    public PlanNode visitDeviceTableScan(DeviceTableScanNode node, Context context) {
      context.setTableScanNode(node);
      return node;
    }

    @Override
    public PlanNode visitInformationSchemaTableScan(
        InformationSchemaTableScanNode node, Context context) {
      context.setCanTransform(false);
      return node;
    }

    @Override
    public PlanNode visitAggregation(AggregationNode node, Context context) {
      context.setCanTransform(false);
      return visitSingleChildProcess(node, context);
    }

    @Override
    public PlanNode visitAggregationTableScan(AggregationTableScanNode node, Context context) {
      context.setCanTransform(false);
      return visitTableScan(node, context);
    }
  }

  /**
   * @param tableColumnSchema The ColumnSchema of original Table, but the symbol name maybe rewrite
   *     by Join
   * @param nodeColumnSchema The ColumnSchema of current node, which has been column pruned
   */
  public static boolean isOrderByAllIdsAndTime(
      Map<Symbol, ColumnSchema> tableColumnSchema,
      Map<Symbol, ColumnSchema> nodeColumnSchema,
      OrderingScheme orderingScheme,
      int streamSortIndex) {
    int tagCount = 0;
    for (ColumnSchema columnSchema : tableColumnSchema.values()) {
      if (columnSchema.getColumnCategory() == TsTableColumnCategory.TAG) {
        tagCount++;
      }
    }

    for (Symbol orderBy : orderingScheme.getOrderBy()) {
      ColumnSchema columnSchema = nodeColumnSchema.get(orderBy);
      if (columnSchema != null && columnSchema.getColumnCategory() == TsTableColumnCategory.TAG) {
        tagCount--;
      }
    }
    return tagCount == 0
        && (orderingScheme.getOrderings().size() == streamSortIndex + 1
            || isTimeColumn(
                orderingScheme.getOrderBy().get(streamSortIndex + 1), tableColumnSchema));
  }

  private static class Context {
    private DeviceTableScanNode tableScanNode;

    private boolean canTransform = true;

    public DeviceTableScanNode getTableScanNode() {
      return tableScanNode;
    }

    public void setTableScanNode(DeviceTableScanNode tableScanNode) {
      this.tableScanNode = tableScanNode;
    }

    public boolean canTransform() {
      return canTransform;
    }

    public void setCanTransform(boolean canTransform) {
      this.canTransform = canTransform;
    }
  }
}
