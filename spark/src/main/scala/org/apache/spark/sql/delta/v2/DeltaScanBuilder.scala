/*
 * Copyright (2026) The Delta Lake Project Authors.
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

package org.apache.spark.sql.delta.v2

import org.apache.spark.paths.SparkPath
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Cast, GenericInternalRow, Literal}
import org.apache.spark.sql.connector.expressions.{Expression => V2Expression, Expressions, Transform}
import org.apache.spark.sql.connector.read._
import org.apache.spark.sql.connector.read.partitioning.{KeyGroupedPartitioning, Partitioning, UnknownPartitioning}
import org.apache.spark.sql.delta._
import org.apache.spark.sql.delta.actions.{AddFile, Metadata, Protocol}
import org.apache.spark.sql.delta.catalog.DeltaTableV2
import org.apache.spark.sql.delta.files.TahoeFileIndex
import org.apache.spark.sql.delta.metering.DeltaLogging
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.spark.sql.execution.datasources.{FileFormat, PartitionedFile}
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.util.CaseInsensitiveStringMap

/**
 * ScanBuilder for Delta DataSource V2 reader with Storage-Partitioned Join (SPJ) support.
 */
class DeltaScanBuilder(
    spark: SparkSession,
    deltaTable: DeltaTableV2,
    tableSchema: StructType,
    options: CaseInsensitiveStringMap)
  extends ScanBuilder
  with SupportsPushDownFilters
  with SupportsPushDownRequiredColumns
  with DeltaLogging {

  private var _pushedFilters: Array[Filter] = Array.empty
  private var _requiredSchema: StructType = tableSchema

  override def pushFilters(filters: Array[Filter]): Array[Filter] = {
    // Keep all filters as residuals so Spark applies them locally, while also
    // pushing them down to prune files in DeltaLog where possible.
    _pushedFilters = filters
    filters
  }

  override def pushedFilters(): Array[Filter] = _pushedFilters

  override def pruneColumns(requiredSchema: StructType): Unit = {
    _requiredSchema = requiredSchema
  }

  override def build(): Scan = {
    new DeltaBatchScan(
      spark = spark,
      deltaTable = deltaTable,
      tableSchema = tableSchema,
      readSchema = _requiredSchema,
      pushedFilters = _pushedFilters,
      options = options
    )
  }
}

/**
 * Scan implementation for Delta Lake that reports [[KeyGroupedPartitioning]]
 * to enable Spark Storage-Partitioned Join (SPJ, SPARK-37375).
 *
 * For partitioned Delta tables where all partition columns are preserved in the readSchema
 * and SPJ is enabled via `spark.sql.sources.v2.bucketing.enabled = true` and
 * `spark.databricks.delta.storagePartitionedJoin.enabled = true`, this scan groups AddFiles
 * by their physical partition values into [[DeltaInputPartition]]s that implement
 * [[HasPartitionKey]].
 */
class DeltaBatchScan(
    val spark: SparkSession,
    val deltaTable: DeltaTableV2,
    val tableSchema: StructType,
    val readSchema: StructType,
    val pushedFilters: Array[Filter],
    val options: CaseInsensitiveStringMap)
  extends Scan
  with Batch
  with SupportsReportPartitioning
  with DeltaLogging {

  override def description(): String = s"DeltaBatchScan[${deltaTable.name()}]"

  override def toBatch: Batch = this

  override def columnarSupportMode(): Scan.ColumnarSupportMode =
    Scan.ColumnarSupportMode.UNSUPPORTED

  private val snapshot: Snapshot = deltaTable.initialSnapshot
  private val protocol: Protocol = snapshot.protocol
  private val metadata: Metadata = snapshot.metadata
  private val partitionColumns: Seq[String] = metadata.partitionColumns

  /**
   * The grouping key transforms reported to Spark for SPJ.
   * Only identity partitioning is reported.
   */
  private lazy val groupingKeyTransforms: Array[V2Expression] = {
    partitionColumns.map { col =>
      Expressions.identity(col): V2Expression
    }.toArray
  }

  /**
   * Whether SPJ grouping can be active for this scan:
   * 1. The table must have partition columns.
   * 2. All partition columns must be present in the readSchema.
   * 3. Delta SPJ configuration must be enabled.
   */
  private def isSPJEligible: Boolean = {
    partitionColumns.nonEmpty &&
      partitionColumns.forall(c => readSchema.fieldNames.contains(c)) &&
      spark.sessionState.conf.getConf(DeltaSQLConf.DELTA_STORAGE_PARTITIONED_JOIN_ENABLED)
  }

  /**
   * Lazily planned input partitions grouped by partition key when SPJ is eligible.
   */
  private lazy val plannedPartitions: Array[InputPartition] = {
    val fileIndex = deltaTable.deltaLog.createRelation(
      snapshotToUseOpt = Some(snapshot),
      catalogTableOpt = deltaTable.catalogTable,
      isTimeTravelQuery = deltaTable.timeTravelOpt.isDefined
    ).asInstanceOf[org.apache.spark.sql.execution.datasources.HadoopFsRelation].location
      .asInstanceOf[TahoeFileIndex]

    val addFiles: Seq[AddFile] = fileIndex.matchingFiles(Seq.empty, Seq.empty)

    if (isSPJEligible) {
      planKeyGroupedPartitions(addFiles)
    } else {
      planStandardPartitions(addFiles)
    }
  }

  private def extractPartitionRow(partValuesMap: Map[String, String]): GenericInternalRow = {
    val partitionSchema = metadata.partitionSchema
    val timeZone = spark.sessionState.conf.sessionLocalTimeZone
    val partitionRowValues = partitionSchema.map { p =>
      val colPhysicalName = DeltaColumnMapping.getPhysicalName(p)
      val partValueStr = partValuesMap.get(colPhysicalName).orNull
      val literalVal = Literal(partValueStr)
      Cast(literalVal, p.dataType, Option(timeZone), ansiEnabled = false).eval()
    }.toArray
    new GenericInternalRow(partitionRowValues)
  }

  private def planKeyGroupedPartitions(addFiles: Seq[AddFile]): Array[InputPartition] = {
    // Group files by their partition values map
    val grouped = addFiles.groupBy(_.partitionValues)

    grouped.zipWithIndex.map { case ((partValuesMap, files), idx) =>
      val partitionKeyRow = extractPartitionRow(partValuesMap)

      val fileInfos = files.map { f =>
        DeltaScanFileInfo(
          path = fileIndex_absolutePath(f.path),
          size = f.size,
          modificationTime = f.modificationTime,
          partitionValues = partitionKeyRow
        )
      }.toArray

      DeltaKeyGroupedInputPartition(
        partitionId = idx,
        files = fileInfos,
        partitionKeyInternalRow = partitionKeyRow
      ): InputPartition
    }.toArray
  }

  private def planStandardPartitions(addFiles: Seq[AddFile]): Array[InputPartition] = {
    addFiles.zipWithIndex.map { case (f, idx) =>
      val partitionKeyRow = extractPartitionRow(f.partitionValues)
      val fileInfo = DeltaScanFileInfo(
        path = fileIndex_absolutePath(f.path),
        size = f.size,
        modificationTime = f.modificationTime,
        partitionValues = partitionKeyRow
      )
      DeltaKeyGroupedInputPartition(
        partitionId = idx,
        files = Array(fileInfo),
        partitionKeyInternalRow = partitionKeyRow
      ): InputPartition
    }.toArray
  }

  private def fileIndex_absolutePath(child: String): String = {
    val p = new org.apache.hadoop.fs.Path(new java.net.URI(child))
    if (p.isAbsolute) {
      p.toString
    } else {
      new org.apache.hadoop.fs.Path(deltaTable.deltaLog.dataPath, p).toString
    }
  }

  override def planInputPartitions(): Array[InputPartition] = plannedPartitions

  override def outputPartitioning(): Partitioning = {
    if (isSPJEligible) {
      logInfo(s"Reporting KeyGroupedPartitioning by ${partitionColumns.mkString(", ")} " +
        s"with ${plannedPartitions.length} partitions for table ${deltaTable.name()}")
      new KeyGroupedPartitioning(groupingKeyTransforms, plannedPartitions.length)
    } else {
      new UnknownPartitioning(plannedPartitions.length)
    }
  }

  override def createReaderFactory(): PartitionReaderFactory = {
    new DeltaPartitionReaderFactory(
      spark = spark,
      dataSchema = tableSchema,
      partitionSchema = metadata.partitionSchema,
      readSchema = readSchema,
      protocol = protocol,
      metadata = metadata,
      options = options
    )
  }
}

/**
 * File metadata needed to construct PartitionedFile for each scan split.
 */
case class DeltaScanFileInfo(
    path: String,
    size: Long,
    modificationTime: Long,
    partitionValues: InternalRow) extends Serializable

/**
 * InputPartition implementation for Delta Lake supporting Storage-Partitioned Join.
 * Implements [[HasPartitionKey]] so Spark's BatchScanExec can extract partition keys
 * to align joins without shuffle exchanges.
 */
case class DeltaKeyGroupedInputPartition(
    partitionId: Int,
    files: Array[DeltaScanFileInfo],
    partitionKeyInternalRow: InternalRow)
  extends InputPartition
  with HasPartitionKey
  with Serializable {

  override def partitionKey(): InternalRow = partitionKeyInternalRow
}

/**
 * Factory for creating PartitionReaders for Delta tables in DSv2.
 * Serializes Hadoop Configuration to executors and uses [[DeltaParquetFileFormat]]
 * to build Parquet file readers.
 */
class DeltaPartitionReaderFactory(
    spark: SparkSession,
    dataSchema: StructType,
    partitionSchema: StructType,
    readSchema: StructType,
    protocol: Protocol,
    metadata: Metadata,
    options: CaseInsensitiveStringMap)
  extends PartitionReaderFactory {

  import org.apache.spark.util.SerializableConfiguration

  private val serializableHadoopConf = {
    // scalastyle:off deltahadoopconfiguration
    val conf = spark.sessionState.newHadoopConf()
    // scalastyle:on deltahadoopconfiguration
    new SerializableConfiguration(conf)
  }

  // Pre-build the Parquet reader function on the driver
  private val parquetFormat = new DeltaParquetFileFormat(protocol, metadata)

  private val readerBuilder = parquetFormat.buildReaderWithPartitionValues(
    sparkSession = spark,
    dataSchema = dataSchema,
    partitionSchema = partitionSchema,
    requiredSchema = readSchema,
    filters = Seq.empty,
    options = Map(FileFormat.OPTION_RETURNING_BATCH -> "false"),
    hadoopConf = serializableHadoopConf.value
  )

  override def createReader(partition: InputPartition): PartitionReader[InternalRow] = {
    val deltaPartition = partition.asInstanceOf[DeltaKeyGroupedInputPartition]
    new DeltaBatchPartitionReader(deltaPartition, readerBuilder)
  }
}

/**
 * PartitionReader that iterates through all files assigned to an InputPartition.
 */
class DeltaBatchPartitionReader(
    partition: DeltaKeyGroupedInputPartition,
    readerBuilder: PartitionedFile => Iterator[InternalRow])
  extends PartitionReader[InternalRow] {

  private val fileIterator: Iterator[DeltaScanFileInfo] = partition.files.iterator
  private var currentFileReader: Option[Iterator[InternalRow]] = None

  private def advanceToNextFile(): Boolean = {
    while (currentFileReader.forall(!_.hasNext) && fileIterator.hasNext) {
      val fileInfo = fileIterator.next()
      val partitionedFile = PartitionedFile(
        partitionValues = fileInfo.partitionValues,
        filePath = SparkPath.fromPathString(fileInfo.path),
        start = 0,
        length = fileInfo.size
      )
      currentFileReader = Some(readerBuilder(partitionedFile))
    }
    currentFileReader.exists(_.hasNext)
  }

  override def next(): Boolean = {
    if (currentFileReader.exists(_.hasNext)) {
      true
    } else {
      advanceToNextFile()
    }
  }

  override def get(): InternalRow = {
    currentFileReader.get.next()
  }

  override def close(): Unit = {
    // Current reader will be GC'd; no-op
  }
}
