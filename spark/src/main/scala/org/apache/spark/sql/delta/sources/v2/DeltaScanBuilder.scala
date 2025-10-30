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

import scala.collection.JavaConverters._

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.connector.read.{Scan, ScanBuilder, SupportsPushDownFilters, SupportsPushDownRequiredColumns}
import org.apache.spark.sql.delta.Snapshot
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.util.CaseInsensitiveStringMap

/**
 * Scan builder for Delta tables that supports storage-partitioned joins.
 *
 * This class builds DeltaBatchQueryScan instances when SPJ is enabled,
 * allowing Spark to eliminate shuffles in partition-aware joins.
 *
 * Features:
 * - Filter pushdown support
 * - Column pruning support
 * - Conditional SPJ enablement based on configuration
 *
 * @param spark The SparkSession
 * @param snapshot The Delta snapshot to scan
 * @param options Read options
 */
class DeltaScanBuilder(
    spark: SparkSession,
    snapshot: Snapshot,
    options: CaseInsensitiveStringMap)
  extends ScanBuilder
  with SupportsPushDownFilters
  with SupportsPushDownRequiredColumns {

  private var _pushedFilters: Array[Filter] = Array.empty
  private var prunedSchema: Option[StructType] = None

  /**
   * Checks if storage-partitioned join should be enabled for this scan.
   *
   * Requirements:
   * - spark.databricks.delta.planning.preserveDataGrouping = true
   * - spark.sql.sources.v2.bucketing.enabled = true
   * - Table has partition columns
   */
  private def shouldPreserveDataGrouping: Boolean = {
    val preserveGrouping = spark.conf.get(
      DeltaSQLConf.PRESERVE_DATA_GROUPING.key,
      DeltaSQLConf.PRESERVE_DATA_GROUPING.defaultValueString
    ).toBoolean

    val v2BucketingEnabled = spark.conf.get(
      "spark.sql.sources.v2.bucketing.enabled",
      "false"
    ).toBoolean

    val hasPartitions = snapshot.metadata.partitionColumns.nonEmpty

    preserveGrouping && v2BucketingEnabled && hasPartitions
  }

  /**
   * Push filters down to the scan.
   * Returns filters that were successfully pushed.
   */
  override def pushFilters(filters: Array[Filter]): Array[Filter] = {
    // For now, accept all filters
    // Full implementation would analyze which filters can be pushed
    _pushedFilters = filters
    filters
  }

  /**
   * Returns filters that have been pushed to the scan.
   */
  override def pushedFilters(): Array[Filter] = _pushedFilters

  /**
   * Prune columns to only read what's necessary.
   */
  override def pruneColumns(requiredSchema: StructType): Unit = {
    prunedSchema = Some(requiredSchema)
  }

  /**
   * Build the scan.
   *
   * Creates a DeltaBatchQueryScan with SPJ support if enabled,
   * otherwise creates a regular scan (future work: implement regular V2 scan path).
   */
  override def build(): Scan = {
    val readSchema = prunedSchema.getOrElse(snapshot.schema)
    val preserveGrouping = shouldPreserveDataGrouping

    new DeltaBatchQueryScan(
      spark,
      snapshot,
      readSchema,
      _pushedFilters.toSeq,
      preserveGrouping
    )
  }
}
