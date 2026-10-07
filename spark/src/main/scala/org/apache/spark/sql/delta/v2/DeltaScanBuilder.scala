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
import org.apache.spark.sql.delta.commands.DeletionVectorUtils
import org.apache.spark.sql.delta.files.TahoeFileIndex
import org.apache.spark.sql.delta.logging.DeltaLogKeys
import org.apache.spark.sql.delta.metering.DeltaLogging
import org.apache.spark.sql.delta.sources.{DeltaSourceUtils, DeltaSQLConf}
import org.apache.hadoop.fs.Path

import org.apache.spark.internal.MDC
import org.apache.spark.paths.SparkPath
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
import org.apache.spark.sql.catalyst.expressions.{Add, And, BindReferences, BoundReference, Cast, Coalesce, CreateNamedStruct, Expression, GenericInternalRow, JoinedRow, Literal, Predicate, UnsafeProjection}
import org.apache.spark.sql.catalyst.types.DataTypeUtils
import org.apache.spark.sql.connector.expressions.{Expression => V2Expression, Expressions, FieldReference, NamedReference}
import org.apache.spark.sql.connector.expressions.filter.{Predicate => V2Predicate}
import org.apache.spark.sql.connector.metric.{CustomMetric, CustomSumMetric, CustomTaskMetric}
import org.apache.spark.sql.connector.read._
import org.apache.spark.sql.connector.read.partitioning.{KeyGroupedPartitioning, Partitioning, UnknownPartitioning}
import org.apache.spark.sql.execution.WholeStageCodegenExec
import org.apache.spark.sql.execution.datasources.{FileFormat, FilePartition, PartitionedFile}
import org.apache.spark.sql.execution.datasources.parquet.ParquetFileFormat
import org.apache.spark.sql.internal.connector.PredicateUtils
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.types.{DataType, LongType, StructField, StructType}
import org.apache.spark.sql.util.CaseInsensitiveStringMap
import org.apache.spark.sql.vectorized.ColumnarBatch
import org.apache.spark.util.{SerializableConfiguration, Utils}

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
  with SupportsRuntimeV2Filtering
  with DeltaLogging {

  override def description(): String = s"DeltaBatchScan[${deltaTable.name()}]"

  override def toBatch: Batch = this

  override def columnarSupportMode(): Scan.ColumnarSupportMode =
    if (columnarReads) Scan.ColumnarSupportMode.SUPPORTED
    else Scan.ColumnarSupportMode.UNSUPPORTED

  private val snapshot: Snapshot = deltaTable.initialSnapshot
  private val protocol: Protocol = snapshot.protocol
  private val metadata: Metadata = snapshot.metadata

  /**
   * Whether the scan returns columnar batches. Like the V1 `FileSourceScanExec`, batches are only
   * returned when whole-stage codegen can consume them and the Parquet reader can return every
   * read column in a batch (vectorized reader enabled, supported types). Scans that read Deletion
   * Vector files or the `_metadata` column are row-based: their extra values (deleted rows,
   * per-file metadata) are applied per row by [[DeltaBatchPartitionReader]].
   */
  private lazy val columnarReads: Boolean = {
    val conf = spark.sessionState.conf
    val readsMetadataColumn = readSchema.fieldNames.contains(FileFormat.METADATA_NAME) &&
      !tableSchema.fieldNames.contains(FileFormat.METADATA_NAME)
    conf.getConf(DeltaSQLConf.DELTA_STORAGE_PARTITIONED_JOIN_COLUMNAR_READS_ENABLED) &&
      !hasDeletionVectors &&
      !readsMetadataColumn &&
      conf.wholeStageEnabled &&
      !WholeStageCodegenExec.isTooManyFields(conf, readSchema) &&
      new DeltaParquetFileFormat(protocol, metadata).supportBatch(spark, readSchema)
  }

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

  /**
   * Whether deleted rows must be filtered. Like V1 (`PreprocessTableWithDVs`), this is only the
   * case when Deletion Vectors are readable and at least one selected file has a DV.
   */
  private lazy val hasDeletionVectors: Boolean =
    DeletionVectorUtils.deletionVectorsReadable(snapshot) &&
      selectedFiles.exists(_.deletionVector != null)

  /** Input partitions planned from all `selectedFiles`. The reported partitioning uses these. */
  private lazy val originalPartitions: Array[InputPartition] = planPartitionsFor(selectedFiles)

  /**
   * Input partitions left after runtime filtering (dynamic partition pruning), see [[filter]].
   * `None` until Spark filters the scan at runtime.
   */
  @volatile private var runtimeFilteredPartitions: Option[Array[InputPartition]] = None

  /**
   * Plans the input partitions for `files`:
   *  - When SPJ is eligible, files are grouped by projected partition key. Each key group is then
   *    split and packed like the V1 file scan (see [[packFiles]]), giving one or more input
   *    partitions that all have the group's key. Spark merges the input partitions of a key when
   *    it needs one partition per key (e.g. for a join), or keeps them apart, for more
   *    parallelism, when it doesn't or when `partiallyClusteredDistribution` is enabled.
   *  - Otherwise all files are split and packed together, and no partition key is reported.
   * With file splitting disabled, every key group (SPJ) or file (no SPJ) is one input partition,
   * and files are read whole.
   */
  private def planPartitionsFor(files: Seq[AddFile]): Array[InputPartition] = {
    val keyedGroups: Seq[(InternalRow, Seq[DeltaScanFileInfo])] = if (isSPJEligible) {
      val projectedPhysicalCols = projectedPartitionFields.map(DeltaColumnMapping.getPhysicalName)
      files.groupBy { f =>
        projectedPhysicalCols.map(col => col -> f.partitionValues.getOrElse(col, null)).toMap
      }.values.toSeq.flatMap { groupFiles =>
        val key = extractPartitionRow(groupFiles.head.partitionValues, projectedPartitionFields)
        if (fileSplittingEnabled) {
          packFiles(groupFiles).map(key -> _)
        } else {
          Seq(key -> groupFiles.map(wholeFile))
        }
      }
    } else {
      val noKey = new GenericInternalRow(0)
      if (fileSplittingEnabled) {
        packFiles(files).map(noKey -> _)
      } else {
        files.map(f => noKey -> Seq(wholeFile(f)))
      }
    }
    keyedGroups.zipWithIndex.map { case ((key, fileInfos), idx) =>
      DeltaKeyGroupedInputPartition(
        partitionId = idx,
        files = fileInfos.toArray,
        partitionKeyInternalRow = key
      ): InputPartition
    }.toArray
  }

  private def fileSplittingEnabled: Boolean = spark.sessionState.conf.getConf(
    DeltaSQLConf.DELTA_STORAGE_PARTITIONED_JOIN_FILE_SPLITTING_ENABLED)

  private def useMetadataRowIndex: Boolean =
    spark.sessionState.conf.getConf(DeltaSQLConf.DELETION_VECTORS_USE_METADATA_ROW_INDEX)

  /**
   * Whether files can be read in byte ranges. Same as `DeltaParquetFileFormat.isSplitable` for
   * the file format the reader factory builds: only DV reads that count row indexes themselves
   * (`useMetadataRowIndex = false`) must read whole files.
   */
  private lazy val isSplittable: Boolean = !(hasDeletionVectors && !useMetadataRowIndex)

  /**
   * Target split size, computed like V1 (`FileSourceScanExec`) from all selected files:
   * `spark.sql.files.maxPartitionBytes`, lowered so that small scans still use the default
   * parallelism, but not below `spark.sql.files.openCostInBytes`.
   */
  private lazy val maxSplitBytes: Long = {
    val openCostInBytes = spark.sessionState.conf.filesOpenCostInBytes
    FilePartition.maxSplitBytes(spark, selectedFiles.map(_.size + openCostInBytes).sum)
  }

  /** Splits `f` into `maxSplitBytes` byte ranges if files are splittable, else one whole range. */
  private def splitFile(f: AddFile): Seq[DeltaScanFileInfo] = {
    val ranges = if (isSplittable) {
      (0L until f.size by maxSplitBytes).map { start =>
        start -> math.min(maxSplitBytes, f.size - start)
      }
    } else {
      Seq(0L -> f.size)
    }
    val partitionValues = extractPartitionRow(f.partitionValues, metadata.partitionSchema)
    val constantMetadata = TahoeFileIndex.constantMetadataForFile(f, rowIndexFilters = None)
    ranges.map { case (start, length) =>
      toFileInfo(f, partitionValues, constantMetadata, start, length)
    }
  }

  /** The whole file `f` as one byte range. */
  private def wholeFile(f: AddFile): DeltaScanFileInfo = toFileInfo(
    f,
    partitionValues = extractPartitionRow(f.partitionValues, metadata.partitionSchema),
    constantMetadata = TahoeFileIndex.constantMetadataForFile(f, rowIndexFilters = None),
    start = 0L,
    length = f.size)

  /**
   * Splits and packs `files` like the V1 file scan: files are split into byte ranges, sorted by
   * size (largest first) and packed into groups of up to `maxSplitBytes` with Spark's own
   * `FilePartition.getFilePartitions` (next-fit decreasing, counting
   * `spark.sql.files.openCostInBytes` per file, and honoring `spark.sql.files.maxPartitionNum`).
   * Returns the byte ranges of each group.
   */
  private def packFiles(files: Seq[AddFile]): Seq[Seq[DeltaScanFileInfo]] = {
    val splits = files.flatMap(splitFile).sortBy(_.length)(Ordering[Long].reverse)
    val infoBySplit = new java.util.IdentityHashMap[PartitionedFile, DeltaScanFileInfo]()
    val partitionedFiles = splits.map { info =>
      val partitionedFile = info.toPartitionedFile
      infoBySplit.put(partitionedFile, info)
      partitionedFile
    }
    FilePartition.getFilePartitions(spark, partitionedFiles, maxSplitBytes).map { p =>
      p.files.toSeq.map(infoBySplit.get)
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

  override def supportedCustomMetrics(): Array[CustomMetric] = DeltaScanMetrics.supportedMetrics

  /**
   * Reports the files the scan reads, like the V1 file scan's `numFiles`, `filesSize` and
   * `numPartitions` metrics. Spark calls this after runtime filtering, so the values reflect the
   * files left by dynamic partition pruning. A file read in several byte ranges counts once.
   */
  override def reportDriverMetrics(): Array[CustomTaskMetric] = {
    val files = planInputPartitions().iterator
      .flatMap(_.asInstanceOf[DeltaKeyGroupedInputPartition].files.iterator)
      .map(f => f.path -> f)
      .toMap
      .values
    Array(
      DeltaScanMetrics.taskMetric(DeltaScanMetrics.NUM_FILES, files.size.toLong),
      DeltaScanMetrics.taskMetric(DeltaScanMetrics.FILES_SIZE, files.iterator.map(_.size).sum),
      DeltaScanMetrics.taskMetric(DeltaScanMetrics.NUM_PARTITIONS,
        files.iterator.map(_.partitionValues).toSet.size.toLong))
  }

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

  /** The byte range `[start, start + length)` of `f` to read. */
  private def toFileInfo(
      f: AddFile,
      partitionValues: InternalRow,
      constantMetadata: Map[String, Any],
      start: Long,
      length: Long): DeltaScanFileInfo =
    DeltaScanFileInfo(
      path = resolveFilePath(f.path),
      start = start,
      length = length,
      size = f.size,
      modificationTime = f.modificationTime,
      partitionValues = partitionValues,
      constantMetadata = constantMetadata)

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

  override def planInputPartitions(): Array[InputPartition] =
    runtimeFilteredPartitions.getOrElse(originalPartitions)

  override def outputPartitioning(): Partitioning = reportedPartitioning

  private lazy val reportedPartitioning: Partitioning = {
    if (isSPJEligible) {
      logInfo(log"Reporting KeyGroupedPartitioning with " +
        log"${MDC(DeltaLogKeys.NUM_PARTITIONS, originalPartitions.length)} partitions for " +
        log"table ${MDC(DeltaLogKeys.TABLE_NAME, deltaTable.name())}")
      new KeyGroupedPartitioning(groupingKeyTransforms, originalPartitions.length)
    } else {
      new UnknownPartitioning(originalPartitions.length)
    }
  }

  /**
   * Columns Spark may filter this scan by at runtime (dynamic partition pruning): the partition
   * columns in `readSchema`. Pruning by data columns would need file statistics and is not
   * supported.
   */
  override def filterAttributes(): Array[NamedReference] =
    projectedPartitionColumns.map(c => FieldReference.column(c): NamedReference).toArray

  /**
   * Prunes the planned input partitions with runtime predicates on partition columns (dynamic
   * partition pruning). Spark then calls [[planInputPartitions]] again. Like V1, the predicates
   * are evaluated on each file's partition values. Predicates that can't be translated, or that
   * reference other columns, are ignored: runtime filters only skip files, the join still
   * filters the rows.
   *
   * Files are removed from the input partitions they were planned in, and input partitions left
   * without files are dropped. So for every key, the number of input partitions can only shrink,
   * as Spark requires for a scan that reports [[KeyGroupedPartitioning]]; Spark adds empty
   * partitions for the missing ones. The reported partitioning and statistics are not changed.
   */
  override def filter(predicates: Array[V2Predicate]): Unit = {
    val partitionAttrs = DataTypeUtils.toAttributes(metadata.partitionSchema)
    val resolver = spark.sessionState.conf.resolver
    val partitionFilters: Seq[Expression] = predicates.toSeq
      .flatMap(PredicateUtils.toV1)
      .flatMap(f => Try(DeltaSourceUtils.translateFilters(Array(f))).toOption)
      .map(_.transform {
        case u: UnresolvedAttribute =>
          partitionAttrs.find(a => resolver(a.name, u.name)).getOrElse(u)
      })
      .filter(_.resolved)
    if (partitionFilters.nonEmpty) {
      val keep = Predicate.createInterpreted(
        BindReferences.bindReference(partitionFilters.reduce(And), partitionAttrs))
      val partitions = planInputPartitions().map(_.asInstanceOf[DeltaKeyGroupedInputPartition])
      // `partitionValues` holds every partition column of the file, typed (see planPartitionsFor).
      val remaining = partitions.flatMap { p =>
        val files = p.files.filter(f => keep.eval(f.partitionValues))
        if (files.isEmpty) None else Some(p.copy(files = files))
      }
      def numFiles(ps: Array[DeltaKeyGroupedInputPartition]): Long =
        ps.iterator.flatMap(_.files.iterator.map(_.path)).toSet.size.toLong
      logInfo(log"Runtime filtering kept " +
        log"${MDC(DeltaLogKeys.NUM_FILES, numFiles(remaining))} of " +
        log"${MDC(DeltaLogKeys.NUM_FILES2, numFiles(partitions))} files for table " +
        log"${MDC(DeltaLogKeys.TABLE_NAME, deltaTable.name())}")
      runtimeFilteredPartitions = Some(remaining.map(p => p: InputPartition))
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
      serializableHadoopConf = hadoopConf,
      deletionVectorTablePath =
        if (hasDeletionVectors) Some(deltaTable.deltaLog.dataPath.toString) else None,
      useMetadataRowIndex = useMetadataRowIndex,
      columnarReads = columnarReads
    )
  }
}

/**
 * File metadata needed to construct PartitionedFile for each scan split.
 *
 * @param start            Offset of the first byte of the file to read.
 * @param length           Number of bytes of the file to read, starting at `start`. A file is
 *                         read in several byte ranges when it is split (see
 *                         `DeltaBatchScan.packFiles`); the Parquet reader then reads the row
 *                         groups whose midpoint falls into the range.
 * @param size             Size of the whole file.
 * @param constantMetadata Per-file constant values passed to the file reader through
 *                         `PartitionedFile.otherConstantMetadataColumnValues` (row tracking
 *                         base values and the serialized Deletion Vector descriptor, if any).
 */
case class DeltaScanFileInfo(
    path: String,
    start: Long,
    length: Long,
    size: Long,
    modificationTime: Long,
    partitionValues: InternalRow,
    constantMetadata: Map[String, Any] = Map.empty) extends Serializable {

  def toPartitionedFile: PartitionedFile = PartitionedFile(
    partitionValues = partitionValues,
    filePath = SparkPath.fromPathString(path),
    start = start,
    length = length,
    modificationTime = modificationTime,
    fileSize = size,
    otherConstantMetadataColumnValues = constantMetadata)
}

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
 *
 * Deletion Vectors are handled like V1 (`PreprocessTableWithDVs`): when
 * `deletionVectorTablePath` is set, the file format is switched to its DV-aware variant and two
 * internal columns are additionally requested from the file reader: the Parquet row index
 * (only when `useMetadataRowIndex` is enabled) and the `is_row_deleted` flag computed by
 * [[DeltaParquetFileFormat]] from each file's DV. Deleted rows are skipped by
 * [[DeltaBatchPartitionReader]] and the internal columns are dropped by the output projection.
 *
 * The `_metadata` column (when present in `readSchema`, see `DeltaTableV2.metadataColumns`) is
 * built by the output projection from:
 *  - a per-file row of constant fields (`file_path`, `file_size`, `base_row_id`, ...), computed
 *    with the file format's `fileConstantMetadataExtractors`, exactly like V1;
 *  - the Parquet row index (`row_index`);
 *  - the materialized Row ID / row commit version columns, combined with the per-file base values
 *    the same way as the V1 `GenerateRowIDs` rule.
 *
 * @param deletionVectorTablePath Table data path used to resolve DV files, or None when no
 *                                selected file has a Deletion Vector.
 * @param useMetadataRowIndex     Whether the row index used for DV filtering comes from the
 *                                Parquet reader (`_metadata.row_index`) or from a row counter.
 * @param columnarReads           Whether partitions are read as columnar batches (see
 *                                `DeltaBatchScan.columnarReads`). Only set when there are no
 *                                Deletion Vectors and no `_metadata` column, so the file
 *                                reader's batches only need their columns reordered.
 */
class DeltaPartitionReaderFactory(
    spark: SparkSession,
    dataSchema: StructType,
    partitionSchema: StructType,
    readSchema: StructType,
    protocol: Protocol,
    metadata: Metadata,
    pushedFilters: Array[Filter],
    serializableHadoopConf: SerializableConfiguration,
    deletionVectorTablePath: Option[String] = None,
    useMetadataRowIndex: Boolean = true,
    columnarReads: Boolean = false)
  extends PartitionReaderFactory {

  import DeltaPartitionReaderFactory._

  /** The requested `_metadata` struct, if any. A table column named `_metadata` takes priority. */
  private val metadataStruct: Option[StructType] =
    if (dataSchema.fieldNames.contains(FileFormat.METADATA_NAME)) {
      None
    } else {
      readSchema.find(_.name == FileFormat.METADATA_NAME).map(_.dataType.asInstanceOf[StructType])
    }

  private val metadataFieldNames: Seq[String] = metadataStruct.toSeq.flatMap(_.fieldNames)

  // scalastyle:off caselocale
  private val readDataSchema: StructType = {
    val partitionNames = partitionSchema.fieldNames.map(_.toLowerCase(Locale.ROOT)).toSet
    StructType(readSchema.filterNot { f =>
      partitionNames.contains(f.name.toLowerCase(Locale.ROOT)) ||
        (metadataStruct.isDefined && f.name == FileFormat.METADATA_NAME)
    })
  }
  // scalastyle:on caselocale

  private val hasDeletionVectors: Boolean = deletionVectorTablePath.isDefined

  /**
   * Internal columns requested from the file reader to filter rows deleted by Deletion Vectors.
   * Mirrors the extra scan output added by `PreprocessTableWithDVs` in V1.
   */
  private val deletionVectorColumns: Seq[StructField] = if (hasDeletionVectors) {
    val rowIndexField = if (useMetadataRowIndex) Seq(RowIndexField) else Seq.empty
    rowIndexField :+ DeltaParquetFileFormat.IS_ROW_DELETED_STRUCT_FIELD
  } else {
    Seq.empty
  }

  /**
   * Extra columns requested from the file reader to build `_metadata`: the Parquet row index
   * (for `row_index` and `row_id`) unless already requested for DVs, and the materialized Row ID
   * and row commit version columns. Like V1, the materialized columns are requested under their
   * physical (materialized) name with the generated metadata field's Spark metadata, so that they
   * are recognized as internal columns under column mapping. They are null for rows that don't
   * have a materialized value.
   */
  private val metadataFileColumns: Seq[StructField] = {
    val needsRowIndex = metadataFieldNames.exists(n => n == ParquetFileFormat.ROW_INDEX ||
      n == RowId.ROW_ID)
    val rowIndex =
      if (needsRowIndex && !deletionVectorColumns.contains(RowIndexField)) Seq(RowIndexField)
      else Seq.empty
    rowIndex ++ materializedColumn(RowId.ROW_ID, MaterializedRowId) ++
      materializedColumn(RowCommitVersion.METADATA_STRUCT_FIELD_NAME, MaterializedRowCommitVersion)
  }

  private def materializedColumn(
      metadataFieldName: String,
      column: MaterializedRowTrackingColumn): Option[StructField] = {
    if (metadataFieldNames.contains(metadataFieldName)) {
      for {
        materializedName <- column.getMaterializedColumnName(protocol, metadata)
        field <- parquetFormat.metadataSchemaFields.find(_.name == metadataFieldName)
      } yield field.copy(name = materializedName, nullable = true)
    } else {
      None
    }
  }

  /** Schema requested from the file reader (without partition columns). */
  private val fileRequiredSchema: StructType =
    StructType(readDataSchema ++ deletionVectorColumns ++ metadataFileColumns)

  /** Schema of the rows produced by the file reader: `fileRequiredSchema ++ partitionSchema`. */
  private val fileOutputSchema: StructType = StructType(fileRequiredSchema ++ partitionSchema)

  /**
   * Per-file constant `_metadata` fields to compute: the requested ones, plus the base values
   * needed to derive `row_id` and `row_commit_version`.
   */
  private val constantMetadataFieldNames: Seq[String] = {
    val generated = Set(ParquetFileFormat.ROW_INDEX, RowId.ROW_ID,
      RowCommitVersion.METADATA_STRUCT_FIELD_NAME)
    val helpers =
      (if (metadataFieldNames.contains(RowId.ROW_ID)) Seq(RowId.BASE_ROW_ID) else Nil) ++
        (if (metadataFieldNames.contains(RowCommitVersion.METADATA_STRUCT_FIELD_NAME)) {
          Seq(DefaultRowCommitVersion.METADATA_STRUCT_FIELD_NAME)
        } else {
          Nil
        })
    (metadataFieldNames.filterNot(generated.contains) ++ helpers).distinct
  }

  /**
   * Data types of `constantMetadataFieldNames`. The helper fields that are not requested
   * (`base_row_id`, `default_row_commit_version`) are LONG.
   */
  private val constantMetadataFieldTypes: Seq[DataType] = constantMetadataFieldNames.map { name =>
    metadataStruct.flatMap(_.find(_.name == name)).map(_.dataType).getOrElse(LongType)
  }

  /**
   * Output expressions over `JoinedRow(fileRow, constantMetadataRow)`, producing `readSchema`.
   * Built on the driver (resolving names needs the session conf) and compiled per task.
   */
  private val outputExpressions: Seq[Expression] = {
    val resolver = spark.sessionState.conf.resolver
    def fileRef(name: String): Expression = {
      val idx = fileOutputSchema.fieldNames.indexWhere(resolver(_, name))
      require(idx >= 0, s"Column $name not found in the file reader output")
      BoundReference(idx, fileOutputSchema(idx).dataType, nullable = true)
    }
    def constantRef(name: String): Expression = {
      val idx = constantMetadataFieldNames.indexOf(name)
      BoundReference(fileOutputSchema.length + idx, constantMetadataFieldTypes(idx),
        nullable = true)
    }
    def materializedRef(column: MaterializedRowTrackingColumn): Option[Expression] =
      column.getMaterializedColumnName(protocol, metadata)
        .filter(n => metadataFileColumns.exists(_.name == n))
        .map(fileRef)

    def metadataFieldExpr(field: StructField): Expression = field.name match {
      case ParquetFileFormat.ROW_INDEX =>
        fileRef(ParquetFileFormat.ROW_INDEX_TEMPORARY_COLUMN_NAME)
      case RowId.ROW_ID =>
        // Same as GenerateRowIDs: coalesce(materialized row id, base_row_id + row_index).
        val generated = Add(constantRef(RowId.BASE_ROW_ID),
          fileRef(ParquetFileFormat.ROW_INDEX_TEMPORARY_COLUMN_NAME))
        materializedRef(MaterializedRowId).map(m => Coalesce(Seq(m, generated)))
          .getOrElse(generated)
      case RowCommitVersion.METADATA_STRUCT_FIELD_NAME =>
        // Same as GenerateRowIDs: coalesce(materialized version, default_row_commit_version).
        val default = constantRef(DefaultRowCommitVersion.METADATA_STRUCT_FIELD_NAME)
        materializedRef(MaterializedRowCommitVersion).map(m => Coalesce(Seq(m, default)))
          .getOrElse(default)
      case name =>
        constantRef(name)
    }

    readSchema.map { f =>
      if (metadataStruct.isDefined && f.name == FileFormat.METADATA_NAME) {
        CreateNamedStruct(metadataStruct.get.flatMap { sub =>
          Seq(Literal(sub.name), metadataFieldExpr(sub))
        })
      } else {
        fileRef(f.name)
      }
    }
  }

  /** Ordinal of the `is_row_deleted` column in `fileOutputSchema`, or -1 without DVs. */
  private val isRowDeletedOrdinal: Int = if (hasDeletionVectors) {
    fileOutputSchema.fieldNames.indexOf(DeltaParquetFileFormat.IS_ROW_DELETED_COLUMN_NAME)
  } else {
    -1
  }

  private lazy val parquetFormat: DeltaParquetFileFormat = {
    val format = new DeltaParquetFileFormat(protocol, metadata)
    deletionVectorTablePath match {
      case Some(tablePath) =>
        format.copyWithDVInfo(tablePath, optimizationsEnabled = useMetadataRowIndex)
      case None => format
    }
  }

  /**
   * Filters passed to the Parquet reader. Like V1, only filters on data columns are passed:
   * partition columns are not read from the files, so Parquet would evaluate filters on them as
   * NULL. Partition filters are already applied through Delta log pruning and by Spark after the
   * scan. Filters on `_metadata` fields are not passed either; Spark applies them after the scan.
   */
  // scalastyle:off caselocale
  private val dataFilters: Seq[Filter] = {
    val partitionNames = partitionSchema.fieldNames.map(_.toLowerCase(Locale.ROOT)).toSet
    def isMetadataRef(r: String): Boolean = metadataStruct.isDefined &&
      (r == FileFormat.METADATA_NAME || r.startsWith(FileFormat.METADATA_NAME + "."))
    pushedFilters.toSeq.filterNot(_.references.exists(r =>
      partitionNames.contains(r.toLowerCase(Locale.ROOT)) || isMetadataRef(r)))
  }
  // scalastyle:on caselocale

  private val readerBuilder = parquetFormat.buildReaderWithPartitionValues(
    sparkSession = spark,
    dataSchema =
      if (hasDeletionVectors) dataSchema.add(DeltaParquetFileFormat.IS_ROW_DELETED_STRUCT_FIELD)
      else dataSchema,
    partitionSchema = partitionSchema,
    requiredSchema = fileRequiredSchema,
    filters = dataFilters,
    options = Map(FileFormat.OPTION_RETURNING_BATCH -> columnarReads.toString),
    hadoopConf = serializableHadoopConf.value
  )

  /** Extractors for the constant `_metadata` fields, the same ones V1 uses. */
  private val constantMetadataExtractors: Map[String, PartitionedFile => Any] =
    parquetFormat.fileConstantMetadataExtractors

  override def createReader(partition: InputPartition): PartitionReader[InternalRow] = {
    val deltaPartition = partition.asInstanceOf[DeltaKeyGroupedInputPartition]
    val projection = UnsafeProjection.create(outputExpressions)
    val constantMetadata = if (constantMetadataFieldNames.nonEmpty) {
      Some(ConstantMetadata(
        constantMetadataFieldNames, constantMetadataFieldTypes, constantMetadataExtractors))
    } else {
      None
    }
    new DeltaBatchPartitionReader(
      deltaPartition, readerBuilder, projection, isRowDeletedOrdinal, constantMetadata)
  }

  /**
   * For columnar reads, the ordinal in the file reader's batches (`fileOutputSchema`: data
   * columns, then partition columns) of each `readSchema` column.
   */
  private val batchColumnOrdinals: Array[Int] = if (columnarReads) {
    val resolver = spark.sessionState.conf.resolver
    readSchema.map { f =>
      val idx = fileOutputSchema.fieldNames.indexWhere(resolver(_, f.name))
      require(idx >= 0, s"Column ${f.name} not found in the file reader output")
      idx
    }.toArray
  } else {
    Array.empty
  }

  override def supportColumnarReads(partition: InputPartition): Boolean = columnarReads

  override def createColumnarReader(partition: InputPartition): PartitionReader[ColumnarBatch] = {
    require(columnarReads, "Columnar reads are not enabled for this scan")
    new DeltaColumnarPartitionReader(
      partition.asInstanceOf[DeltaKeyGroupedInputPartition], readerBuilder, batchColumnOrdinals)
  }
}

object DeltaPartitionReaderFactory {
  /**
   * The Parquet row index column, filled by Spark's Parquet reader, which looks the column up by
   * name. It must be nullable: the Parquet reader rejects non-nullable requested columns that are
   * missing in the file.
   */
  private val RowIndexField: StructField =
    StructField(ParquetFileFormat.ROW_INDEX_TEMPORARY_COLUMN_NAME, LongType, nullable = true)
}

/**
 * Computes the per-file row of constant `_metadata` field values.
 *
 * @param fieldNames     Names of the constant `_metadata` fields, in row order.
 * @param fieldDataTypes Data types of the constant `_metadata` fields, in row order.
 * @param extractors     The file format's `fileConstantMetadataExtractors`.
 */
case class ConstantMetadata(
    fieldNames: Seq[String],
    fieldDataTypes: Seq[DataType],
    extractors: Map[String, PartitionedFile => Any]) {
  def rowFor(file: PartitionedFile): InternalRow =
    FileFormat.updateMetadataInternalRow(
      new GenericInternalRow(fieldNames.length), fieldNames, file, extractors, fieldDataTypes)
}

/**
 * PartitionReader that iterates through all files assigned to an InputPartition
 * and ensures underlying Parquet iterators are properly closed.
 *
 * @param isRowDeletedOrdinal Ordinal of the `is_row_deleted` column in the file reader rows, or
 *                            -1 when there are no Deletion Vectors. Rows whose value is not
 *                            [[RowIndexFilter.KEEP_ROW_VALUE]] are skipped.
 * @param constantMetadata    Computes the per-file constant `_metadata` values, if `_metadata`
 *                            needs any. The projection reads them after the file reader columns.
 */
class DeltaBatchPartitionReader(
    partition: DeltaKeyGroupedInputPartition,
    readerBuilder: PartitionedFile => Iterator[InternalRow],
    projection: UnsafeProjection,
    isRowDeletedOrdinal: Int = -1,
    constantMetadata: Option[ConstantMetadata] = None)
  extends PartitionReader[InternalRow] {

  private val fileIterator: Iterator[DeltaScanFileInfo] = partition.files.iterator
  private var currentFileReader: Option[Iterator[InternalRow]] = None
  private var currentRow: InternalRow = _
  private var numDeletedRowsSkipped: Long = 0L
  private val joinedRow = new JoinedRow()
  private var constantMetadataRow: InternalRow = InternalRow.empty

  private def closeCurrentFileReader(): Unit = {
    currentFileReader.foreach {
      case closeable: AutoCloseable => closeable.close()
      case _ =>
    }
    currentFileReader = None
  }

  /** Opens the next files until one has remaining rows. Returns false when all are consumed. */
  private def advanceToNextFile(): Boolean = {
    while (currentFileReader.forall(!_.hasNext) && fileIterator.hasNext) {
      closeCurrentFileReader()
      val fileInfo = fileIterator.next()
      val partitionedFile = fileInfo.toPartitionedFile
      constantMetadataRow =
        constantMetadata.map(_.rowFor(partitionedFile)).getOrElse(InternalRow.empty)
      currentFileReader = Some(readerBuilder(partitionedFile))
    }
    currentFileReader.exists(_.hasNext)
  }

  private def isRowDeleted(row: InternalRow): Boolean =
    isRowDeletedOrdinal >= 0 && row.getByte(isRowDeletedOrdinal) != RowIndexFilter.KEEP_ROW_VALUE

  override def next(): Boolean = {
    var found = false
    while (!found && advanceToNextFile()) {
      val row = currentFileReader.get.next()
      if (!isRowDeleted(row)) {
        currentRow = projection(joinedRow(row, constantMetadataRow))
        found = true
      } else {
        numDeletedRowsSkipped += 1
      }
    }
    found
  }

  override def get(): InternalRow = currentRow

  override def currentMetricsValues(): Array[CustomTaskMetric] = Array(
    DeltaScanMetrics.taskMetric(DeltaScanMetrics.NUM_DELETED_ROWS_SKIPPED, numDeletedRowsSkipped))

  override def close(): Unit = {
    closeCurrentFileReader()
  }
}

/**
 * Columnar counterpart of [[DeltaBatchPartitionReader]]: reads the files of an InputPartition
 * one after another with a file reader that returns [[ColumnarBatch]]es (the Parquet vectorized
 * reader with `FileFormat.OPTION_RETURNING_BATCH`), and returns each batch with its columns in
 * `readSchema` order. The column vectors are not copied.
 *
 * @param columnOrdinals For each `readSchema` column, its ordinal in the file reader's batches.
 */
class DeltaColumnarPartitionReader(
    partition: DeltaKeyGroupedInputPartition,
    readerBuilder: PartitionedFile => Iterator[InternalRow],
    columnOrdinals: Array[Int])
  extends PartitionReader[ColumnarBatch] {

  private val fileIterator: Iterator[DeltaScanFileInfo] = partition.files.iterator
  private var currentFileReader: Option[Iterator[InternalRow]] = None
  private var currentBatch: ColumnarBatch = _

  private def closeCurrentFileReader(): Unit = {
    currentFileReader.foreach {
      case closeable: AutoCloseable => closeable.close()
      case _ =>
    }
    currentFileReader = None
  }

  /** Opens the next files until one has remaining batches. Returns false when all are consumed. */
  private def advanceToNextFile(): Boolean = {
    while (currentFileReader.forall(!_.hasNext) && fileIterator.hasNext) {
      closeCurrentFileReader()
      currentFileReader = Some(readerBuilder(fileIterator.next().toPartitionedFile))
    }
    currentFileReader.exists(_.hasNext)
  }

  override def next(): Boolean = {
    if (advanceToNextFile()) {
      // The file reader returns batches typed as rows, like for the V1 file scan.
      val batch = currentFileReader.get.next().asInstanceOf[Any].asInstanceOf[ColumnarBatch]
      currentBatch = new ColumnarBatch(columnOrdinals.map(batch.column), batch.numRows())
      true
    } else {
      false
    }
  }

  override def get(): ColumnarBatch = currentBatch

  override def close(): Unit = {
    closeCurrentFileReader()
  }
}

/**
 * Custom metrics of [[DeltaBatchScan]], shown on the `BatchScan` node in the SQL UI. The driver
 * metrics have the same names and descriptions as the V1 file scan's. Spark instantiates the
 * metric classes by name to aggregate values, so they are top-level classes with no-arg
 * constructors.
 */
object DeltaScanMetrics {
  val NUM_FILES = "numFiles"
  val FILES_SIZE = "filesSize"
  val NUM_PARTITIONS = "numPartitions"
  val NUM_DELETED_ROWS_SKIPPED = "numDeletedRowsSkipped"

  def supportedMetrics: Array[CustomMetric] = Array(
    new DeltaScanNumFilesMetric,
    new DeltaScanFilesSizeMetric,
    new DeltaScanNumPartitionsMetric,
    new DeltaScanNumDeletedRowsSkippedMetric)

  def taskMetric(metricName: String, metricValue: Long): CustomTaskMetric = new CustomTaskMetric {
    override def name(): String = metricName
    override def value(): Long = metricValue
  }
}

class DeltaScanNumFilesMetric extends CustomSumMetric {
  override def name(): String = DeltaScanMetrics.NUM_FILES
  override def description(): String = "number of files read"
}

class DeltaScanFilesSizeMetric extends CustomMetric {
  override def name(): String = DeltaScanMetrics.FILES_SIZE
  override def description(): String = "size of files read"
  override def aggregateTaskMetrics(taskMetrics: Array[Long]): String =
    Utils.bytesToString(taskMetrics.sum)
}

class DeltaScanNumPartitionsMetric extends CustomSumMetric {
  override def name(): String = DeltaScanMetrics.NUM_PARTITIONS
  override def description(): String = "number of partitions read"
}

class DeltaScanNumDeletedRowsSkippedMetric extends CustomSumMetric {
  override def name(): String = DeltaScanMetrics.NUM_DELETED_ROWS_SKIPPED
  override def description(): String = "number of rows skipped by deletion vectors"
}
