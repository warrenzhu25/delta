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

package org.apache.spark.sql.delta

import org.apache.spark.sql.delta.catalog.DeltaTableV2
import org.apache.spark.sql.delta.commands.DeletionVectorUtils
import org.apache.spark.sql.delta.commands.cdc.CDCReader
import org.apache.spark.sql.delta.sources.DeltaSQLConf

import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Relation
import org.apache.spark.sql.internal.SQLConf

/**
 * Fall back to V1 nodes, unless the DataSource V2 read path is enabled for Storage-Partitioned
 * Join (SPJ) and the table can safely be read through it.
 */
object FallbackToV1DeltaRelation {
  def unapply(dsv2: DataSourceV2Relation): Option[LogicalRelation] = dsv2.table match {
    case d: DeltaTableV2 if dsv2.getTagValue(DeltaRelation.KEEP_AS_V2_RELATION_TAG).isEmpty =>
      if (shouldKeepAsV2ForSPJ(d, dsv2)) {
        None
      } else {
        Some(DeltaRelation.fromV2Relation(d, dsv2, dsv2.options))
      }
    case _ => None
  }

  /**
   * Returns true if the relation should stay a [[DataSourceV2Relation]] so that it is planned as
   * a `BatchScanExec` that reports `KeyGroupedPartitioning`. This requires:
   *  - both Delta's SPJ flag and Spark's V2 bucketing flag to be enabled (otherwise the V2 scan
   *    gives no benefit over the V1 scan);
   *  - the table to be partitioned;
   *  - not a CDC read;
   *  - no Deletion Vectors, unless DV support in the V2 scan is enabled.
   * The cheap configuration checks are evaluated first to avoid loading the snapshot otherwise.
   */
  private def shouldKeepAsV2ForSPJ(d: DeltaTableV2, dsv2: DataSourceV2Relation): Boolean = {
    val conf = d.spark.sessionState.conf
    val enabled = conf.getConf(DeltaSQLConf.DELTA_STORAGE_PARTITIONED_JOIN_ENABLED) &&
      conf.getConf(SQLConf.V2_BUCKETING_ENABLED) &&
      !CDCReader.isCDCRead(dsv2.options)
    enabled && {
      val snapshot = d.initialSnapshot
      snapshot.metadata.partitionColumns.nonEmpty &&
        (conf.getConf(DeltaSQLConf.DELTA_STORAGE_PARTITIONED_JOIN_DELETION_VECTORS_ENABLED) ||
          !DeletionVectorUtils.deletionVectorsReadable(snapshot))
    }
  }
}
