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

import java.net.URI
import java.util.{Locale, OptionalLong}

import scala.util.Try

import org.apache.spark.sql.delta._
import org.apache.spark.sql.delta.actions.{AddFile, Metadata, Protocol}
import org.apache.spark.sql.delta.catalog.DeltaTableV2
import org.apache.spark.sql.delta.logging.DeltaLogKeys
import org.apache.spark.sql.delta.metering.DeltaLogging
import org.apache.spark.sql.delta.sources.{DeltaSourceUtils, DeltaSQLConf}
import org.apache.hadoop.fs.Path

import org.apache.spark.internal.MDC
import org.apache.spark.paths.SparkPath
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
import org.apache.spark.sql.catalyst.expressions.{BoundReference, Cast, Expression, GenericInternalRow, Literal, UnsafeProjection}
import org.apache.spark.sql.catalyst.types.DataTypeUtils
import org.apache.spark.sql.connector.expressions.{Expression => V2Expression, Expressions}
import org.apache.spark.sql.connector.read._
import org.apache.spark.sql.connector.read.partitioning.{KeyGroupedPartitioning, Partitioning, UnknownPartitioning}
import org.apache.spark.sql.execution.datasources.{FileFormat, PartitionedFile}
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.types.{StructField, StructType}
import org.apache.spark.sql.util.CaseInsensitiveStringMap
import org.apache.spark.util.SerializableConfiguration

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
    // translating them into Catalyst predicates to prune files/partitions in DeltaLog.
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
 * When SPJ is enabled (`spark.databricks.delta.storagePartitionedJoin.enabled = true`) and at
 * least one partition column is present in the readSchema, this scan groups AddFiles by the
 * values of the partition columns present in the readSchema into
 * [[DeltaKeyGroupedInputPartition]]s that implement [[HasPartitionKey]].
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
  with SupportsReportStatistics
  with DeltaLogging {

  override def description(): String = s"DeltaBatchScan[${deltaTable.name()}]"

  override def toBatch: Batch = this

  override def columnarSupportMode(): Scan.ColumnarSupportMode =
    Scan.ColumnarSupportMode.UNSUPPORTED

  private val snapshot: Snapshot = deltaTable.initialSnapshot
  private val protocol: Protocol = snapshot.protocol
  private val metadata: Metadata = snapshot.metadata

  // scalastyle:off caselocale
  private lazy val readFieldNamesLower: Set[String] =
    readSchema.fieldNames.map(_.toLowerCase(Locale.ROOT)).toSet

  /**
   * Partition columns that are present in the projected `readSchema`.
   * Following Apache Iceberg (`Partitioning.groupingKeyType`), only partition columns
   * preserved in the query projection are included in the grouping key. This enables SPJ
   * even when the query only projects and joins on a subset of the table's partition columns.
   */
  private lazy val projectedPartitionFields = metadata.partitionSchema.filter { f =>
    readFieldNamesLower.contains(f.name.toLowerCase(Locale.ROOT))
  }
  // scalastyle:on caselocale

  private lazy val projectedPartitionColumns: Seq[String] =
    projectedPartitionFields.map(_.name)

  /**
   * The grouping key transforms reported to Spark for SPJ.
   * Only identity partitioning on projected partition columns is reported.
   */
  private lazy val groupingKeyTransforms: Array[V2Expression] = {
    projectedPartitionColumns.map { col =>
      Expressions.identity(col): V2Expression
    }.toArray
  }

  /**
   * Whether SPJ grouping can be active for this scan:
   * 1. At least one partition column is present in the projected readSchema.
   * 2. Delta SPJ configuration is enabled.
   */
  private def isSPJEligible: Boolean = {
    projectedPartitionColumns.nonEmpty &&
      spark.sessionState.conf.getConf(DeltaSQLConf.DELTA_STORAGE_PARTITIONED_JOIN_ENABLED)
  }

  /**
   * Lazily planned input partitions:
   * - Prunes files in the Delta Snapshot using pushed filters (partition pruning & data skipping).
   * - Groups matching AddFiles by projected partition key when SPJ is eligible.
   */
  private lazy val selectedFiles: Seq[AddFile] = {
    val attrMap = DataTypeUtils.toAttributes(tableSchema).map(a => a.name -> a).toMap
    // Filters that cannot be translated or resolved are skipped here; they are still applied
    // by Spark after the scan since all pushed filters are reported as residuals.
    val catalystFilters: Seq[Expression] = pushedFilters.toSeq.flatMap { f =>
      Try(DeltaSourceUtils.translateFilters(Array(f))).toOption
    }.map { expr =>
      expr.transform {
        case u: UnresolvedAttribute => attrMap.getOrElse(u.name, u)
      }
    }.filter(_.resolved)

    snapshot.filesForScan(catalystFilters).files
  }

  private lazy val plannedPartitions: Array[InputPartition] = {
    if (isSPJEligible) {
      val projectedPhysicalCols = projectedPartitionFields.map(DeltaColumnMapping.getPhysicalName)
      val grouped = selectedFiles.groupBy { f =>
        projectedPhysicalCols.map(col => col -> f.partitionValues.getOrElse(col, null)).toMap
      }.toSeq
      planPartitions(grouped)
    } else {
      planPartitions(selectedFiles.map(f => (f.partitionValues, Seq(f))))
    }
  }

  /**
   * Statistics of the selected files, used by Spark's planner (e.g. to pick broadcast joins).
   * Without them Spark assumes `spark.sql.defaultSizeInBytes` (effectively infinite).
   * Like V1 (`HadoopFsRelation.sizeInBytes`), only the size is reported: the total size of the
   * selected files scaled by `spark.sql.sources.fileCompressionFactor`. A row count is not
   * reported because `filesForScan` doesn't keep per-file record counts by default.
   */
  private lazy val scanStatistics: Statistics = {
    val compressionFactor = spark.sessionState.conf.fileCompressionFactor
    val totalSize = (selectedFiles.map(_.size).sum * compressionFactor).toLong
    new Statistics {
      override def sizeInBytes(): OptionalLong = OptionalLong.of(totalSize)
      override def numRows(): OptionalLong = OptionalLong.empty()
    }
  }

  override def estimateStatistics(): Statistics = scanStatistics

  private def extractPartitionRow(
      partValuesMap: Map[String, String],
      schemaFields: Seq[StructField]): GenericInternalRow = {
    val timeZone = spark.sessionState.conf.sessionLocalTimeZone
    val partitionRowValues = schemaFields.map { p =>
      val colPhysicalName = DeltaColumnMapping.getPhysicalName(p)
      val partValueStr = partValuesMap.get(colPhysicalName).orNull
      val literalVal = Literal(partValueStr)
      Cast(literalVal, p.dataType, Option(timeZone), ansiEnabled = false).eval()
    }.toArray
    new GenericInternalRow(partitionRowValues)
  }

  private def planPartitions(
      groups: Seq[(Map[String, String], Seq[AddFile])]): Array[InputPartition] = {
    groups.zipWithIndex.map { case (_, files) -> idx =>
      val groupingKeyRow = extractPartitionRow(files.head.partitionValues, projectedPartitionFields)
      val fileInfos = files.map { f =>
        val fullFilePartitionRow = extractPartitionRow(f.partitionValues, metadata.partitionSchema)
        DeltaScanFileInfo(
          path = resolveFilePath(f.path),
          size = f.size,
          modificationTime = f.modificationTime,
          partitionValues = fullFilePartitionRow
        )
      }.toArray

      DeltaKeyGroupedInputPartition(
        partitionId = idx,
        files = fileInfos,
        partitionKeyInternalRow = groupingKeyRow
      ): InputPartition
    }.toArray
  }

  private def resolveFilePath(child: String): String = {
    // scalastyle:off pathfromuri
    val p = new Path(new URI(child))
    // scalastyle:on pathfromuri
    if (p.isAbsolute) {
      p.toString
    } else {
      new Path(deltaTable.deltaLog.dataPath, p).toString
    }
  }

  override def planInputPartitions(): Array[InputPartition] = plannedPartitions

  override def outputPartitioning(): Partitioning = reportedPartitioning

  private lazy val reportedPartitioning: Partitioning = {
    if (isSPJEligible) {
      logInfo(log"Reporting KeyGroupedPartitioning with " +
        log"${MDC(DeltaLogKeys.NUM_PARTITIONS, plannedPartitions.length)} partitions for " +
        log"table ${MDC(DeltaLogKeys.TABLE_NAME, deltaTable.name())}")
      new KeyGroupedPartitioning(groupingKeyTransforms, plannedPartitions.length)
    } else {
      new UnknownPartitioning(plannedPartitions.length)
    }
  }

  override def createReaderFactory(): PartitionReaderFactory = {
    val hadoopConf = new SerializableConfiguration(deltaTable.deltaLog.newDeltaHadoopConf())
    new DeltaPartitionReaderFactory(
      spark = spark,
      dataSchema = tableSchema,
      partitionSchema = metadata.partitionSchema,
      readSchema = readSchema,
      protocol = protocol,
      metadata = metadata,
      pushedFilters = pushedFilters,
      serializableHadoopConf = hadoopConf
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
 * Uses [[DeltaParquetFileFormat]] to build Parquet file readers on the driver.
 *
 * Like the V1 `FileSourceScanExec`, partition columns are never read from the Parquet files
 * (they may or may not be materialized there). Only the data columns of `readSchema` are
 * requested from the file reader, which appends the partition values taken from the Delta log
 * (`PartitionedFile.partitionValues`). The resulting `dataColumns ++ partitionColumns` row is
 * then projected into `readSchema` order.
 */
class DeltaPartitionReaderFactory(
    spark: SparkSession,
    dataSchema: StructType,
    partitionSchema: StructType,
    readSchema: StructType,
    protocol: Protocol,
    metadata: Metadata,
    pushedFilters: Array[Filter],
    serializableHadoopConf: SerializableConfiguration)
  extends PartitionReaderFactory {

  // scalastyle:off caselocale
  private val readDataSchema: StructType = {
    val partitionNames = partitionSchema.fieldNames.map(_.toLowerCase(Locale.ROOT)).toSet
    StructType(readSchema.filterNot(f => partitionNames.contains(f.name.toLowerCase(Locale.ROOT))))
  }
  // scalastyle:on caselocale

  /** Schema of the rows produced by the file reader: `readDataSchema ++ partitionSchema`. */
  private val fileOutputSchema: StructType = StructType(readDataSchema ++ partitionSchema)

  /** For each `readSchema` column, its ordinal in `fileOutputSchema`. Resolved on the driver. */
  private val outputOrdinals: Array[Int] = {
    val resolver = spark.sessionState.conf.resolver
    readSchema.fieldNames.map { name =>
      val idx = fileOutputSchema.fieldNames.indexWhere(resolver(_, name))
      require(idx >= 0, s"Column $name not found in the file reader output")
      idx
    }
  }

  private val parquetFormat = new DeltaParquetFileFormat(protocol, metadata)

  /**
   * Filters passed to the Parquet reader. Like V1, only filters on data columns are passed:
   * partition columns are not read from the files, so Parquet would evaluate filters on them as
   * NULL. Partition filters are already applied through Delta log pruning and by Spark after the
   * scan.
   */
  // scalastyle:off caselocale
  private val dataFilters: Seq[Filter] = {
    val partitionNames = partitionSchema.fieldNames.map(_.toLowerCase(Locale.ROOT)).toSet
    pushedFilters.toSeq.filterNot(_.references.exists(r =>
      partitionNames.contains(r.toLowerCase(Locale.ROOT))))
  }
  // scalastyle:on caselocale

  private val readerBuilder = parquetFormat.buildReaderWithPartitionValues(
    sparkSession = spark,
    dataSchema = dataSchema,
    partitionSchema = partitionSchema,
    requiredSchema = readDataSchema,
    filters = dataFilters,
    options = Map(FileFormat.OPTION_RETURNING_BATCH -> "false"),
    hadoopConf = serializableHadoopConf.value
  )

  override def createReader(partition: InputPartition): PartitionReader[InternalRow] = {
    val deltaPartition = partition.asInstanceOf[DeltaKeyGroupedInputPartition]
    val outputExprs = outputOrdinals.toSeq.map { i =>
      val f = fileOutputSchema(i)
      BoundReference(i, f.dataType, f.nullable)
    }
    val projection = UnsafeProjection.create(outputExprs)
    new DeltaBatchPartitionReader(deltaPartition, readerBuilder, projection)
  }
}

/**
 * PartitionReader that iterates through all files assigned to an InputPartition
 * and ensures underlying Parquet iterators are properly closed.
 */
class DeltaBatchPartitionReader(
    partition: DeltaKeyGroupedInputPartition,
    readerBuilder: PartitionedFile => Iterator[InternalRow],
    projection: UnsafeProjection)
  extends PartitionReader[InternalRow] {

  private val fileIterator: Iterator[DeltaScanFileInfo] = partition.files.iterator
  private var currentFileReader: Option[Iterator[InternalRow]] = None

  private def closeCurrentFileReader(): Unit = {
    currentFileReader.foreach {
      case closeable: AutoCloseable => closeable.close()
      case _ =>
    }
    currentFileReader = None
  }

  private def advanceToNextFile(): Boolean = {
    while (currentFileReader.forall(!_.hasNext) && fileIterator.hasNext) {
      closeCurrentFileReader()
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
    projection(currentFileReader.get.next())
  }

  override def close(): Unit = {
    closeCurrentFileReader()
  }
}
