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

import java.util

import org.apache.spark.sql.connector.catalog.{
  SupportsRead,
  SupportsWrite,
  Table,
  TableCapability
}
import org.apache.spark.sql.connector.expressions.Transform
import org.apache.spark.sql.connector.read.ScanBuilder
import org.apache.spark.sql.connector.write.{LogicalWriteInfo, WriteBuilder}
import org.apache.spark.sql.delta.catalog.DeltaTableV2
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.util.CaseInsensitiveStringMap

/**
 * Wrapper around DeltaTableV2 that adds DataSource V2 scan support for storage-partitioned joins.
 *
 * This wrapper delegates all operations to the underlying DeltaTableV2 except for scan creation,
 * where it provides a DeltaScanBuilder that supports SPJ.
 *
 * NOTE: This is experimental integration approach. The wrapper pattern allows us to add V2 scan
 * support without modifying DeltaTableV2 directly, making it easier to feature-flag and test.
 *
 * Usage:
 * - DeltaCatalog can wrap DeltaTableV2 instances when SPJ is enabled
 * - Spark will see this as a Table that can provide scans
 * - The scan builder will report partitioning for SPJ optimization
 *
 * @param underlying The DeltaTableV2 instance to wrap
 */
class DeltaTableV2WithV2Scans(underlying: DeltaTableV2)
  extends Table
  with SupportsRead
  with SupportsWrite {

  /**
   * Provide scan builder for reads.
   * This is the key method that enables V2 scans with SPJ support.
   *
   * Implements SupportsRead.newScanBuilder to properly integrate with Spark's query planner.
   * The scan builder will:
   * - Report KeyGroupedPartitioning for partitioned tables
   * - Enable storage-partitioned join optimization
   * - Support filter and column pushdown
   */
  override def newScanBuilder(options: CaseInsensitiveStringMap): ScanBuilder = {
    underlying.createScanBuilder(options)
  }

  // Delegate all Table methods to underlying

  override def name(): String = underlying.name()

  override def schema(): StructType = underlying.schema()

  override def partitioning(): Array[Transform] = underlying.partitioning()

  override def properties(): util.Map[String, String] = underlying.properties()

  override def capabilities(): util.Set[TableCapability] = underlying.capabilities()

  // Delegate SupportsWrite methods to underlying

  override def newWriteBuilder(info: LogicalWriteInfo): WriteBuilder = {
    underlying.newWriteBuilder(info)
  }

  /**
   * Returns a string representation for logging and debugging.
   */
  override def toString: String = s"DeltaTableV2WithV2Scans(${underlying.name()})"
}
