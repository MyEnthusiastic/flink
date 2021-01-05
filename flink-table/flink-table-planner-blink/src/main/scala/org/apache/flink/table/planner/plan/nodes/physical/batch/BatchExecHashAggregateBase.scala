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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.planner.plan.nodes.physical.batch

import org.apache.flink.api.dag.Transformation
import org.apache.flink.configuration.MemorySize
import org.apache.flink.table.api.config.ExecutionConfigOptions
import org.apache.flink.table.data.RowData
import org.apache.flink.table.functions.UserDefinedFunction
import org.apache.flink.table.planner.calcite.FlinkTypeFactory
import org.apache.flink.table.planner.codegen.CodeGeneratorContext
import org.apache.flink.table.planner.codegen.agg.batch.{AggWithoutKeysCodeGenerator, HashAggCodeGenerator}
import org.apache.flink.table.planner.delegation.BatchPlanner
import org.apache.flink.table.planner.plan.cost.FlinkCost._
import org.apache.flink.table.planner.plan.cost.FlinkCostFactory
import org.apache.flink.table.planner.plan.nodes.exec.LegacyBatchExecNode
import org.apache.flink.table.planner.plan.nodes.exec.utils.ExecNodeUtil
import org.apache.flink.table.planner.plan.utils.AggregateUtil.transformToBatchAggregateInfoList
import org.apache.flink.table.planner.plan.utils.FlinkRelMdUtil
import org.apache.flink.table.runtime.operators.CodeGenOperatorFactory
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo

import org.apache.calcite.plan.{RelOptCluster, RelOptCost, RelOptPlanner, RelTraitSet}
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.`type`.RelDataType
import org.apache.calcite.rel.core.AggregateCall
import org.apache.calcite.rel.metadata.RelMetadataQuery
import org.apache.calcite.util.Util

/**
  * Batch physical RelNode for hash-based aggregate operator.
  *
  * @see [[BatchPhysicalGroupAggregateBase]] for more info.
  */
abstract class BatchExecHashAggregateBase(
    cluster: RelOptCluster,
    traitSet: RelTraitSet,
    inputRel: RelNode,
    outputRowType: RelDataType,
    inputRowType: RelDataType,
    aggInputRowType: RelDataType,
    grouping: Array[Int],
    auxGrouping: Array[Int],
    aggCallToAggFunction: Seq[(AggregateCall, UserDefinedFunction)],
    isMerge: Boolean,
    isFinal: Boolean)
  extends BatchPhysicalGroupAggregateBase(
    cluster,
    traitSet,
    inputRel,
    outputRowType,
    grouping,
    auxGrouping,
    aggCallToAggFunction,
    isMerge,
    isFinal)
  with LegacyBatchExecNode[RowData] {

  override def computeSelfCost(planner: RelOptPlanner, mq: RelMetadataQuery): RelOptCost = {
    val numOfGroupKey = grouping.length
    val inputRowCnt = mq.getRowCount(getInput())
    if (inputRowCnt == null) {
      return null
    }
    val hashCpuCost = HASH_CPU_COST * inputRowCnt * numOfGroupKey
    val aggFunctionCpuCost = FUNC_CPU_COST * inputRowCnt * aggCallToAggFunction.size
    val memCost: Double = if (numOfGroupKey == 0) {
      mq.getAverageRowSize(this)
    } else {
      // assume memory is enough to hold hashTable to simplify the estimation because spill will not
      // happen under the assumption
      val ndvOfGroupKey = Util.first(mq.getRowCount(this), 1d)
      //  We aim for a 200% utilization of the bucket table.
      // TODO use BytesHashMap.BUCKET_SIZE instead of 16
      val bucketSize = ndvOfGroupKey * 16 / HASH_COLLISION_WEIGHT
      // TODO use BytesHashMap.RECORD_EXTRA_LENGTH instead of 8
      val recordSize = ndvOfGroupKey * (FlinkRelMdUtil.binaryRowAverageSize(this) + 8)
      bucketSize + recordSize
    }
    val cpuCost = hashCpuCost + aggFunctionCpuCost
    val rowCount = mq.getRowCount(this)
    val costFactory = planner.getCostFactory.asInstanceOf[FlinkCostFactory]
    costFactory.makeCost(rowCount, cpuCost, 0, 0, memCost)
  }

  //~ ExecNode methods -----------------------------------------------------------

  override protected def translateToPlanInternal(
      planner: BatchPlanner): Transformation[RowData] = {
    val config = planner.getTableConfig
    val input = getInputNodes.get(0).translateToPlan(planner)
        .asInstanceOf[Transformation[RowData]]
    val ctx = CodeGeneratorContext(config)
    val outputType = FlinkTypeFactory.toLogicalRowType(getRowType)
    val inputType = FlinkTypeFactory.toLogicalRowType(inputRowType)

    val aggInfos = transformToBatchAggregateInfoList(
      FlinkTypeFactory.toLogicalRowType(aggInputRowType), getAggCallList)

    var managedMemory: Long = 0L
    val generatedOperator = if (grouping.isEmpty) {
      AggWithoutKeysCodeGenerator.genWithoutKeys(
        ctx, planner.getRelBuilder, aggInfos, inputType, outputType, isMerge, isFinal, "NoGrouping")
    } else {
      managedMemory = MemorySize.parse(config.getConfiguration.getString(
        ExecutionConfigOptions.TABLE_EXEC_RESOURCE_HASH_AGG_MEMORY)).getBytes
      new HashAggCodeGenerator(
        ctx,
        planner.getRelBuilder,
        aggInfos,
        inputType,
        outputType,
        grouping,
        auxGrouping,
        isMerge,
        isFinal
      ).genWithKeys()
    }
    val operator = new CodeGenOperatorFactory[RowData](generatedOperator)
    ExecNodeUtil.createOneInputTransformation(
      input,
      getRelDetailedDescription,
      operator,
      InternalTypeInfo.of(outputType),
      input.getParallelism,
      managedMemory)
  }
}
