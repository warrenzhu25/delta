# Storage Partition Join Support for Delta Lake

## Goal
Implement storage-partitioned join optimization to eliminate shuffle when joining Delta tables on their partition columns, providing significant performance improvements for co-partitioned joins.

## What is Storage Partition Join?

When two tables are partitioned by the same columns and joined on those columns, Spark can skip the shuffle phase by reading co-located partitions together. This provides:
- **No shuffle overhead** for partition-key joins
- **Lower memory usage** (no hash tables for entire datasets)
- **Better parallelism** (partition-level processing)
- **Faster query execution** (especially for large tables)

Example:
```sql
-- Table A partitioned by (date, region)
-- Table B partitioned by (date, region)
-- Join on partition keys = no shuffle needed!
SELECT * FROM tableA JOIN tableB
ON tableA.date = tableB.date AND tableA.region = tableB.region
```

## Current State

### What We Have
- ✅ Delta tracks partition columns in `Metadata.partitionColumns` (actions.scala:1027)
- ✅ `DeltaTableV2.partitioning()` returns `Array[Transform]` (DeltaTableV2.scala:231-235)
- ✅ `TahoeFileIndex` provides partition-to-file mapping (TahoeFileIndex.scala:92-98)
- ✅ Partition filtering infrastructure exists in `PrepareDeltaScan` (PrepareDeltaScan.scala:54)

### What's Missing
- ❌ `DeltaTableV2` doesn't implement `SupportsReportPartitioning` (Spark V2 interface)
- ❌ No `KeyGroupedPartitioning` implementation to report distribution
- ❌ Spark can't detect that Delta tables are storage-partitioned
- ❌ Joins always shuffle, even when partition keys match

## Implementation Plan

### Phase 1: Research & Setup (Current Phase)
**Status**: ✅ Complete

Completed:
- [x] Investigated Spark V2 `SupportsReportPartitioning` interface
- [x] Analyzed Delta's partition metadata structure
- [x] Identified key files and integration points
- [x] Documented current partitioning infrastructure

---

### Phase 2: Implement SupportsReportPartitioning Interface
**Goal**: Make Delta tables report their partitioning to Spark

#### Step 2.1: Add Interface to DeltaTableV2
**File**: `spark/src/main/scala/org/apache/spark/sql/delta/catalog/DeltaTableV2.scala`

Modify class definition (line 64):
```scala
class DeltaTableV2(
    // ... existing parameters ...
) extends Table
  with SupportsWrite
  with SupportsRead              // Ensure this is present for V2 reads
  with SupportsReportPartitioning // ← ADD THIS
  with V2TableWithV1Fallback
  with DeltaLogging {
```

#### Step 2.2: Implement reportPartitioning() Method
Add method to `DeltaTableV2` class:

```scala
override def reportPartitioning(): Partitioning = {
  val partitionCols = initialSnapshot.metadata.partitionColumns

  if (partitionCols.isEmpty) {
    // Table is not partitioned
    return UnknownPartitioning(0)
  }

  // Convert partition column names to NamedReference
  val partitionKeys = partitionCols.map { colName =>
    FieldReference(colName)
  }.toArray

  // Report as KeyGroupedPartitioning
  // clustering = numPartitions (unknown, use 0 for dynamic)
  KeyGroupedPartitioning(partitionKeys, clustering = 0)
}
```

**Key decisions**:
- Use `KeyGroupedPartitioning` for partition-key-based distribution
- `clustering = 0` means number of partitions unknown (Spark will discover)
- Convert `metadata.partitionColumns` to `FieldReference` objects

#### Step 2.3: Write Unit Tests
**New test file**: `spark/src/test/scala/org/apache/spark/sql/delta/DeltaPartitionJoinSuite.scala`

Test cases:
1. `test("reportPartitioning returns KeyGroupedPartitioning for partitioned table")`
   - Create table partitioned by (date, region)
   - Verify `reportPartitioning()` returns correct partition keys

2. `test("reportPartitioning returns UnknownPartitioning for non-partitioned table")`
   - Create non-partitioned table
   - Verify `reportPartitioning()` returns `UnknownPartitioning`

3. `test("reportPartitioning handles column mapping")`
   - Create table with column mapping enabled
   - Verify partition keys use physical column names

**Run tests**:
```bash
./build/sbt "spark/testOnly *DeltaPartitionJoinSuite"
```

**Success Criteria**: All unit tests pass

---

### Phase 3: Implement Scan-Level Partitioning Support
**Goal**: Report partitioning information at scan level for better optimization

#### Step 3.1: Add SupportsReportPartitioning to DeltaScan
**Option A**: If we have a custom `DeltaScan` class implementing `Scan` interface:
- Add `SupportsReportPartitioning` interface
- Implement `reportPartitioning()` method similar to `DeltaTableV2`

**Option B**: If using V1 fallback currently:
- Skip this step for now (table-level reporting is sufficient)
- Return to this after Phase 2 validates the approach

#### Step 3.2: Handle Partition Filters
Ensure scan-level partitioning respects pushed-down filters:
- If partition filters are pushed down, report reduced partitioning
- Example: Filter `date = '2024-01-01'` → only 1 partition active

**File**: Integrate with `PrepareDeltaScan.scala` or scan builder

**Success Criteria**: Partition pruning doesn't break join optimization

---

### Phase 4: Integration Testing
**Goal**: Validate storage-partitioned joins work end-to-end

#### Step 4.1: Create Join Test Cases
**File**: `spark/src/test/scala/org/apache/spark/sql/delta/DeltaPartitionJoinSuite.scala`

Test scenarios:
1. **Basic storage-partitioned join**
   ```scala
   test("storage partitioned join eliminates shuffle") {
     withTable("tableA", "tableB") {
       // Create two tables partitioned by same columns
       sql("CREATE TABLE tableA (id INT, date DATE, value INT) USING delta PARTITIONED BY (date)")
       sql("CREATE TABLE tableB (id INT, date DATE, value INT) USING delta PARTITIONED BY (date)")

       // Insert data
       sql("INSERT INTO tableA VALUES (1, '2024-01-01', 100), (2, '2024-01-02', 200)")
       sql("INSERT INTO tableB VALUES (1, '2024-01-01', 10), (2, '2024-01-02', 20)")

       // Join on partition key
       val df = sql("SELECT * FROM tableA JOIN tableB ON tableA.date = tableB.date")

       // Verify no shuffle in physical plan
       val plan = df.queryExecution.executedPlan
       assert(!plan.toString.contains("Exchange"), "Should not shuffle for storage-partitioned join")

       // Verify results correct
       checkAnswer(df, Seq(...))
     }
   }
   ```

2. **Multi-column partition join**
   - Partition by (date, region)
   - Join on both columns
   - Verify no shuffle

3. **Partial partition join (should shuffle)**
   - Partition by (date, region)
   - Join only on date
   - Verify shuffle still occurs (correctness)

4. **Mixed partition schemes (should shuffle)**
   - TableA partitioned by date
   - TableB partitioned by region
   - Join on date
   - Verify shuffle occurs

5. **Non-partition column join (should shuffle)**
   - Both tables partitioned by date
   - Join on id (non-partition column)
   - Verify shuffle occurs

#### Step 4.2: Performance Benchmarking
Create benchmark to measure improvement:

**File**: `benchmarks/src/main/scala/org/apache/spark/sql/delta/benchmark/PartitionJoinBenchmark.scala`

Benchmark:
- Create two large tables (1M+ rows) partitioned by date (100 partitions)
- Measure join time WITH storage partition join
- Measure join time WITHOUT (baseline: force shuffle)
- Report speedup and shuffle reduction

**Expected results**:
- 2-5x faster for partition-key joins on large tables
- Zero shuffle bytes for partition-key joins
- Memory usage reduction

#### Step 4.3: Run Full Test Suite
Ensure no regressions:
```bash
# Run all Delta tests
./build/sbt "spark/test"

# Run specific join/optimization tests
./build/sbt "spark/testOnly *JoinSuite"
./build/sbt "spark/testOnly *OptimizationSuite"
```

**Success Criteria**: All tests pass, no regressions

---

### Phase 5: Handle Edge Cases
**Goal**: Ensure robustness for production use

#### Step 5.1: Column Mapping Support
**File**: `spark/src/main/scala/org/apache/spark/sql/delta/catalog/DeltaTableV2.scala`

Update `reportPartitioning()` to handle column mapping:
```scala
override def reportPartitioning(): Partitioning = {
  val metadata = initialSnapshot.metadata
  val partitionCols = if (DeltaColumnMapping.hasColumnMapping(metadata)) {
    // Use physical column names for column mapping
    metadata.physicalPartitionColumns
  } else {
    metadata.partitionColumns
  }

  if (partitionCols.isEmpty) {
    return UnknownPartitioning(0)
  }

  val partitionKeys = partitionCols.map { colName =>
    FieldReference(colName)
  }.toArray

  KeyGroupedPartitioning(partitionKeys, clustering = 0)
}
```

Test with column mapping modes:
- `id` mode
- `name` mode
- No column mapping

#### Step 5.2: Time Travel Support
Ensure partitioning works with time travel:
- `SELECT * FROM table@v1 JOIN table@v2 ON partition_col`
- Verify partition info uses correct snapshot
- Test with schema evolution (partition columns added/removed)

#### Step 5.3: Deletion Vectors
Verify storage partition join works with deletion vectors:
- Tables with deleted rows should still report partitioning
- Join correctness with DVs enabled
- No performance regression

#### Step 5.4: Multi-Part Checkpointing
Test with multi-part checkpointing enabled:
- Partition metadata should load correctly
- No performance issues with large checkpoints

**Test file**: Add edge case tests to `DeltaPartitionJoinSuite.scala`

**Success Criteria**: All edge cases handled correctly

---

### Phase 6: Documentation & Finalization
**Goal**: Document feature for users and contributors

#### Step 6.1: Code Documentation
Add ScalaDoc comments:
- `DeltaTableV2.reportPartitioning()` - explain what it does
- Explain `KeyGroupedPartitioning` vs `UnknownPartitioning` decision
- Document column mapping handling

#### Step 6.2: User Documentation
Create documentation explaining:
- What storage-partitioned joins are
- When Delta uses them automatically
- How to design tables for optimal joins
- Performance benefits and limitations

**Example user guide**:
```markdown
## Storage-Partitioned Joins

Delta Lake automatically optimizes joins when tables share the same partition columns.

### Example
CREATE TABLE sales USING delta PARTITIONED BY (date, region);
CREATE TABLE inventory USING delta PARTITIONED BY (date, region);

-- This join will NOT shuffle data (optimized!)
SELECT * FROM sales JOIN inventory
  ON sales.date = inventory.date
  AND sales.region = inventory.region;

### Requirements
- Both tables must be partitioned by the SAME columns
- Join must be on ALL partition columns
- Partition column types must match

### Performance
- Eliminates shuffle for partition-key joins
- Reduces memory usage and execution time
- Especially effective for large tables with many partitions
```

#### Step 6.3: Release Notes
Document in release notes:
- New feature: Storage-partitioned join optimization
- Performance improvements for co-partitioned joins
- Automatic optimization (no user changes required)

#### Step 6.4: Scalastyle Check
```bash
./build/sbt "spark/scalastyle"
```

Fix any violations:
- Line length (max 100 chars)
- Trailing whitespace
- Final newlines

**Success Criteria**: Documentation complete, code follows style guidelines

---

### Phase 7: Validation & Rollout
**Goal**: Validate in production-like scenarios

#### Step 7.1: Integration with Existing Tests
Verify feature works with existing Delta functionality:
- ✓ MERGE operations with partition joins
- ✓ UPDATE/DELETE with joins
- ✓ Streaming joins (structured streaming)
- ✓ Time travel queries
- ✓ OPTIMIZE/VACUUM operations

#### Step 7.2: Performance Validation
Run TPC-H or TPC-DS queries that benefit:
- TPC-H Q13 (joins on keys)
- TPC-DS queries with partition-aligned joins
- Measure query time reduction

#### Step 7.3: Backward Compatibility
Verify:
- Works with older Spark versions (3.5+)
- No breaking changes for users
- Graceful degradation if interface not supported

#### Step 7.4: Feature Flag (Optional)
Consider adding config to disable if needed:
```scala
spark.databricks.delta.storage.partitionedJoin.enabled = true
```

**Success Criteria**: Ready for production use

---

## Key Files to Modify

| File | Purpose | Changes |
|------|---------|---------|
| **DeltaTableV2.scala** | Main table interface | Add `SupportsReportPartitioning`, implement `reportPartitioning()` |
| **DeltaPartitionJoinSuite.scala** | Test suite (NEW) | Unit and integration tests for partition join |
| **PartitionJoinBenchmark.scala** | Benchmark (NEW) | Performance measurement |
| *Documentation files* | User guide | Document feature and usage |

## Success Metrics

### Functional
- ✅ `reportPartitioning()` returns correct partition keys
- ✅ Spark eliminates shuffle for matching partition-key joins
- ✅ Results are correct (no data loss/corruption)
- ✅ Works with column mapping, DVs, time travel
- ✅ All tests pass

### Performance
- ✅ 2-5x faster for large partition-key joins
- ✅ Zero shuffle bytes for optimized joins
- ✅ Memory usage reduction measurable
- ✅ No regression for non-partition joins

## Timeline Estimate
- **Phase 2**: 1 week (implement interface + unit tests)
- **Phase 3**: 3-4 days (scan-level support, optional)
- **Phase 4**: 1 week (integration tests + benchmarks)
- **Phase 5**: 3-4 days (edge cases)
- **Phase 6**: 2-3 days (documentation)
- **Phase 7**: 2-3 days (validation)

**Total**: ~3-4 weeks for complete implementation

## Risks & Mitigation

### Risk 1: V1 Fallback Interferes
Current: `DeltaTableV2` extends `V2TableWithV1Fallback`, reads go through V1 `LogicalRelation`

**Mitigation**:
- Table-level `reportPartitioning()` should still work even with V1 reads
- If blocked, implement minimal V2 scan support (Phase 3)
- Test explicitly with both V1 and V2 code paths

### Risk 2: Column Mapping Complexity
Physical vs logical column names might cause mismatches

**Mitigation**:
- Use `physicalPartitionColumns` when column mapping enabled
- Extensive testing with all column mapping modes
- Add debug logging for partition key resolution

### Risk 3: Performance Regression for Non-Partition Joins
Additional interface checks might add overhead

**Mitigation**:
- Benchmark non-partition joins before/after
- Ensure `UnknownPartitioning` returns quickly
- Profile hot paths

### Risk 4: Spark Version Compatibility
`SupportsReportPartitioning` might not exist in all Spark versions

**Mitigation**:
- Check Spark version compatibility (3.5+)
- Use shim pattern if needed for version compatibility
- Graceful degradation for older versions

## Next Steps

1. ✅ **Phase 1 Complete**: Research and planning done
2. **Start Phase 2**: Implement `SupportsReportPartitioning` in `DeltaTableV2`
3. Write unit tests to validate interface
4. Proceed through phases sequentially with test-first approach

## References

- **Spark V2 API Docs**: `org.apache.spark.sql.connector.catalog.SupportsReportPartitioning`
- **Key Delta Classes**: `DeltaTableV2`, `Metadata`, `TahoeFileIndex`
- **Similar Implementation**: Apache Iceberg's storage-partitioned join support
