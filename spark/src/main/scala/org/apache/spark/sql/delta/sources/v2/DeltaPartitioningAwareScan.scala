/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta.sources.v2

import org.apache.spark.sql.connector.expressions.Expressions
import org.apache.spark.sql.connector.read.{Scan, SupportsReportPartitioning}
import org.apache.spark.sql.connector.read.partitioning.{
  KeyGroupedPartitioning,
  Partitioning,
  UnknownPartitioning
}
import org.apache.spark.sql.delta.util.DeltaPartitionUtils
import org.apache.spark.sql.types.StructType

/**
 * Trait for Delta scans that report partitioning information to Spark's query optimizer.
 *
 * This enables storage-partitioned joins (SPJ) where Spark can eliminate shuffles
 * when joining tables on their partition columns.
 *
 * Based on Apache Iceberg's SparkPartitioningAwareScan implementation.
 *
 * NOTE: This is a prototype implementation. Full integration with Delta's query planning
 * requires additional architectural changes, as Delta currently uses DataSource V1 for reads.
 *
 * Requirements:
 * - Spark 3.3+ (for SupportsReportPartitioning API)
 * - spark.databricks.delta.planning.preserveDataGrouping=true
 * - spark.sql.sources.v2.bucketing.enabled=true
 *
 * @see STORAGE_PARTITION_JOIN_PHASE2_NOTES.md for architectural details
 */
trait DeltaPartitioningAwareScan extends Scan with SupportsReportPartitioning {

  /**
   * The full table schema
   */
  protected def schema: StructType

  /**
   * The partition column names for this table
   */
  protected def partitionColumns: Seq[String]

  /**
   * Whether to preserve data grouping (from configuration)
   */
  protected def preserveDataGrouping: Boolean

  /**
   * Compute the grouping key type (partition columns schema).
   * Cached to ensure consistency across multiple calls.
   */
  private lazy val _groupingKeyType: StructType = {
    if (preserveDataGrouping && partitionColumns.nonEmpty) {
      DeltaPartitionUtils.groupingKeyType(schema, partitionColumns)
    } else {
      StructType(Seq.empty)
    }
  }

  /**
   * Returns the grouping key type for this scan
   */
  def groupingKeyType: StructType = _groupingKeyType

  /**
   * Reports partitioning to Spark's query optimizer.
   *
   * Returns KeyGroupedPartitioning if:
   * - preserve-data-grouping is enabled
   * - table has partition columns
   * - spark.sql.sources.v2.bucketing.enabled is true (checked by Spark)
   *
   * Otherwise returns UnknownPartitioning (no optimization).
   */
  override def outputPartitioning(): Partitioning = {
    if (_groupingKeyType.nonEmpty) {
      // Report key-grouped partitioning to Spark
      // This tells Spark that data is already grouped by partition columns
      new KeyGroupedPartitioning(
        toSparkTransforms(_groupingKeyType),
        numPartitions = numFileGroups
      )
    } else {
      // No partitioning information available
      new UnknownPartitioning(0)
    }
  }

  /**
   * Converts partition column types to Spark transforms.
   * For Delta (identity partitioning only), this is straightforward.
   */
  private def toSparkTransforms(
      keyType: StructType): Array[org.apache.spark.sql.connector.expressions.Transform] = {
    keyType.fields.map { field =>
      Expressions.identity(field.name)
    }
  }

  /**
   * Number of file groups (unique partition value combinations).
   * Subclasses must implement this based on their file grouping logic.
   */
  protected def numFileGroups: Int

  /**
   * Read schema for this scan
   */
  override def readSchema(): StructType = schema

  /**
   * Returns a description of this scan for explain output
   */
  override def description(): String = {
    val partitionInfo = if (_groupingKeyType.nonEmpty) {
      s"partitioned by [${partitionColumns.mkString(", ")}]"
    } else {
      "non-partitioned"
    }
    s"DeltaScan($partitionInfo, numFileGroups=$numFileGroups)"
  }
}
