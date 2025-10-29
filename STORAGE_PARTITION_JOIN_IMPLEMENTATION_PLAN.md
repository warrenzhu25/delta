# Storage-Partitioned Join (SPJ) Implementation Plan for Delta Lake

## Executive Summary

This document outlines the implementation plan for adding **Storage-Partitioned Join (SPJ)** support to Delta Lake, based on Apache Iceberg commit `31c801104303678a790e33586aeb388833fd90d6`. SPJ is a query optimization that eliminates unnecessary shuffle operations when joining tables on their partition columns, resulting in significant performance improvements.

**Key Benefits:**
- **Performance**: Eliminates expensive shuffle operations in partition-aware joins
- **Scalability**: Reduces network traffic and memory pressure in large-scale joins
- **Compatibility**: Leverages Spark 3.3+ DataSource V2 APIs
- **Correctness**: Maintains query correctness while improving performance

**Target Release**: Delta Lake 3.x (Spark 3.3+)

---

## Table of Contents

1. [Background and Motivation](#background-and-motivation)
2. [Architecture Analysis](#architecture-analysis)
3. [Implementation Phases](#implementation-phases)
4. [Detailed Code Changes](#detailed-code-changes)
5. [Testing Strategy](#testing-strategy)
6. [Configuration and Rollout](#configuration-and-rollout)
7. [Performance Validation](#performance-validation)
8. [Risk Assessment](#risk-assessment)
9. [Success Criteria](#success-criteria)
10. [References](#references)

---

## Background and Motivation

### What is Storage-Partitioned Join?

Storage-Partitioned Join (SPJ) is a query optimization technique that leverages physical data partitioning to eliminate shuffle operations during joins. When two tables are partitioned on the same keys and joined on those partition columns, Spark can avoid shuffling data because matching records are guaranteed to be co-located in corresponding partitions.

### How It Works

**Without SPJ (Traditional Join):**
```
Table1 (partitioned by date, country)
  ↓ [SHUFFLE - expensive!]
Join on date, country
  ↓ [SHUFFLE - expensive!]
Table2 (partitioned by date, country)
  ↓
Result
```

**With SPJ (Optimized Join):**
```
Table1 (partitioned by date, country)
  ↓ [NO SHUFFLE - files already grouped!]
Join on date, country (partition columns)
  ↓ [NO SHUFFLE - local join within partitions!]
Table2 (partitioned by date, country)
  ↓
Result
```

### Why Delta Needs SPJ

1. **Performance**: Delta users frequently join large partitioned tables
2. **Competitive Feature**: Iceberg has this optimization since 2023
3. **Natural Fit**: Delta's partition pruning already groups files by partition
4. **Low Risk**: Transparent optimization with no query syntax changes

### Iceberg Commit Analysis

**Commit**: `31c801104303678a790e33586aeb388833fd90d6`
**Author**: Iceberg community
**Target**: Spark 3.3+
**Key Changes**: 14 files (4 new classes, 10 modified)

**Core Innovation**: Implementing `SupportsReportPartitioning` to inform Spark's query optimizer about physical data grouping.

---

## Architecture Analysis

### Iceberg Architecture (Source)

```
┌─────────────────────────────────────────┐
│   SparkPartitioningAwareScan            │  ← Abstract base class
│   - implements SupportsReportPartitioning│
│   - outputPartitioning() → KeyGrouped   │
│   - groupingKeyType() → StructType      │
└─────────────────┬───────────────────────┘
                  │ extends
┌─────────────────▼───────────────────────┐
│   SparkBatchQueryScan                   │  ← Concrete implementation
│   - extends SparkPartitioningAwareScan  │
│   - implements SupportsRuntimeFiltering │
│   - taskGroups() → grouped by partition │
└─────────────────────────────────────────┘
```

**Key Classes:**
- `SparkPartitioningAwareScan`: Abstract base with partitioning logic
- `SparkBatchQueryScan`: Concrete implementation for SELECT queries
- `SparkCopyOnWriteScan`: SPJ for UPDATE/DELETE/MERGE
- `Partitioning.groupingKeyType()`: Computes common partition fields

### Delta Architecture (Current)

```
┌─────────────────────────────────────────┐
│   DeltaLog                              │
│   - getSnapshot() → Snapshot            │
└─────────────────┬───────────────────────┘
                  │
┌─────────────────▼───────────────────────┐
│   TahoeFileIndex                        │  ← Implements FileIndex (V1 API)
│   - listPartitionsAsAddFiles()          │
│   - matchingFiles(filters)              │
│   - grouping exists but not exposed!    │
└─────────────────────────────────────────┘
                  │
┌─────────────────▼───────────────────────┐
│   PrepareDeltaScan                      │  ← Logical plan rule
│   - pushes filters and projections      │
│   - NO partitioning reporting!          │
└─────────────────────────────────────────┘
```

**Key Differences:**
1. **API Version**: Delta uses hybrid V1/V2, Iceberg uses pure V2
2. **No SupportsReportPartitioning**: Delta doesn't report partitioning to Spark
3. **File Grouping**: Exists in `TahoeFileIndex` but not exposed to optimizer
4. **Simpler Partitioning**: Delta only supports identity partitions (no transforms)

### Delta Architecture (Target - After SPJ)

```
┌─────────────────────────────────────────┐
│   DeltaPartitioningAwareScan (NEW)      │  ← Trait implementing
│   - SupportsReportPartitioning          │     SupportsReportPartitioning
│   - outputPartitioning() → KeyGrouped   │
│   - groupingKeyType() → StructType      │
└─────────────────┬───────────────────────┘
                  │ extends
┌─────────────────▼───────────────────────┐
│   DeltaBatchQueryScan (NEW)             │  ← Concrete implementation
│   - extends DeltaPartitioningAwareScan  │
│   - implements SupportsRuntimeFiltering │
│   - uses Snapshot + AddFile grouping    │
└─────────────────────────────────────────┘
```

### Mapping Iceberg to Delta

| **Iceberg Component** | **Delta Equivalent** | **Status** |
|----------------------|---------------------|------------|
| `SparkPartitioningAwareScan` | `DeltaPartitioningAwareScan` (trait) | **NEW** |
| `SparkBatchQueryScan` | `DeltaBatchQueryScan` (class) | **NEW** |
| `SparkCopyOnWriteScan` | `DeltaMergeScan` extensions | **MODIFY** |
| `Partitioning.groupingKeyType()` | `DeltaPartitionUtils.groupingKeyType()` | **NEW** |
| `SparkSQLProperties.PRESERVE_DATA_GROUPING` | `DeltaSQLConf.PRESERVE_DATA_GROUPING` | **NEW** |
| `SparkReadConf.preserveDataGrouping()` | Read from `DeltaSQLConf` | **NEW** |
| `PartitionSpec` (with transforms) | `Metadata.partitionColumns` (identity only) | **EXISTING** |
| `TestStoragePartitionedJoins` | `DeltaStoragePartitionedJoinsSuite` | **NEW** |

---

## Implementation Phases

### Phase 1: Core Infrastructure (Week 1)

**Goal**: Establish configuration, utilities, and base traits

**Deliverables**:
1. Configuration property in `DeltaSQLConf`
2. Partition utility methods
3. `DeltaPartitioningAwareScan` trait
4. Basic unit tests

**Estimated Effort**: 3-4 days

---

### Phase 2: Scan Implementation (Week 2)

**Goal**: Implement concrete scan classes with partitioning support

**Deliverables**:
1. `DeltaBatchQueryScan` class
2. Integration with Delta's snapshot/file listing
3. Scan builder modifications
4. Integration tests for SELECT joins

**Estimated Effort**: 4-5 days

---

### Phase 3: Advanced Features (Week 3)

**Goal**: Add runtime filtering and row-level operation support

**Deliverables**:
1. Runtime filtering implementation
2. SPJ for DELETE/UPDATE/MERGE operations
3. Tests for row-level operations
4. Edge case handling

**Estimated Effort**: 5-6 days

---

### Phase 4: Testing & Validation (Week 4)

**Goal**: Comprehensive testing and performance validation

**Deliverables**:
1. Comprehensive test suite
2. Performance benchmarks
3. Documentation updates
4. Code review and refinement

**Estimated Effort**: 3-4 days

---

## Detailed Code Changes

### Phase 1: Core Infrastructure

#### 1.1 Configuration Property

**File**: `spark/src/main/scala/org/apache/spark/sql/delta/DeltaSQLConf.scala`

**Change**: Add new configuration property

```scala
val PRESERVE_DATA_GROUPING =
  buildConf("planning.preserve-data-grouping")
    .doc(
      """
        |When true, Delta will report physical partitioning information to Spark's query optimizer
        |to enable storage-partitioned joins. This eliminates unnecessary shuffles when joining
        |tables on their partition columns, significantly improving performance for partition-aware
        |joins. Requires spark.sql.sources.v2.bucketing.enabled=true.
      """.stripMargin)
    .booleanConf
    .createWithDefault(false)
```

**Rationale**:
- Disabled by default for safe rollout
- Clear documentation about requirements
- Follows Delta's configuration naming convention

---

#### 1.2 Partition Utility

**File**: `spark/src/main/scala/org/apache/spark/sql/delta/util/DeltaPartitionUtils.scala` (NEW)

**Content**: Utility methods for computing grouping keys

```scala
package org.apache.spark.sql.delta.util

import org.apache.spark.sql.types.{StructField, StructType}

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
   * Extracts partition values from an AddFile as a Row for grouping.
   *
   * @param addFile The AddFile containing partition values
   * @param partitionSchema The schema of partition columns
   * @return An InternalRow representing the partition values
   */
  def partitionValuesToRow(
      addFile: org.apache.spark.sql.delta.actions.AddFile,
      partitionSchema: StructType): org.apache.spark.sql.catalyst.InternalRow = {
    // Implementation will convert AddFile.partitionValues map to InternalRow
    // This will be implemented in Phase 2 when we have the full context
    ???
  }
}
```

**Rationale**:
- Centralized utility for partition-related operations
- Simplified compared to Iceberg (no transform handling)
- Extensible for future enhancements

---

#### 1.3 DeltaPartitioningAwareScan Trait

**File**: `spark/src/main/scala/org/apache/spark/sql/delta/sources/DeltaPartitioningAwareScan.scala` (NEW for Spark 3.3+)

**Content**: Abstract trait implementing `SupportsReportPartitioning`

```scala
package org.apache.spark.sql.delta.sources

import org.apache.spark.sql.connector.read.{Scan, SupportsReportPartitioning}
import org.apache.spark.sql.connector.read.partitioning.{KeyGroupedPartitioning, Partitioning, UnknownPartitioning}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.delta.DeltaSQLConf
import org.apache.spark.sql.delta.util.DeltaPartitionUtils

/**
 * Trait for Delta scans that report partitioning information to Spark's query optimizer.
 *
 * This enables storage-partitioned joins (SPJ) where Spark can eliminate shuffles
 * when joining tables on their partition columns.
 *
 * Based on Iceberg's SparkPartitioningAwareScan implementation.
 */
trait DeltaPartitioningAwareScan extends Scan with SupportsReportPartitioning {

  /**
   * The full table schema
   */
  protected def schema: StructType

  /**
   * The partition column names for this table
   */
  protected def partitionColumns: Seq[String]

  /**
   * Whether to preserve data grouping (from configuration)
   */
  protected def preserveDataGrouping: Boolean

  /**
   * Compute the grouping key type (partition columns schema).
   * Cached to ensure consistency across multiple calls.
   */
  private lazy val _groupingKeyType: StructType = {
    if (preserveDataGrouping && partitionColumns.nonEmpty) {
      DeltaPartitionUtils.groupingKeyType(schema, partitionColumns)
    } else {
      StructType(Seq.empty)
    }
  }

  def groupingKeyType: StructType = _groupingKeyType

  /**
   * Reports partitioning to Spark's query optimizer.
   *
   * Returns KeyGroupedPartitioning if:
   * - preserve-data-grouping is enabled
   * - table has partition columns
   * - spark.sql.sources.v2.bucketing.enabled is true (checked by Spark)
   *
   * Otherwise returns UnknownPartitioning (no optimization).
   */
  override def outputPartitioning(): Partitioning = {
    if (_groupingKeyType.nonEmpty) {
      // Report key-grouped partitioning to Spark
      // This tells Spark that data is already grouped by partition columns
      new KeyGroupedPartitioning(
        toSparkTransforms(_groupingKeyType),
        numPartitions = numFileGroups
      )
    } else {
      // No partitioning information available
      new UnknownPartitioning(0)
    }
  }

  /**
   * Converts partition column types to Spark transforms.
   * For Delta (identity partitioning only), this is straightforward.
   */
  private def toSparkTransforms(keyType: StructType): Array[org.apache.spark.sql.connector.expressions.Transform] = {
    import org.apache.spark.sql.connector.expressions.Expressions
    keyType.fields.map { field =>
      Expressions.identity(field.name)
    }
  }

  /**
   * Number of file groups (unique partition value combinations).
   * Subclasses must implement this based on their file grouping logic.
   */
  protected def numFileGroups: Int

  /**
   * Read schema for this scan
   */
  override def readSchema(): StructType = schema
}
```

**Rationale**:
- Trait (not abstract class) for flexibility
- Lazy evaluation of grouping key for efficiency
- Clear separation of concerns (partition reporting vs. file scanning)
- Delta-specific simplifications (identity partitioning only)

---

#### 1.4 Unit Tests for Core Infrastructure

**File**: `spark/src/test/scala/org/apache/spark/sql/delta/DeltaPartitionUtilsSuite.scala` (NEW)

**Content**: Test partition utility methods

```scala
package org.apache.spark.sql.delta

import org.apache.spark.sql.delta.util.DeltaPartitionUtils
import org.apache.spark.sql.types.{IntegerType, StringType, StructField, StructType}
import org.scalatest.funsuite.AnyFunSuite

class DeltaPartitionUtilsSuite extends AnyFunSuite {

  test("groupingKeyType with single partition column") {
    val schema = StructType(Seq(
      StructField("id", IntegerType),
      StructField("date", StringType),
      StructField("value", IntegerType)
    ))
    val partitionColumns = Seq("date")

    val groupingKey = DeltaPartitionUtils.groupingKeyType(schema, partitionColumns)

    assert(groupingKey.fields.length == 1)
    assert(groupingKey.fields(0).name == "date")
    assert(groupingKey.fields(0).dataType == StringType)
  }

  test("groupingKeyType with multiple partition columns") {
    val schema = StructType(Seq(
      StructField("id", IntegerType),
      StructField("year", IntegerType),
      StructField("month", IntegerType),
      StructField("value", IntegerType)
    ))
    val partitionColumns = Seq("year", "month")

    val groupingKey = DeltaPartitionUtils.groupingKeyType(schema, partitionColumns)

    assert(groupingKey.fields.length == 2)
    assert(groupingKey.fields(0).name == "year")
    assert(groupingKey.fields(1).name == "month")
  }

  test("groupingKeyType with no partition columns") {
    val schema = StructType(Seq(
      StructField("id", IntegerType),
      StructField("value", IntegerType)
    ))
    val partitionColumns = Seq.empty

    val groupingKey = DeltaPartitionUtils.groupingKeyType(schema, partitionColumns)

    assert(groupingKey.fields.isEmpty)
  }

  test("isValidForStoragePartitionedJoin with partitions") {
    assert(DeltaPartitionUtils.isValidForStoragePartitionedJoin(Seq("date")))
    assert(DeltaPartitionUtils.isValidForStoragePartitionedJoin(Seq("year", "month")))
  }

  test("isValidForStoragePartitionedJoin without partitions") {
    assert(!DeltaPartitionUtils.isValidForStoragePartitionedJoin(Seq.empty))
  }
}
```

**Test Execution**: Tests must pass before committing Phase 1 code.

---

### Phase 2: Scan Implementation

#### 2.1 DeltaBatchQueryScan Class

**File**: `spark/src/main/scala/org/apache/spark/sql/delta/sources/DeltaBatchQueryScan.scala` (NEW for Spark 3.3+)

**Content**: Concrete scan implementation with partitioning support

```scala
package org.apache.spark.sql.delta.sources

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.connector.read.{Batch, InputPartition, PartitionReaderFactory, SupportsRuntimeFiltering}
import org.apache.spark.sql.connector.read.streaming.MicroBatchStream
import org.apache.spark.sql.delta.Snapshot
import org.apache.spark.sql.delta.actions.AddFile
import org.apache.spark.sql.delta.util.DeltaPartitionUtils
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.util.CaseInsensitiveStringMap

import scala.collection.JavaConverters._

/**
 * Scan implementation for Delta batch queries with storage-partitioned join support.
 *
 * This class groups AddFiles by partition values and reports partitioning information
 * to Spark's query optimizer, enabling shuffle elimination in partition-aware joins.
 */
class DeltaBatchQueryScan(
    snapshot: Snapshot,
    readSchema: StructType,
    filters: Seq[Filter],
    options: CaseInsensitiveStringMap,
    preserveDataGrouping: Boolean)
  extends DeltaPartitioningAwareScan
  with SupportsRuntimeFiltering {

  override protected def schema: StructType = readSchema

  override protected def partitionColumns: Seq[String] =
    snapshot.metadata.partitionColumns

  private var _pushedFilters: Array[Filter] = Array.empty

  /**
   * Groups AddFiles by partition values.
   * Each group represents files with the same partition values.
   */
  private lazy val fileGroups: Map[InternalRow, Seq[AddFile]] = {
    if (groupingKeyType.nonEmpty) {
      // Get files matching filters
      val files = snapshot.filesForScan(filters, keepNumRecords = true)

      // Group by partition values
      files.files.groupBy { addFile =>
        DeltaPartitionUtils.partitionValuesToRow(addFile, groupingKeyType)
      }
    } else {
      // No grouping - all files in single group
      val files = snapshot.filesForScan(filters, keepNumRecords = true)
      Map(InternalRow.empty -> files.files)
    }
  }

  override protected def numFileGroups: Int = fileGroups.size

  override def toBatch: Batch = {
    new DeltaBatch(fileGroups, readSchema, snapshot, options)
  }

  // Runtime filtering support
  override def filterAttributes(): Array[org.apache.spark.sql.connector.expressions.NamedReference] = {
    if (groupingKeyType.nonEmpty) {
      import org.apache.spark.sql.connector.expressions.FieldReference
      partitionColumns.map(FieldReference(_)).toArray
    } else {
      Array.empty
    }
  }

  override def filter(filters: Array[Filter]): Unit = {
    // Store pushed filters for potential re-planning
    _pushedFilters = filters
  }
}

/**
 * Batch implementation that creates input partitions from file groups.
 */
class DeltaBatch(
    fileGroups: Map[InternalRow, Seq[AddFile]],
    readSchema: StructType,
    snapshot: Snapshot,
    options: CaseInsensitiveStringMap)
  extends Batch {

  override def planInputPartitions(): Array[InputPartition] = {
    fileGroups.flatMap { case (partitionValues, files) =>
      // Create input partitions for this file group
      // For simplicity, one partition per file group (can be optimized later)
      Seq(new DeltaInputPartition(files, readSchema, snapshot, partitionValues))
    }.toArray
  }

  override def createReaderFactory(): PartitionReaderFactory = {
    new DeltaPartitionReaderFactory(snapshot, readSchema, options)
  }
}

/**
 * Input partition carrying file group information.
 */
class DeltaInputPartition(
    val files: Seq[AddFile],
    val readSchema: StructType,
    val snapshot: Snapshot,
    val partitionValues: InternalRow)
  extends InputPartition {
  // Serializable implementation
}

/**
 * Reader factory for Delta partitions.
 */
class DeltaPartitionReaderFactory(
    snapshot: Snapshot,
    readSchema: StructType,
    options: CaseInsensitiveStringMap)
  extends PartitionReaderFactory {

  override def createReader(partition: InputPartition): org.apache.spark.sql.connector.read.PartitionReader[InternalRow] = {
    val deltaPartition = partition.asInstanceOf[DeltaInputPartition]
    // Create reader for the files in this partition
    // (Implementation will use existing Delta file reading logic)
    ???
  }
}
```

**Note**: This is a simplified structure. Full implementation will integrate with Delta's existing file reading infrastructure.

---

#### 2.2 Integration with Scan Building

**File**: `spark/src/main/scala/org/apache/spark/sql/delta/sources/DeltaDataSource.scala`

**Change**: Modify to create `DeltaBatchQueryScan` when appropriate

```scala
// Add method to check if SPJ should be enabled
private def shouldPreserveDataGrouping(
    snapshot: Snapshot,
    options: CaseInsensitiveStringMap): Boolean = {
  val conf = snapshot.deltaLog.newDeltaHadoopConf()
  val preserveGrouping = DeltaSQLConf.PRESERVE_DATA_GROUPING.fromString(
    conf.get(DeltaSQLConf.PRESERVE_DATA_GROUPING.key,
      DeltaSQLConf.PRESERVE_DATA_GROUPING.defaultValueString))

  preserveGrouping &&
    snapshot.metadata.partitionColumns.nonEmpty &&
    SparkSession.active.conf.get("spark.sql.sources.v2.bucketing.enabled", "false").toBoolean
}

// Modify scan creation to use DeltaBatchQueryScan
override def newScanBuilder(options: CaseInsensitiveStringMap): ScanBuilder = {
  new DeltaScanBuilder(snapshot, options) {
    override def build(): Scan = {
      if (shouldPreserveDataGrouping(snapshot, options)) {
        // Use partition-aware scan
        new DeltaBatchQueryScan(snapshot, readSchema, filters, options, preserveDataGrouping = true)
      } else {
        // Use regular scan
        createRegularScan(snapshot, readSchema, filters, options)
      }
    }
  }
}
```

**Rationale**:
- Conditional creation based on configuration and requirements
- Maintains backward compatibility
- Clear separation between SPJ and non-SPJ paths

---

#### 2.3 Integration Tests

**File**: `spark/src/test/scala/org/apache/spark/sql/delta/DeltaStoragePartitionedJoinsSuite.scala` (NEW)

**Content**: Integration tests for SELECT joins

```scala
package org.apache.spark.sql.delta

import org.apache.spark.sql.{QueryTest, Row}
import org.apache.spark.sql.test.SharedSparkSession

class DeltaStoragePartitionedJoinsSuite extends QueryTest with SharedSparkSession {

  import testImplicits._

  override def beforeAll(): Unit = {
    super.beforeAll()
    // Enable SPJ feature
    spark.conf.set("spark.databricks.delta.planning.preserveDataGrouping", "true")
    spark.conf.set("spark.sql.sources.v2.bucketing.enabled", "true")
  }

  test("basic join on single partition column eliminates shuffle") {
    withTempDir { dir =>
      val path1 = s"${dir.getAbsolutePath}/table1"
      val path2 = s"${dir.getAbsolutePath}/table2"

      // Create partitioned tables
      spark.range(0, 100)
        .selectExpr("id", "id % 10 as part", "id * 2 as value1")
        .write
        .format("delta")
        .partitionBy("part")
        .save(path1)

      spark.range(0, 100)
        .selectExpr("id", "id % 10 as part", "id * 3 as value2")
        .write
        .format("delta")
        .partitionBy("part")
        .save(path2)

      val df1 = spark.read.format("delta").load(path1)
      val df2 = spark.read.format("delta").load(path2)

      // Join on partition column
      val joined = df1.join(df2, df1("part") === df2("part") && df1("id") === df2("id"))

      // Verify no shuffle in plan
      val plan = joined.queryExecution.executedPlan.toString()
      assert(!plan.contains("Exchange"), s"Plan should not contain shuffle: $plan")

      // Verify correctness
      val result = joined.collect()
      assert(result.length == 100)
    }
  }

  test("join on multiple partition columns eliminates shuffle") {
    withTempDir { dir =>
      val path1 = s"${dir.getAbsolutePath}/table1"
      val path2 = s"${dir.getAbsolutePath}/table2"

      // Create tables partitioned by (year, month)
      spark.range(0, 100)
        .selectExpr("id", "cast(id / 50 as int) as year", "cast((id % 50) / 10 as int) as month", "id * 2 as value1")
        .write
        .format("delta")
        .partitionBy("year", "month")
        .save(path1)

      spark.range(0, 100)
        .selectExpr("id", "cast(id / 50 as int) as year", "cast((id % 50) / 10 as int) as month", "id * 3 as value2")
        .write
        .format("delta")
        .partitionBy("year", "month")
        .save(path2)

      val df1 = spark.read.format("delta").load(path1)
      val df2 = spark.read.format("delta").load(path2)

      // Join on both partition columns
      val joined = df1.join(df2,
        df1("year") === df2("year") &&
        df1("month") === df2("month") &&
        df1("id") === df2("id"))

      // Verify no shuffle
      val plan = joined.queryExecution.executedPlan.toString()
      assert(!plan.contains("Exchange"), s"Plan should not contain shuffle: $plan")

      // Verify correctness
      val result = joined.collect()
      assert(result.length == 100)
    }
  }

  test("join without partition columns includes shuffle") {
    withTempDir { dir =>
      val path1 = s"${dir.getAbsolutePath}/table1"
      val path2 = s"${dir.getAbsolutePath}/table2"

      // Create partitioned tables
      spark.range(0, 100)
        .selectExpr("id", "id % 10 as part", "id * 2 as value1")
        .write
        .format("delta")
        .partitionBy("part")
        .save(path1)

      spark.range(0, 100)
        .selectExpr("id", "id % 10 as part", "id * 3 as value2")
        .write
        .format("delta")
        .partitionBy("part")
        .save(path2)

      val df1 = spark.read.format("delta").load(path1)
      val df2 = spark.read.format("delta").load(path2)

      // Join only on non-partition column (should shuffle)
      val joined = df1.join(df2, df1("id") === df2("id"))

      // Verify shuffle is present
      val plan = joined.queryExecution.executedPlan.toString()
      assert(plan.contains("Exchange"), s"Plan should contain shuffle when not joining on partition columns: $plan")
    }
  }

  test("SPJ disabled when config is false") {
    spark.conf.set("spark.databricks.delta.planning.preserveDataGrouping", "false")

    withTempDir { dir =>
      val path1 = s"${dir.getAbsolutePath}/table1"
      val path2 = s"${dir.getAbsolutePath}/table2"

      spark.range(0, 100)
        .selectExpr("id", "id % 10 as part", "id * 2 as value1")
        .write
        .format("delta")
        .partitionBy("part")
        .save(path1)

      spark.range(0, 100)
        .selectExpr("id", "id % 10 as part", "id * 3 as value2")
        .write
        .format("delta")
        .partitionBy("part")
        .save(path2)

      val df1 = spark.read.format("delta").load(path1)
      val df2 = spark.read.format("delta").load(path2)

      val joined = df1.join(df2, df1("part") === df2("part") && df1("id") === df2("id"))

      // Should have shuffle when SPJ is disabled
      val plan = joined.queryExecution.executedPlan.toString()
      assert(plan.contains("Exchange"), s"Plan should contain shuffle when SPJ is disabled: $plan")
    }

    // Reset config
    spark.conf.set("spark.databricks.delta.planning.preserveDataGrouping", "true")
  }
}
```

**Test Execution**: All tests must pass before committing Phase 2 code.

---

### Phase 3: Advanced Features

#### 3.1 Runtime Filtering Support

Runtime filtering is already partially implemented in `DeltaBatchQueryScan` (see `filterAttributes()` and `filter()` methods in Phase 2).

**Additional Work**:
1. Implement filter re-planning when runtime filters are pushed
2. Handle dynamic partition pruning scenarios
3. Test with complex join patterns

**File**: Extend `DeltaBatchQueryScan` implementation

```scala
override def filter(filters: Array[Filter]): Unit = {
  _pushedFilters = filters
  // Trigger re-planning of file groups with new filters
  // This is called by Spark during runtime filtering
}

private def replanFileGroups(additionalFilters: Array[Filter]): Map[InternalRow, Seq[AddFile]] = {
  val allFilters = filters ++ additionalFilters
  val files = snapshot.filesForScan(allFilters, keepNumRecords = true)

  if (groupingKeyType.nonEmpty) {
    files.files.groupBy { addFile =>
      DeltaPartitionUtils.partitionValuesToRow(addFile, groupingKeyType)
    }
  } else {
    Map(InternalRow.empty -> files.files)
  }
}
```

---

#### 3.2 Row-Level Operations Support

**Goal**: Enable SPJ for DELETE, UPDATE, and MERGE operations

**Files to Modify**:
- `spark/src/main/scala/org/apache/spark/sql/delta/commands/DeleteCommand.scala`
- `spark/src/main/scala/org/apache/spark/sql/delta/commands/UpdateCommand.scala`
- `spark/src/main/scala/org/apache/spark/sql/delta/commands/MergeIntoCommand.scala`

**Approach**:
1. Create `DeltaMergeScan` extending `DeltaPartitioningAwareScan`
2. Use partition-aware scanning for row-level operation target tables
3. Maintain correctness while eliminating shuffles

**Example** (simplified):

```scala
/**
 * Scan for Delta merge operations (UPDATE, DELETE, MERGE) with SPJ support.
 */
class DeltaMergeScan(
    snapshot: Snapshot,
    readSchema: StructType,
    filters: Seq[Filter],
    options: CaseInsensitiveStringMap,
    preserveDataGrouping: Boolean)
  extends DeltaPartitioningAwareScan {

  // Similar implementation to DeltaBatchQueryScan
  // but handles merge-specific requirements

  override protected def schema: StructType = readSchema
  override protected def partitionColumns: Seq[String] = snapshot.metadata.partitionColumns

  // ... implementation details ...
}
```

---

#### 3.3 Tests for Row-Level Operations

**File**: `spark/src/test/scala/org/apache/spark/sql/delta/DeltaStoragePartitionedJoinsRowLevelOpsSuite.scala` (NEW)

```scala
class DeltaStoragePartitionedJoinsRowLevelOpsSuite extends QueryTest with SharedSparkSession {

  test("DELETE with SPJ eliminates shuffle") {
    withTempDir { dir =>
      val targetPath = s"${dir.getAbsolutePath}/target"
      val sourcePath = s"${dir.getAbsolutePath}/source"

      // Create partitioned target table
      spark.range(0, 100)
        .selectExpr("id", "id % 10 as part", "id * 2 as value")
        .write
        .format("delta")
        .partitionBy("part")
        .save(targetPath)

      // Create partitioned source table
      spark.range(0, 50)
        .selectExpr("id", "id % 10 as part")
        .write
        .format("delta")
        .partitionBy("part")
        .save(sourcePath)

      val target = io.delta.tables.DeltaTable.forPath(spark, targetPath)
      val source = spark.read.format("delta").load(sourcePath)

      // DELETE with partition column join
      target.as("t")
        .delete(s"EXISTS (SELECT 1 FROM delta.`$sourcePath` s WHERE s.id = t.id AND s.part = t.part)")

      // Verify no shuffle in plan (would need to capture plan during execution)
      // Verify correctness
      val remaining = spark.read.format("delta").load(targetPath).count()
      assert(remaining == 50)
    }
  }

  test("UPDATE with SPJ eliminates shuffle") {
    // Similar test for UPDATE
  }

  test("MERGE with SPJ eliminates shuffle") {
    // Similar test for MERGE
  }
}
```

---

### Phase 4: Testing & Validation

#### 4.1 Comprehensive Test Suite

**Additional Test Cases**:

1. **Edge Cases**:
   - Empty partitions
   - Null partition values
   - Single-file partitions
   - Very large partitions

2. **Multiple Join Types**:
   - Inner join
   - Left outer join
   - Right outer join
   - Full outer join

3. **Complex Queries**:
   - Multi-way joins (3+ tables)
   - Joins with aggregations
   - Joins with filters

4. **Partition Variations**:
   - Single partition column
   - Multiple partition columns
   - Non-partitioned tables (verify no SPJ)

5. **Configuration Combinations**:
   - SPJ enabled/disabled
   - V2 bucketing enabled/disabled
   - Various filter combinations

**File**: Add tests to `DeltaStoragePartitionedJoinsSuite.scala`

---

#### 4.2 Performance Benchmarks

**Goal**: Measure performance improvement with SPJ

**Benchmark Setup**:
```scala
object DeltaSPJBenchmark extends Benchmark {

  def runBenchmark(): Unit = {
    val iterations = 5
    val numRows = 10000000 // 10M rows
    val numPartitions = 100

    // Create large partitioned tables
    createBenchmarkTables(numRows, numPartitions)

    // Benchmark without SPJ
    val withoutSPJ = measureJoinTime(spjEnabled = false, iterations)

    // Benchmark with SPJ
    val withSPJ = measureJoinTime(spjEnabled = true, iterations)

    println(s"Join time without SPJ: ${withoutSPJ}ms")
    println(s"Join time with SPJ: ${withSPJ}ms")
    println(s"Speedup: ${withoutSPJ / withSPJ}x")
  }

  // ... implementation ...
}
```

**Expected Results**:
- 2-5x speedup for partition-aware joins
- No shuffle operations in SPJ query plans
- Reduced memory usage
- Lower network traffic

---

## Configuration and Rollout

### Configuration Properties

#### Primary Configuration

```properties
# Enable storage-partitioned joins (default: false)
spark.databricks.delta.planning.preserveDataGrouping = false

# Required Spark configuration for SPJ to work
spark.sql.sources.v2.bucketing.enabled = true
```

#### SQL Configuration

```sql
-- Enable SPJ for current session
SET spark.databricks.delta.planning.preserveDataGrouping = true;

-- Enable V2 bucketing (required)
SET spark.sql.sources.v2.bucketing.enabled = true;
```

### Rollout Strategy

#### Phase 1: Experimental (Week 1-2)
- Feature complete but disabled by default
- Internal testing and validation
- Documentation for early adopters

#### Phase 2: Opt-In (Week 3-4)
- Announce feature to community
- Provide clear documentation
- Gather feedback from early users

#### Phase 3: Default Enabled (Week 5+)
- After validation period with no issues
- Consider enabling by default in future release
- Monitor for any issues

### Compatibility Considerations

**Spark Version**: Requires Spark 3.3+ (for `SupportsReportPartitioning` API)

**Delta Version**: Target Delta Lake 3.x

**Breaking Changes**: None (opt-in feature)

**Migration**: No migration needed (transparent optimization)

---

## Performance Validation

### Benchmark Scenarios

1. **Basic Join Performance**
   - 10M rows, 100 partitions
   - Measure join time with/without SPJ
   - Expected: 2-5x speedup

2. **Large-Scale Joins**
   - 100M+ rows, 1000+ partitions
   - Measure memory usage and execution time
   - Expected: Significant reduction in shuffle data

3. **Multi-Way Joins**
   - 3-5 tables, partition-aligned
   - Measure end-to-end query time
   - Expected: Multiplicative benefits

4. **TPC-H Queries**
   - Run relevant TPC-H queries with partitioned tables
   - Measure overall performance improvement
   - Expected: 10-30% improvement on applicable queries

### Success Metrics

| Metric | Target | Validation Method |
|--------|--------|-------------------|
| Shuffle Elimination | 100% for partition joins | Query plan inspection |
| Performance Improvement | 2-5x for large joins | Benchmark suite |
| Memory Reduction | 30-50% | Spark metrics |
| Correctness | 100% | Comprehensive tests |
| No Regressions | 0 failures | Full test suite |

---

## Risk Assessment

### Technical Risks

1. **API Compatibility**
   - Risk: Breaking changes in Spark DataSource V2 API
   - Mitigation: Target stable Spark 3.3+ API
   - Severity: Low

2. **Performance Regression**
   - Risk: SPJ overhead in non-beneficial cases
   - Mitigation: Disabled by default, only apply when beneficial
   - Severity: Low

3. **Correctness Issues**
   - Risk: Incorrect file grouping leading to wrong results
   - Mitigation: Comprehensive test suite, validation against non-SPJ results
   - Severity: High (but low probability)

4. **Integration Complexity**
   - Risk: Complex integration with existing Delta code
   - Mitigation: Phased implementation, thorough code review
   - Severity: Medium

### Organizational Risks

1. **Resource Availability**
   - Risk: Implementation takes longer than expected
   - Mitigation: Phased approach, clear milestones
   - Severity: Low

2. **Community Adoption**
   - Risk: Users don't enable or understand feature
   - Mitigation: Clear documentation, blog posts, examples
   - Severity: Low

### Mitigation Strategies

1. **Comprehensive Testing**: Unit, integration, and performance tests
2. **Gradual Rollout**: Disabled by default, opt-in period
3. **Monitoring**: Track usage and issues in production
4. **Documentation**: Clear docs on when to use SPJ
5. **Rollback Plan**: Feature flag allows easy disable if issues arise

---

## Success Criteria

### Functional Requirements

- [ ] SPJ eliminates shuffles for partition-aligned joins
- [ ] Works with single and multiple partition columns
- [ ] Supports SELECT, DELETE, UPDATE, MERGE operations
- [ ] Runtime filtering integration
- [ ] Configurable enable/disable
- [ ] No correctness issues

### Performance Requirements

- [ ] 2-5x speedup for large partition-aligned joins
- [ ] No overhead when SPJ not applicable
- [ ] Reduced memory usage (30-50%)
- [ ] Scales to 1000+ partitions

### Quality Requirements

- [ ] 100% test pass rate
- [ ] No scalastyle violations
- [ ] Code review approved
- [ ] Documentation complete
- [ ] Performance benchmarks show improvement

### Adoption Requirements

- [ ] Feature documented in Delta docs
- [ ] Blog post or announcement
- [ ] Example notebooks/queries
- [ ] Community feedback positive

---

## References

### Iceberg Implementation

- **Commit**: `31c801104303678a790e33586aeb388833fd90d6`
- **Files**: 14 files changed (4 new, 10 modified)
- **Key Classes**:
  - `SparkPartitioningAwareScan`
  - `SparkBatchQueryScan`
  - `Partitioning.groupingKeyType()`

### Spark APIs

- **DataSource V2**: Spark 3.0+ connector API
- **SupportsReportPartitioning**: Spark 3.3+ interface for partition reporting
- **KeyGroupedPartitioning**: Spark's representation of grouped data
- **SupportsRuntimeFiltering**: Dynamic partition pruning support

### Delta Architecture

- **TahoeFileIndex**: Current file listing and grouping
- **PrepareDeltaScan**: Logical plan optimization
- **Snapshot**: Point-in-time table view
- **AddFile**: File metadata with partition values

### Performance Studies

- **Iceberg Performance**: Reported 2-10x improvement in partition joins
- **Spark Adaptive Execution**: Complements SPJ for dynamic optimization

---

## Appendix A: File Changes Summary

| File | Type | Lines | Phase |
|------|------|-------|-------|
| `DeltaSQLConf.scala` | Modify | +10 | 1 |
| `DeltaPartitionUtils.scala` | New | ~150 | 1 |
| `DeltaPartitioningAwareScan.scala` | New | ~120 | 1 |
| `DeltaPartitionUtilsSuite.scala` | New | ~80 | 1 |
| `DeltaBatchQueryScan.scala` | New | ~250 | 2 |
| `DeltaDataSource.scala` | Modify | +30 | 2 |
| `DeltaStoragePartitionedJoinsSuite.scala` | New | ~400 | 2 |
| `DeltaMergeScan.scala` | New | ~200 | 3 |
| `DeleteCommand.scala` | Modify | +20 | 3 |
| `UpdateCommand.scala` | Modify | +20 | 3 |
| `MergeIntoCommand.scala` | Modify | +20 | 3 |
| `DeltaStoragePartitionedJoinsRowLevelOpsSuite.scala` | New | ~300 | 3 |
| **Total** | **8 new, 5 modified** | **~1,600** | **4 phases** |

---

## Appendix B: Commit Strategy

Each phase will have multiple focused commits:

### Phase 1 Commits
1. Add configuration property
2. Add partition utilities
3. Add DeltaPartitioningAwareScan trait
4. Add unit tests

### Phase 2 Commits
1. Add DeltaBatchQueryScan class
2. Integrate with scan building
3. Add integration tests

### Phase 3 Commits
1. Add runtime filtering
2. Add DeltaMergeScan for row-level ops
3. Modify DELETE/UPDATE/MERGE commands
4. Add row-level operation tests

### Phase 4 Commits
1. Add comprehensive test cases
2. Add performance benchmarks
3. Update documentation
4. Final refinements

**Commit Message Format**:
```
[SPJ] Brief description

Detailed description of changes and motivation.

Testing:
- List of tests added/modified
- All tests passing

Phase: X/4
```

---

## Appendix C: Testing Checklist

### Unit Tests
- [ ] DeltaPartitionUtils.groupingKeyType
- [ ] DeltaPartitionUtils.isValidForStoragePartitionedJoin
- [ ] DeltaPartitioningAwareScan.outputPartitioning
- [ ] Configuration property parsing

### Integration Tests
- [ ] Basic join with single partition column
- [ ] Join with multiple partition columns
- [ ] Join without partition columns (verify shuffle)
- [ ] SPJ disabled via config
- [ ] Empty partitions
- [ ] Null partition values
- [ ] Different join types (inner, left, right, full)

### Row-Level Operation Tests
- [ ] DELETE with SPJ
- [ ] UPDATE with SPJ
- [ ] MERGE with SPJ
- [ ] Row-level ops without SPJ

### Performance Tests
- [ ] Benchmark: 10M rows, 100 partitions
- [ ] Benchmark: 100M rows, 1000 partitions
- [ ] Multi-way join benchmark
- [ ] Memory usage measurement

### End-to-End Tests
- [ ] TPC-H query subset
- [ ] Real-world query patterns
- [ ] Edge cases and stress tests

---

## Document Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 1.0 | 2025-10-29 | Initial detailed implementation plan | Implementation Team |

---

**End of Document**

This implementation plan provides a comprehensive blueprint for adding storage-partitioned join support to Delta Lake. Each phase is designed to be implemented incrementally with clear milestones, testing requirements, and success criteria.
