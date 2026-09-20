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
import org.apache.spark.sql.delta.sources.DeltaSQLConf

import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Relation

/**
 * Fall back to V1 nodes unless DataSource V2 read is requested (e.g. for Storage-Partitioned Join).
 */
object FallbackToV1DeltaRelation {
  def unapply(dsv2: DataSourceV2Relation): Option[LogicalRelation] = dsv2.table match {
    case d: DeltaTableV2 if dsv2.getTagValue(DeltaRelation.KEEP_AS_V2_RELATION_TAG).isEmpty =>
      val spjEnabled = d.spark.sessionState.conf.getConf(
        DeltaSQLConf.DELTA_STORAGE_PARTITIONED_JOIN_ENABLED)
      val isPartitioned = d.initialSnapshot.metadata.partitionColumns.nonEmpty
      if (spjEnabled && isPartitioned) {
        None
      } else {
        Some(DeltaRelation.fromV2Relation(d, dsv2, dsv2.options))
      }
    case _ => None
  }
}
