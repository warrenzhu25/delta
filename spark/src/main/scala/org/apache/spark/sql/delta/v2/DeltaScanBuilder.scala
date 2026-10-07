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
import org.apache.spark.sql.catalyst.expressions.{Add, BoundReference, Cast, Coalesce, CreateNamedStruct, Expression, GenericInternalRow, JoinedRow, Literal, UnsafeProjection}
import org.apache.spark.sql.catalyst.types.DataTypeUtils
import org.apache.spark.sql.connector.expressions.{Expression => V2Expression, Expressions}
import org.apache.spark.sql.connector.read._
import org.apache.spark.sql.connector.read.partitioning.{KeyGroupedPartitioning, Partitioning, UnknownPartitioning}
import org.apache.spark.sql.execution.datasources.{FileFormat, PartitionedFile}
import org.apache.spark.sql.execution.datasources.parquet.ParquetFileFormat
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.types.{DataType, LongType, StructField, StructType}
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

  /**
   * Whether deleted rows must be filtered. Like V1 (`PreprocessTableWithDVs`), this is only the
   * case when Deletion Vectors are readable and at least one selected file has a DV.
   */
  private lazy val hasDeletionVectors: Boolean =
    DeletionVectorUtils.deletionVectorsReadable(snapshot) &&
      selectedFiles.exists(_.deletionVector != null)

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
          partitionValues = fullFilePartitionRow,
          constantMetadata = TahoeFileIndex.constantMetadataForFile(f, rowIndexFilters = None)
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
      serializableHadoopConf = hadoopConf,
      deletionVectorTablePath =
        if (hasDeletionVectors) Some(deltaTable.deltaLog.dataPath.toString) else None,
      useMetadataRowIndex = spark.sessionState.conf.getConf(
        DeltaSQLConf.DELETION_VECTORS_USE_METADATA_ROW_INDEX)
    )
  }
}

/**
 * File metadata needed to construct PartitionedFile for each scan split.
 *
 * @param constantMetadata Per-file constant values passed to the file reader through
 *                         `PartitionedFile.otherConstantMetadataColumnValues` (row tracking
 *                         base values and the serialized Deletion Vector descriptor, if any).
 */
case class DeltaScanFileInfo(
    path: String,
    size: Long,
    modificationTime: Long,
    partitionValues: InternalRow,
    constantMetadata: Map[String, Any] = Map.empty) extends Serializable

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
    useMetadataRowIndex: Boolean = true)
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
    options = Map(FileFormat.OPTION_RETURNING_BATCH -> "false"),
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
      val partitionedFile = PartitionedFile(
        partitionValues = fileInfo.partitionValues,
        filePath = SparkPath.fromPathString(fileInfo.path),
        start = 0,
        length = fileInfo.size,
        modificationTime = fileInfo.modificationTime,
        fileSize = fileInfo.size,
        otherConstantMetadataColumnValues = fileInfo.constantMetadata
      )
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
      }
    }
    found
  }

  override def get(): InternalRow = currentRow

  override def close(): Unit = {
    closeCurrentFileReader()
  }
}
