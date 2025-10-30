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

package org.apache.spark.sql.delta.catalog

import org.apache.spark.sql.connector.catalog.{Identifier, Table}
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.spark.sql.delta.sources.v2.DeltaTableV2WithV2Scans

/**
 * Extension of DeltaCatalog that provides DataSource V2 scan support with
 * storage-partitioned joins (SPJ).
 *
 * This catalog wraps DeltaTableV2 instances with DeltaTableV2WithV2Scans when SPJ is enabled,
 * allowing Spark to use V2 scans and eliminate shuffles in partition-aware joins.
 *
 * == How to Use ==
 *
 * 1. Configure Spark to use this catalog:
 * ```
 * spark.sql.catalog.spark_catalog = org.apache.spark.sql.delta.catalog.DeltaCatalogWithSPJ
 * ```
 *
 * 2. Enable required configurations:
 * ```
 * spark.databricks.delta.planning.preserveDataGrouping = true
 * spark.sql.sources.v2.bucketing.enabled = true
 * ```
 *
 * 3. Query partitioned tables:
 * ```sql
 * -- Tables partitioned on same columns
 * CREATE TABLE t1 (id INT, date STRING, value INT) PARTITIONED BY (date);
 * CREATE TABLE t2 (id INT, date STRING, value INT) PARTITIONED BY (date);
 *
 * -- Join will use SPJ and avoid shuffle
 * SELECT * FROM t1 JOIN t2 ON t1.id = t2.id AND t1.date = t2.date;
 * ```
 *
 * == Status ==
 *
 * **EXPERIMENTAL**: This is a prototype implementation demonstrating the SPJ integration pattern.
 *
 * Full production readiness requires:
 * - Complete reader factory implementation with actual file reading
 * - Comprehensive testing with various query patterns
 * - Performance validation and benchmarking
 * - Edge case handling (nulls, schema evolution, etc.)
 *
 * See STORAGE_PARTITION_JOIN_PHASE3_SUMMARY.md for details.
 */
class DeltaCatalogWithSPJ extends DeltaCatalog {

  /**
   * Checks if storage-partitioned join should be enabled.
   *
   * Requirements:
   * - spark.databricks.delta.planning.preserveDataGrouping = true
   * - spark.sql.sources.v2.bucketing.enabled = true
   */
  private def shouldEnableSPJ: Boolean = {
    val preserveGrouping = spark.conf.get(
      DeltaSQLConf.PRESERVE_DATA_GROUPING.key,
      DeltaSQLConf.PRESERVE_DATA_GROUPING.defaultValueString
    ).toBoolean

    val v2BucketingEnabled = spark.conf.get(
      "spark.sql.sources.v2.bucketing.enabled",
      "false"
    ).toBoolean

    preserveGrouping && v2BucketingEnabled
  }

  /**
   * Override loadTable to wrap DeltaTableV2 with V2 scan support when SPJ is enabled.
   */
  override def loadTable(ident: Identifier): Table = {
    val table = super.loadTable(ident)

    table match {
      case deltaTable: DeltaTableV2 if shouldEnableSPJ =>
        // Wrap with V2 scan support for SPJ
        logInfo(s"Enabling SPJ for table: ${ident.toString}")
        new DeltaTableV2WithV2Scans(deltaTable)
      case other =>
        // Return table as-is (no SPJ)
        other
    }
  }

  /**
   * Override time travel loadTable to maintain consistency.
   */
  override def loadTable(ident: Identifier, timestamp: Long): Table = {
    val table = super.loadTable(ident, timestamp)

    table match {
      case deltaTable: DeltaTableV2 if shouldEnableSPJ =>
        logInfo(s"Enabling SPJ for time travel table: ${ident.toString} at timestamp $timestamp")
        new DeltaTableV2WithV2Scans(deltaTable)
      case other =>
        other
    }
  }

  /**
   * Override version-based time travel to maintain consistency.
   */
  override def loadTable(ident: Identifier, version: String): Table = {
    val table = super.loadTable(ident, version)

    table match {
      case deltaTable: DeltaTableV2 if shouldEnableSPJ =>
        logInfo(s"Enabling SPJ for time travel table: ${ident.toString} at version $version")
        new DeltaTableV2WithV2Scans(deltaTable)
      case other =>
        other
    }
  }
}
