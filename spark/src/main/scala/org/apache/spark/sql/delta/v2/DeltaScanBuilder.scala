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
import java.util.Locale

import org.apache.hadoop.fs.Path

import org.apache.spark.paths.SparkPath
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Cast, GenericInternalRow, Literal}
import org.apache.spark.sql.connector.expressions.{Expression => V2Expression, Expressions}
import org.apache.spark.sql.connector.read._
import org.apache.spark.sql.connector.read.partitioning.{KeyGroupedPartitioning, Partitioning, UnknownPartitioning}
import org.apache.spark.sql.delta._
import org.apache.spark.sql.delta.actions.{AddFile, Metadata, Protocol}
import org.apache.spark.sql.delta.catalog.DeltaTableV2
import org.apache.spark.sql.delta.metering.DeltaLogging
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.spark.sql.execution.datasources.{FileFormat, PartitionedFile}
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.types.StructType
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
 * For partitioned Delta tables where all partition columns are preserved in the readSchema
 * and SPJ is enabled via `spark.sql.sources.v2.bucketing.enabled = true` and
 * `spark.databricks.delta.storagePartitionedJoin.enabled = true`, this scan groups AddFiles
 * by their physical partition values into [[DeltaKeyGroupedInputPartition]]s that implement
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
  private lazy val plannedPartitions: Array[InputPartition] = {
    val attrMap = org.apache.spark.sql.catalyst.types.DataTypeUtils.toAttributes(tableSchema)
      .map(a => a.name -> a).toMap
    val catalystFilters = pushedFilters.flatMap { f =>
      scala.util.Try(
        org.apache.spark.sql.delta.sources.DeltaSourceUtils.translateFilters(Array(f))
      ).toOption
    }.map { expr =>
      expr.transform {
        case u: org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute =>
          attrMap.getOrElse(u.name, u)
      }
    }.filter(_.resolved)

    val addFiles: Seq[AddFile] = snapshot.filesForScan(catalystFilters).files

    if (isSPJEligible) {
      val projectedPhysicalCols = projectedPartitionFields.map(DeltaColumnMapping.getPhysicalName)
      val grouped = addFiles.groupBy { f =>
        projectedPhysicalCols.map(col => col -> f.partitionValues.getOrElse(col, null)).toMap
      }.toSeq
      planPartitions(grouped)
    } else {
      planPartitions(addFiles.map(f => (f.partitionValues, Seq(f))))
    }
  }

  private def extractPartitionRow(
      partValuesMap: Map[String, String],
      schemaFields: Seq[org.apache.spark.sql.types.StructField]): GenericInternalRow = {
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

  private val parquetFormat = new DeltaParquetFileFormat(protocol, metadata)

  private val readerBuilder = parquetFormat.buildReaderWithPartitionValues(
    sparkSession = spark,
    dataSchema = dataSchema,
    partitionSchema = partitionSchema,
    requiredSchema = readSchema,
    filters = pushedFilters.toSeq,
    options = Map(FileFormat.OPTION_RETURNING_BATCH -> "false"),
    hadoopConf = serializableHadoopConf.value
  )

  override def createReader(partition: InputPartition): PartitionReader[InternalRow] = {
    val deltaPartition = partition.asInstanceOf[DeltaKeyGroupedInputPartition]
    new DeltaBatchPartitionReader(deltaPartition, readerBuilder)
  }
}

/**
 * PartitionReader that iterates through all files assigned to an InputPartition
 * and ensures underlying Parquet iterators are properly closed.
 */
class DeltaBatchPartitionReader(
    partition: DeltaKeyGroupedInputPartition,
    readerBuilder: PartitionedFile => Iterator[InternalRow])
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
    currentFileReader.get.next()
  }

  override def close(): Unit = {
    closeCurrentFileReader()
  }
}
