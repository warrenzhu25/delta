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

import org.apache.spark.paths.SparkPath
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.connector.read.{Batch, InputPartition, PartitionReaderFactory}
import org.apache.spark.sql.delta.{DeltaParquetFileFormat, Snapshot}
import org.apache.spark.sql.delta.actions.AddFile
import org.apache.spark.sql.delta.util.DeltaPartitionUtils
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.types.StructType

/**
 * Scan implementation for Delta batch queries with storage-partitioned join support.
 *
 * This class groups AddFiles by partition values and reports partitioning information
 * to Spark's query optimizer, enabling shuffle elimination in partition-aware joins.
 *
 * Based on Apache Iceberg's SparkBatchQueryScan implementation.
 *
 * NOTE: This is a prototype/stub implementation demonstrating the pattern. Full integration
 * requires:
 * 1. DeltaTableV2 to implement SupportsRead
 * 2. DeltaScanBuilder to construct these scans
 * 3. Integration with Delta's snapshot and file listing
 * 4. Proper batch and partition reader implementation
 *
 * @param spark The SparkSession
 * @param snapshot The Delta snapshot to scan
 * @param readSchema The schema to read
 * @param filters Filters to apply
 * @param preserveDataGrouping Whether to preserve data grouping for SPJ
 *
 * @see STORAGE_PARTITION_JOIN_PHASE2_NOTES.md for architectural details
 */
class DeltaBatchQueryScan(
    spark: SparkSession,
    snapshot: Snapshot,
    readSchema: StructType,
    filters: Seq[Filter],
    override val preserveDataGrouping: Boolean)
  extends DeltaPartitioningAwareScan {

  override protected def schema: StructType = readSchema

  override protected def partitionColumns: Seq[String] =
    snapshot.metadata.partitionColumns

  /**
   * Groups AddFiles by partition values.
   * Each group represents files with the same partition values.
   *
   * NOTE: This is a simplified stub. Full implementation would:
   * - Use snapshot.filesForScan() for efficient file listing
   * - Handle partition pruning based on filters
   * - Group files for optimal parallelism
   */
  private lazy val fileGroups: Map[InternalRow, Seq[AddFile]] = {
    if (groupingKeyType.nonEmpty) {
      // Get files matching filters (stub - would use actual snapshot APIs)
      val files = getFilteredFiles()

      // Group by partition values
      files.groupBy { addFile =>
        DeltaPartitionUtils.partitionValuesToRow(addFile, groupingKeyType, spark)
      }
    } else {
      // No grouping - all files in single group
      Map(InternalRow.empty -> getFilteredFiles())
    }
  }

  /**
   * Get files from snapshot, filtered by pushed filters.
   *
   * For now, we simply get all files from the snapshot. Full optimization would:
   * - Apply partition pruning based on filters
   * - Use snapshot.filesForScan() for more efficient filtering
   * - Handle deletion vectors and other Delta features
   */
  private def getFilteredFiles(): Seq[AddFile] = {
    // Get all files from snapshot as a Scala sequence
    // This is inefficient for large tables but sufficient for prototyping
    snapshot.allFiles.collect().toSeq
  }

  override protected def numFileGroups: Int = fileGroups.size

  /**
   * Creates a Batch for executing this scan.
   *
   * NOTE: This is a stub. Full implementation would:
   * - Create proper input partitions from file groups
   * - Implement partition reader factory
   * - Handle columnar reads if beneficial
   */
  override def toBatch: Batch = {
    new DeltaBatch(fileGroups, readSchema, snapshot)
  }
}

/**
 * Batch implementation that creates input partitions from file groups.
 *
 * NOTE: This is a stub implementation.
 */
class DeltaBatch(
    fileGroups: Map[InternalRow, Seq[AddFile]],
    readSchema: StructType,
    snapshot: Snapshot)
  extends Batch {

  override def planInputPartitions(): Array[InputPartition] = {
    fileGroups.flatMap { case (partitionValues, files) =>
      // Create input partitions for this file group
      // For simplicity, one partition per file group (can be optimized later)
      if (files.nonEmpty) {
        Some(new DeltaInputPartition(files, readSchema, snapshot, partitionValues))
      } else {
        None
      }
    }.toArray
  }

  override def createReaderFactory(): PartitionReaderFactory = {
    new DeltaPartitionReaderFactory(snapshot, readSchema)
  }
}

/**
 * Input partition carrying file group information.
 *
 * NOTE: This is a stub implementation.
 */
class DeltaInputPartition(
    val files: Seq[AddFile],
    val readSchema: StructType,
    val snapshot: Snapshot,
    val partitionValues: InternalRow)
  extends InputPartition {
  // Serializable implementation
  // TODO: Add proper serialization support
}

/**
 * Reader factory for Delta partitions.
 *
 * Creates readers that leverage Delta's existing file reading infrastructure
 * including support for deletion vectors, column mapping, and Parquet optimization.
 */
class DeltaPartitionReaderFactory(
    snapshot: Snapshot,
    readSchema: StructType)
  extends PartitionReaderFactory {

  override def createReader(partition: InputPartition):
      org.apache.spark.sql.connector.read.PartitionReader[InternalRow] = {
    val deltaPartition = partition.asInstanceOf[DeltaInputPartition]
    new DeltaPartitionReader(deltaPartition, readSchema, snapshot)
  }
}

/**
 * Partition reader that reads Delta files using existing Delta infrastructure.
 *
 * This leverages DeltaParquetFileFormat for actual file reading, ensuring
 * compatibility with deletion vectors, column mapping, and other Delta features.
 */
class DeltaPartitionReader(
    partition: DeltaInputPartition,
    readSchema: StructType,
    snapshot: Snapshot)
  extends org.apache.spark.sql.connector.read.PartitionReader[InternalRow] {

  private val spark = SparkSession.active

  // Create DeltaParquetFileFormat with snapshot's protocol and metadata
  private val fileFormat = DeltaParquetFileFormat(
    protocol = snapshot.protocol,
    metadata = snapshot.metadata,
    tablePath = Some(snapshot.deltaLog.dataPath.toString)
  )

  // Build the reader function using Delta's infrastructure
  private val hadoopConf = snapshot.deltaLog.newDeltaHadoopConf()
  private val readerFunction = fileFormat.buildReaderWithPartitionValues(
    sparkSession = spark,
    dataSchema = snapshot.metadata.schema,
    partitionSchema = snapshot.metadata.partitionSchema,
    requiredSchema = readSchema,
    filters = Seq.empty, // Filters already applied during file selection
    options = Map.empty,
    hadoopConf = hadoopConf
  )

  // Create iterators for each file in this partition
  private val fileIterators: Iterator[Iterator[InternalRow]] =
    partition.files.iterator.map { addFile =>
    // Convert AddFile to PartitionedFile
    val filePath = new org.apache.hadoop.fs.Path(
      snapshot.deltaLog.dataPath,
      addFile.path
    )

    val partitionedFile = org.apache.spark.sql.execution.datasources.PartitionedFile(
      partitionValues = partition.partitionValues,
      filePath = SparkPath.fromPath(filePath),
      start = 0,
      length = addFile.size,
      locations = Array.empty // Spark will determine locations
    )

    // Use the reader function to read this file
    readerFunction(partitionedFile)
  }

  // Flatten all file iterators into a single iterator
  private val rowIterator: Iterator[InternalRow] = fileIterators.flatten

  override def next(): Boolean = rowIterator.hasNext

  override def get(): InternalRow = rowIterator.next()

  override def close(): Unit = {
    // Cleanup if needed
    // The underlying iterators from Parquet will handle their own cleanup
  }
}
