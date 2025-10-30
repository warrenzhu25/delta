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

package org.apache.spark.sql.delta.util

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Cast, GenericInternalRow, Literal}
import org.apache.spark.sql.delta.DeltaColumnMapping
import org.apache.spark.sql.delta.actions.AddFile
import org.apache.spark.sql.types.{StructField, StructType}

/**
 * Utility methods for working with Delta table partitions, particularly for
 * storage-partitioned join support.
 */
object DeltaPartitionUtils {

  /**
   * Computes the grouping key type from partition columns.
   *
   * For Delta, this is simplified compared to Iceberg because Delta only supports
   * identity partitioning (no transforms like bucket, truncate, etc.).
   *
   * @param schema The full table schema
   * @param partitionColumns The list of partition column names
   * @return A StructType containing only the partition columns from the schema
   */
  def groupingKeyType(schema: StructType, partitionColumns: Seq[String]): StructType = {
    if (partitionColumns.isEmpty) {
      // No partitioning - return empty struct
      StructType(Seq.empty)
    } else {
      // Extract partition column fields from schema
      // Use case-insensitive matching for partition column names
      val partitionFields = partitionColumns.flatMap { colName =>
        schema.fields.find(_.name.equalsIgnoreCase(colName))
      }
      StructType(partitionFields)
    }
  }

  /**
   * Checks if the given partition columns are valid for storage-partitioned joins.
   *
   * @param partitionColumns The partition column names
   * @return True if partitioning is valid for SPJ
   */
  def isValidForStoragePartitionedJoin(partitionColumns: Seq[String]): Boolean = {
    // For Delta, any non-empty partition column list is valid (identity partitioning only)
    partitionColumns.nonEmpty
  }

  /**
   * Extracts partition values from an AddFile as an InternalRow for grouping.
   *
   * @param addFile The AddFile containing partition values
   * @param partitionSchema The schema of partition columns
   * @param spark The SparkSession for accessing session configuration
   * @return An InternalRow representing the partition values
   */
  def partitionValuesToRow(
      addFile: AddFile,
      partitionSchema: StructType,
      spark: SparkSession): InternalRow = {
    // AddFile.partitionValues is a Map[String, String]
    // We need to convert it to an InternalRow based on the partitionSchema
    // Use the same logic as TahoeFileIndex.getPartitionValuesRow
    val timeZone = spark.sessionState.conf.sessionLocalTimeZone
    val partitionRowValues = partitionSchema.map { p =>
      val colName = DeltaColumnMapping.getPhysicalName(p)
      val partValue = Literal(addFile.partitionValues.get(colName).orNull)
      Cast(partValue, p.dataType, Option(timeZone), ansiEnabled = false).eval()
    }.toArray
    new GenericInternalRow(partitionRowValues)
  }
}
