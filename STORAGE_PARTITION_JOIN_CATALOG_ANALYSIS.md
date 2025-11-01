# Storage-Partitioned Join: Custom Catalog Analysis

## Executive Summary

**Question**: Can we avoid using a custom catalog for SPJ implementation?

**Answer**: **No** - The custom catalog approach is necessary and represents the best design choice given Delta Lake's architecture and constraints.

**Key Finding**: `DeltaTableV2` intentionally uses the V1 read path (`V2TableWithV1Fallback`) while providing V2 writes. This is a deliberate design for production stability. SPJ requires V2 reads via the `SupportsRead` trait, making the wrapper pattern through `DeltaCatalogWithSPJ` the cleanest and safest approach.

---

## Current Implementation Overview

The SPJ implementation uses a **catalog wrapper pattern** across 3 components:

### 1. DeltaCatalogWithSPJ
- Extends `DeltaCatalog`
- Intercepts `loadTable()` calls
- Conditionally wraps tables when SPJ is enabled

### 2. DeltaTableV2WithV2Scans
- Wrapper class that adds `SupportsRead` trait
- Delegates all other operations to underlying `DeltaTableV2`
- Provides `newScanBuilder()` for V2 scan creation

### 3. DeltaTableV2 (unchanged)
- Core table implementation
- Uses V1 reads intentionally (`V2TableWithV1Fallback`)
- Uses V2 writes (`SupportsWrite`)
- Production-tested and stable

```scala
// DeltaCatalogWithSPJ.loadTable():
table match {
  case deltaTable: DeltaTableV2 if shouldEnableSPJ =>
    new DeltaTableV2WithV2Scans(deltaTable)  // Wrapper adds SupportsRead
  case other =>
    other  // No wrapping
}
```

---

## Analysis of Alternatives

### Option A: Add SupportsRead Directly to DeltaTableV2 ❌

**Approach**: Modify `DeltaTableV2` to implement `SupportsRead` and rename `createScanBuilder` to `newScanBuilder`.

```scala
class DeltaTableV2 extends Table
  with SupportsRead          // ← Add this
  with SupportsWrite
  with V2TableWithV1Fallback

  override def newScanBuilder(...): ScanBuilder = {
    createScanBuilder(options)  // Existing method, just renamed
  }
```

**Code Change**: Single line - rename existing method to match `SupportsRead` interface.

#### Pros
- ✅ No custom catalog needed
- ✅ Simpler architecture (like Iceberg's `SparkTable`)
- ✅ Minimal code change (1 line)
- ✅ Direct implementation, no indirection

#### Cons
- ❌ **BREAKS EXISTING BEHAVIOR** - All Delta queries would use V2 reads
- ❌ **High production risk** - V2 read path not battle-tested for all Delta features
- ❌ **No gradual rollout** - Can't disable if issues arise
- ❌ **May conflict with V1Fallback** - Unclear behavior when both V2 reads and V1 fallback are present
- ❌ **Affects millions of queries** - Every Delta read operation changes behavior

#### Risk Assessment
- **Compatibility**: Unknown - would need extensive testing across all Delta features
- **Performance**: Unknown - V2 reads may have different characteristics
- **Correctness**: Unknown - deletion vectors, column mapping, time travel, CDC all need validation

#### Verdict
**Not viable** - The risk to existing production systems is too high. Delta's V1 read path has years of production hardening that cannot be discarded lightly.

---

### Option B: Conditional SupportsRead via Scala Traits ❌

**Approach**: Conditionally mix in `SupportsRead` trait based on configuration.

```scala
// Conceptual - not actually possible
if (shouldEnableSPJ) {
  new DeltaTableV2(...) with SupportsRead {
    override def newScanBuilder(...) = ...
  }
} else {
  new DeltaTableV2(...)
}
```

#### Pros
- ✅ Would allow conditional V2 read support
- ✅ No catalog changes needed (in theory)

#### Cons
- ❌ **Not possible in Scala** - Cannot conditionally add traits at instantiation time
- ❌ **Would require builder pattern** - Complex factory/builder to construct different variants
- ❌ **Still needs catalog-level interception** - Catalog must choose which variant to create
- ❌ **Defeats the purpose** - If catalog intercepts anyway, might as well use wrapper

#### Scala Language Limitation
Traits must be declared at class definition time, not instance creation time. You cannot write:
```scala
val table = baseTable.asInstanceOf[baseTable.type with SupportsRead]  // Doesn't work
```

#### Verdict
**Not viable** - Scala language limitation makes this impossible without essentially recreating the catalog wrapper pattern.

---

### Option C: Analyzer Rule Instead of Catalog ⚠️

**Approach**: Implement a Spark analyzer rule that transforms logical plans from V1 to V2 scans.

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

**Registration**:
```scala
class DeltaSparkSessionExtension extends (SparkSessionExtensions => Unit) {
  def apply(extensions: SparkSessionExtensions): Unit = {
    extensions.injectResolutionRule(_ => InjectDeltaSPJScans)
  }
}
```

#### Pros
- ✅ No catalog configuration changes needed
- ✅ Can be enabled via session extension
- ✅ Surgical fix at query planning stage
- ✅ Opt-in via Spark configuration

#### Cons
- ❌ **Complex implementation** - Need to manually construct V2 scan plan nodes
- ❌ **Internal API dependency** - Depends on Spark's internal plan structures
- ❌ **Rule ordering issues** - May conflict with other analyzer rules
- ❌ **Fragile across Spark versions** - Internal APIs change between versions
- ❌ **Harder to debug** - Plan transformations are opaque to users
- ❌ **Higher maintenance burden** - Need to update for each Spark version
- ❌ **More code complexity** - ~500-800 lines vs ~200 lines for catalog

#### Implementation Complexity
Requires:
1. Pattern matching on LogicalRelation nodes
2. Extracting Delta-specific information
3. Constructing DataSourceV2Relation or DataSourceV2ScanRelation
4. Creating Scan instances manually
5. Handling all edge cases (time travel, partition filters, etc.)
6. Ensuring rule fires at correct phase

#### Timeline
- Catalog approach: 1 week (already complete)
- Analyzer rule approach: 3-4 weeks estimated

#### Verdict
**Possible but significantly more complex** - Would work but offers no clear advantages over catalog approach while adding substantial complexity.

---

### Option D: Keep Custom Catalog (Current Implementation) ✅

**Approach**: Use `DeltaCatalogWithSPJ` to wrap tables with `DeltaTableV2WithV2Scans` when SPJ is enabled.

```scala
class DeltaCatalogWithSPJ extends DeltaCatalog {
  override def loadTable(ident: Identifier): Table = {
    val table = super.loadTable(ident)
    table match {
      case deltaTable: DeltaTableV2 if shouldEnableSPJ =>
        new DeltaTableV2WithV2Scans(deltaTable)
      case other =>
        other
    }
  }
}
```

**Configuration**:
```bash
spark.sql.catalog.spark_catalog = org.apache.spark.sql.delta.catalog.DeltaCatalogWithSPJ
spark.databricks.delta.planning.preserveDataGrouping = true
spark.sql.sources.v2.bucketing.enabled = true
```

#### Pros
- ✅ **Preserves existing behavior** - DeltaTableV2 completely unchanged
- ✅ **Opt-in via configuration** - Safe gradual rollout capability
- ✅ **Clean isolation** - SPJ code separate from core Delta
- ✅ **Easy to disable** - Change one config setting
- ✅ **Follows Spark patterns** - Catalog is the designed extension point
- ✅ **Low complexity** - ~200 lines of straightforward code
- ✅ **Already implemented** - Working code, no additional effort
- ✅ **Testable in isolation** - Can test SPJ without affecting core
- ✅ **Feature flaggable** - Can enable per-session or cluster-wide
- ✅ **Backwards compatible** - Zero impact when not enabled

#### Cons
- ⚠️ Requires catalog configuration change (one-time setup)
- ⚠️ Slight indirection via wrapper (negligible performance impact)
- ⚠️ Two classes instead of one (small code size increase)

#### Design Quality
- **Separation of Concerns**: SPJ feature is orthogonal to core table functionality
- **Open/Closed Principle**: Extends functionality without modifying existing code
- **Single Responsibility**: Each class has one clear purpose
- **Interface Segregation**: Wrapper only adds what's needed (SupportsRead)

#### Verdict
**Best approach** - Clean, safe, maintainable, already working.

---

## Comparison with Apache Iceberg

### Why Iceberg Doesn't Need Custom Catalog

Iceberg's `SparkTable` is V2-native from inception:

```java
public class SparkTable
    implements Table,
               SupportsRead,
               SupportsWrite,
               SupportsDeleteV2,
               SupportsRowLevelOperations {

  @Override
  public ScanBuilder newScanBuilder(CaseInsensitiveStringMap options) {
    return new SparkScanBuilder(sparkSession(), icebergTable, ...);
  }
}
```

**Key Difference**: Iceberg always uses V2 reads. No V1 legacy to support.

### Delta's Different Constraints

Delta has a fundamentally different evolution path:

| Aspect | Iceberg | Delta |
|--------|---------|-------|
| **Read Path** | V2 from day 1 | V1 (production-hardened) |
| **Write Path** | V2 from day 1 | V2 (newer) |
| **Legacy Support** | None needed | Years of V1 queries |
| **Migration** | N/A | Gradual V1→V2 transition |
| **Risk Tolerance** | Can be V2-only | Must maintain V1 stability |

### Why Delta Can't Just Copy Iceberg

1. **Production Scale**: Millions of existing queries depend on V1 read behavior
2. **Feature Completeness**: V1 read path supports all Delta features (deletion vectors, column mapping, time travel, CDC)
3. **Performance Characteristics**: V1 path has known, optimized performance
4. **Risk Management**: Cannot risk production stability for a single feature
5. **Migration Strategy**: Need controlled rollout path, not big bang

---

## Decision Matrix

| Approach | Feasibility | Production Risk | Complexity | Timeline | Recommendation |
|----------|-------------|-----------------|------------|----------|----------------|
| **Direct SupportsRead** | High (1 line change) | **VERY HIGH** ⚠️ | Low | 1 day | ❌ **Don't Do** |
| **Conditional Traits** | **Not Possible** | N/A | N/A | N/A | ❌ **Not Viable** |
| **Analyzer Rule** | Medium | Medium | **High** | 3-4 weeks | ⚠️ Possible Alternative |
| **Custom Catalog** ✅ | High | **Low** ✅ | Low | 1 week | ✅ **Recommended** |

### Scoring Criteria

**Feasibility**: Can it be implemented with current Spark/Scala APIs?
**Production Risk**: Impact to existing production workloads?
**Complexity**: Implementation and maintenance burden?
**Timeline**: How long to implement and test?

---

## Recommendation: Keep Custom Catalog

### Why This Is Actually Good Design

The custom catalog approach is not a workaround - it's **the correct architectural choice** for Delta's constraints:

#### 1. Separation of Concerns
- **Core Delta functionality**: Unchanged, stable
- **SPJ optimization**: Isolated, feature-flagged
- **Clean boundaries**: Wrapper pattern keeps responsibilities clear

#### 2. Risk Management
- **Zero impact when disabled**: Existing systems unaffected
- **Controlled rollout**: Enable per-session, cluster, or globally
- **Easy rollback**: Single config change to disable

#### 3. Follows Spark Patterns
- **Catalog as extension point**: Exactly what Spark catalogs are designed for
- **Wrapper pattern**: Standard approach for adding capabilities
- **Configuration-driven**: Spark's standard feature flag mechanism

#### 4. Future-Proof
- **V2 migration path**: When Delta fully migrates to V2 reads, can deprecate catalog
- **Incremental adoption**: Can test V2 reads in controlled environments
- **Reversible decision**: Can change approach later if needed

#### 5. Proven Pattern
- **Similar to Spark patterns**: Hive catalog wrapping, V2 catalog extensions
- **Used in production**: Pattern used by other data sources
- **Well understood**: Standard Scala wrapper/decorator pattern

### Configuration is Not a Burden

The catalog configuration is **one-time setup**:

```scala
// In spark-defaults.conf or cluster config:
spark.sql.catalog.spark_catalog = org.apache.spark.sql.delta.catalog.DeltaCatalogWithSPJ
spark.databricks.delta.planning.preserveDataGrouping = true
spark.sql.sources.v2.bucketing.enabled = true
```

Or programmatically:
```scala
spark.conf.set("spark.sql.catalog.spark_catalog",
  "org.apache.spark.sql.delta.catalog.DeltaCatalogWithSPJ")
```

This is **standard Spark configuration**, not an unusual requirement.

---

## Future Enhancements

While the custom catalog is the right approach now, we could make it easier to use:

### Option 1: Auto-Configuration via Extension

```scala
object DeltaSparkSessionExtension extends (SparkSessionExtensions => Unit) {
  def apply(extensions: SparkSessionExtensions): Unit = {
    // Auto-configure catalog if SPJ flag is set
    val session = SparkSession.active
    if (session.conf.get("spark.databricks.delta.enableSPJ", "false") == "true") {
      session.conf.set("spark.sql.catalog.spark_catalog",
        "org.apache.spark.sql.delta.catalog.DeltaCatalogWithSPJ")
    }
  }
}
```

**Usage**: Single flag instead of three configs
```scala
spark.databricks.delta.enableSPJ = true  // That's it!
```

### Option 2: Default Catalog in Future Versions

Once V2 reads are stable, make `DeltaCatalogWithSPJ` the **default catalog**:
- No configuration needed
- SPJ works out of the box
- Controlled by feature flags

---

## Conclusion

### Final Answer

**The custom catalog cannot and should not be avoided.**

It represents:
- ✅ The safest approach for production systems
- ✅ The cleanest architectural design
- ✅ The most maintainable solution
- ✅ The standard Spark extension pattern
- ✅ The fastest implementation (already complete)

### Why the Question Matters

It's **good engineering** to question if complexity is necessary. In this case:
- The analysis confirms the approach is correct
- The alternatives are either infeasible or more complex
- The perceived "complexity" is actually proper design
- The pattern is standard, not a workaround

### Key Insight

Delta's V1/V2 migration is a **feature, not a bug**. The hybrid approach:
- Uses V1 reads (stable, tested, production-proven)
- Uses V2 writes (newer, optimized)
- Allows gradual migration via wrappers like `DeltaCatalogWithSPJ`

This is **deliberate, careful engineering** for a production system with millions of users.

---

## References

### Implementation Files
- `spark/src/main/scala/org/apache/spark/sql/delta/catalog/DeltaCatalogWithSPJ.scala`
- `spark/src/main/scala/org/apache/spark/sql/delta/sources/v2/DeltaTableV2WithV2Scans.scala`
- `spark/src/main/scala/org/apache/spark/sql/delta/catalog/DeltaTableV2.scala` (lines 67-77)

### Documentation
- `STORAGE_PARTITION_JOIN_PHASE3_SUMMARY.md` - Original analysis of options
- `STORAGE_PARTITION_JOIN_PHASE4_SUMMARY.md` - Custom catalog implementation
- `STORAGE_PARTITION_JOIN_FINAL_SUMMARY.md` - Complete implementation summary

### Related Commits
- Phase 3: `a349a1b89` - Documents integration challenges and options
- Phase 4: `bc61c3c58` - Implements custom catalog approach
- Phase 6: `765cdd3ce` - Integration tests

### External References
- [Apache Iceberg SparkTable](https://github.com/apache/iceberg/blob/main/spark/v3.5/spark/src/main/java/org/apache/iceberg/spark/source/SparkTable.java)
- [Spark DataSource V2 Documentation](https://spark.apache.org/docs/latest/sql-data-sources-v2.html)
