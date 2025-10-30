# Phase 2 Implementation Notes: Architectural Considerations

## Challenge: DataSource V1 vs V2 Architecture

### Current Delta Architecture
Delta Lake currently uses a **hybrid DataSource V1/V2 approach**:
- **Reads**: Primarily DataSource V1 via `LogicalRelation` + `TahoeFileIndex`
- **Writes**: DataSource V2 via `DeltaTableV2` + `SupportsWrite`
- **Table**: DataSource V2 via `DeltaTableV2` implements `Table`

### Storage-Partitioned Join Requirements
SPJ requires **DataSource V2 Scan API** with:
- Implementation of `org.apache.spark.sql.connector.read.Scan`
- Implementation of `org.apache.spark.sql.connector.read.SupportsReportPartitioning`
- Reporting `KeyGroupedPartitioning` to Spark's optimizer
- Available only in Spark 3.3+ DataSource V2 API

### Iceberg vs Delta
| Aspect | Iceberg | Delta |
|--------|---------|-------|
| Read API | Full DataSource V2 | Mostly DataSource V1 |
| Scan Classes | `SparkScan`, `SparkBatchQueryScan` | N/A (uses `TahoeFileIndex`) |
| Partitioning | Reported via `SupportsReportPartitioning` | Not reported |
| Integration | Direct in scan building | Would require new path |

## Implementation Options

### Option 1: Full V2 Migration (Not Pursued)
**Scope**: Migrate all Delta reads to DataSource V2
- **Pros**: Clean architecture, full SPJ support
- **Cons**: Massive change, affects all Delta operations, high risk
- **Effort**: Weeks/months of work

### Option 2: Parallel V2 Path (Chosen Approach)
**Scope**: Create V2 scan path activated by configuration
- **Pros**: Opt-in, doesn't affect existing users, can be tested independently
- **Cons**: Maintains two code paths, complex integration
- **Effort**: Moderate (days/weeks)

### Option 3: V1 Workaround (Not Possible)
**Scope**: Try to report partitioning through V1 API
- **Pros**: Minimal changes
- **Cons**: **V1 API doesn't support partition reporting**, SPJ is V2-only feature
- **Effort**: N/A (not feasible)

## Phase 2 Implementation Strategy

### Core Classes to Create

#### 1. `DeltaPartitioningAwareScan` (Trait)
```scala
trait DeltaPartitioningAwareScan extends Scan with SupportsReportPartitioning {
  protected def partitionColumns: Seq[String]
  protected def preserveDataGrouping: Boolean

  override def outputPartitioning(): Partitioning = {
    if (preserveDataGrouping && partitionColumns.nonEmpty) {
      new KeyGroupedPartitioning(...)
    } else {
      new UnknownPartitioning(0)
    }
  }
}
```

**Purpose**: Base trait for scans that report partitioning

**Challenge**: Requires full Scan implementation

#### 2. `DeltaBatchQueryScan` (Class)
```scala
class DeltaBatchQueryScan(...) extends DeltaPartitioningAwareScan {
  override def toBatch: Batch = ...
  override def readSchema(): StructType = ...
}
```

**Purpose**: Concrete scan for SELECT queries

**Challenge**: Needs to integrate with Delta's snapshot/file listing

#### 3. Integration Point
Where to create these scans?
- Option A: Modify `DeltaTableV2` to implement `SupportsRead`
- Option B: Create new `DeltaScanBuilder` invoked conditionally
- Option C: Create custom Spark rule to replace `LogicalRelation`

### Current Implementation Status

**Phase 2 Deliverables**:
1. ✅ Core infrastructure (Phase 1)
2. 🚧 `DeltaPartitioningAwareScan` trait (in progress)
3. ⏳ `DeltaBatchQueryScan` stub
4. ⏳ Architecture documentation
5. ⏳ Basic tests demonstrating the concept

**Note**: Full integration with Delta's query planning will require additional work beyond this prototype.

## Next Steps

### Immediate (Phase 2)
1. Create `DeltaPartitioningAwareScan` trait with proper Spark 3.3+ APIs
2. Create `DeltaBatchQueryScan` stub showing the pattern
3. Document integration points
4. Create unit tests for the classes

### Future Work (Phase 3+)
1. Implement `SupportsRead` in `DeltaTableV2`
2. Create `DeltaScanBuilder` for scan construction
3. Integrate with Delta's snapshot/file listing
4. Handle runtime filtering
5. Support row-level operations (DELETE/UPDATE/MERGE)
6. Performance testing and optimization

## Key Takeaways

1. **SPJ requires DataSource V2**: Cannot be implemented with V1 API
2. **Delta uses V1 for reads**: Requires architectural changes
3. **Iceberg had easier path**: Already using V2 throughout
4. **Opt-in approach is best**: Minimizes impact on existing users
5. **Prototype demonstrates concept**: Full integration needs more work

## Testing Strategy

Since full integration is complex, testing will focus on:
1. **Unit tests**: Verify partitioning logic in isolation
2. **Component tests**: Test scan classes independently
3. **Integration tests**: Show end-to-end concept (may require mocks)
4. **Documentation**: Clear explanation of limitations and future work

## References

- Iceberg SPJ PR: apache/iceberg#6989
- Iceberg Commit: 31c801104303678a790e33586aeb388833fd90d6
- Spark DataSource V2: https://spark.apache.org/docs/latest/sql-data-sources-v2.html
- `SupportsReportPartitioning`: Added in Spark 3.3
