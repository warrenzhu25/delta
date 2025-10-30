# Phase 3 Summary: SPJ Integration Challenges and Path Forward

## Overview

Phase 3 attempted to complete the integration of storage-partitioned join (SPJ) support into Delta Lake by making `DeltaTableV2` provide V2 scans. We successfully created all necessary classes but discovered a critical API compatibility issue.

## What Was Accomplished ✅

### 1. Complete Scan Infrastructure
- **DeltaPartitioningAwareScan** trait (Phase 2)
  - Implements `SupportsReportPartitioning`
  - Reports `KeyGroupedPartitioning` to Spark
  - Handles partition column transforms

- **DeltaBatchQueryScan** class (Phase 2)
  - Concrete scan implementation
  - File grouping by partition values
  - Stub reader factory

- **DeltaScanBuilder** class (Phase 3) ✅
  - Full implementation with filter pushdown
  - Column pruning support
  - Conditional SPJ enablement logic

- **DeltaTableV2.createScanBuilder()** method (Phase 3) ✅
  - Method to create scan builders
  - Integration point for future work

### 2. Code Quality
- ✅ All code compiles successfully
- ✅ Scalastyle checks pass
- ✅ Clear documentation and comments
- ✅ Demonstrates correct SPJ pattern from Iceberg

## The Challenge Discovered 🔍

### API Compatibility Issue

**Expected (from Iceberg)**: `Table` with `SupportsRead` trait providing `newScanBuilder()`

**Reality (Delta's Spark 3.5)**: `Table` interface doesn't have `newScanBuilder()` method

### Why This Matters

Spark's query planner needs to:
1. Recognize that a table supports V2 reads
2. Call a method to get a `ScanBuilder`
3. Use the `ScanBuilder` to create a `Scan`
4. Use the `Scan` to get partition information

Without `newScanBuilder()` in the `Table` interface, there's no standard way for Spark to request a V2 scan from our table.

### Different Spark Versions

- **Spark 3.3** (Iceberg): Has `SupportsRead` trait
- **Spark 3.4**: API evolved
- **Spark 3.5** (Delta): Different API surface
- **Result**: Implementation must match the specific Spark version's API

## Paths Forward

### Option 1: Custom Catalog ⭐ Recommended for Production
**Implement a custom `TableCatalog` that understands SPJ**

```scala
class DeltaCatalogWithSPJ extends DeltaCatalog {
  override def loadTable(ident: Identifier): Table = {
    val table = super.loadTable(ident)
    // Wrap or enhance table with V2 scan capabilities
    new DeltaTableV2WithScans(table)
  }
}
```

**Pros**:
- Clean integration with Spark
- Follows Spark's design patterns
- Can be feature-flagged

**Cons**:
- Requires catalog configuration changes
- Affects all table resolution
- Moderate complexity

**Effort**: 2-3 weeks

### Option 2: Runtime Plan Transformation
**Create Spark analyzer rule to transform plans**

```scala
object InjectDeltaSPJScans extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case LogicalRelation(baseRelation, _, catalogTable, _)
        if isDeltaTable(catalogTable) && shouldUseSPJ =>
      // Transform to V2 scan-based plan
      createV2ScanPlan(catalogTable)
  }
}
```

**Pros**:
- No catalog changes needed
- Can be enabled/disabled easily
- Surgical fix

**Cons**:
- Complex implementation
- Fragile across Spark versions
- May conflict with other rules

**Effort**: 3-4 weeks

### Option 3: Wait for Spark API Evolution
**Wait for Spark version with unified scan API**

**Pros**:
- Clean, supported solution
- No workarounds needed

**Cons**:
- Timeline uncertain
- Blocks SPJ feature

**Effort**: N/A (waiting)

### Option 4: Conditional Compilation by Spark Version
**Build different implementations for different Spark versions**

```scala
// Spark 3.3
trait DeltaTableV2SPJ extends Table with SupportsRead

// Spark 3.4+
trait DeltaTableV2SPJ extends Table {
  override def newScanBuilder(...): ScanBuilder
}

// Spark 3.5
// Current approach with createScanBuilder
```

**Pros**:
- Can support all Spark versions
- Each version uses native APIs

**Cons**:
- Complex build system
- High maintenance burden
- Testing complexity

**Effort**: 4-5 weeks + ongoing maintenance

## Current State

### What Works
1. ✅ Core infrastructure (Phase 1)
   - Configuration
   - Partition utilities
   - All tests passing

2. ✅ Scan pattern demonstration (Phase 2-3)
   - All scan classes implemented
   - Correct SPJ pattern
   - Compiles successfully

### What's Needed for Full SPJ
1. ⏳ Integration approach decision (Catalog vs Runtime vs Wait)
2. ⏳ Implementation of chosen approach
3. ⏳ Actual file reading in reader factory
4. ⏳ Filter conversion (sources.Filter → Expression)
5. ⏳ Runtime filtering implementation
6. ⏳ Row-level operation support
7. ⏳ End-to-end integration tests
8. ⏳ Performance validation

## Recommendations

### Immediate (Next 1-2 weeks)
1. **Decision**: Choose integration approach
   - Recommended: Option 1 (Custom Catalog) for production
   - Alternative: Option 2 (Runtime Transform) if catalog changes are problematic

2. **Prototype**: Implement chosen approach for basic SELECT queries
   - Validate that Spark recognizes and uses V2 scans
   - Confirm SPJ optimization triggers

3. **Testing**: Create end-to-end test demonstrating SPJ
   - Verify shuffle elimination
   - Measure performance improvement

### Medium-term (2-4 weeks)
1. **Complete Implementation**: File reading, filtering, etc.
2. **Expand Coverage**: Row-level operations, runtime filtering
3. **Performance Validation**: Benchmarks and optimization

### Long-term (1-2 months)
1. **Production Readiness**: Stability testing, edge cases
2. **Documentation**: User guide, migration docs
3. **Community**: Share findings, gather feedback

## Value of Current Work

Even without full integration, this work provides:

1. **Correct Pattern**: Code demonstrates how SPJ should work
2. **Reference Implementation**: Useful for future Delta V2 migration
3. **Research**: Clear documentation of challenges and solutions
4. **Foundation**: Core utilities and infrastructure ready to use

## Key Learnings

1. **API Evolution**: DataSource V2 APIs vary significantly across Spark versions
2. **Iceberg Advantage**: Already using V2 throughout made SPJ straightforward
3. **Delta Reality**: V1-based reads require architectural changes for V2 features
4. **Integration Complexity**: Not just about code, but about API compatibility

## Files Created/Modified

```
Phase 1 (Working):
✅ DeltaSQLConf.scala - Configuration
✅ DeltaPartitionUtils.scala - Utilities
✅ DeltaPartitionUtilsSuite.scala - Tests (8/8 passing)

Phase 2 (Pattern Demo):
✅ DeltaPartitioningAwareScan.scala - Scan trait
✅ DeltaBatchQueryScan.scala - Scan implementation
✅ STORAGE_PARTITION_JOIN_PHASE2_NOTES.md - Architecture

Phase 3 (Integration Attempt):
✅ DeltaScanBuilder.scala - Scan builder
✅ DeltaTableV2.scala - Added createScanBuilder()
✅ STORAGE_PARTITION_JOIN_PHASE3_SUMMARY.md - This document
```

## Conclusion

We've successfully demonstrated the SPJ pattern and identified the integration challenge. The path forward is clear: choose an integration approach and implement it. The current code provides a solid foundation and will be valuable regardless of which integration approach is chosen.

**Recommendation**: Start with Option 1 (Custom Catalog) as a prototype to validate the approach, then expand from there.
