# Delta Lake Data Source V2 API Migration Plan

## Overview
Migrate Delta Lake from hybrid V1/V2 implementation to native V2 API, enabling V2-only features like dynamic filter pushdown, advanced partition pruning, and better catalog integration.

## Current State Analysis

### What We Have Now (Hybrid V1/V2)
- **V2 Registration**: `DeltaDataSource` implements `TableProvider` (V2)
- **V2 Table**: `DeltaTableV2` extends `Table`, `SupportsWrite` (V2)
- **V1 Reads**: Falls back to `LogicalRelation` via `FallbackToV1Relations.scala`
- **V1 Writes**: V2 `WriteBuilder` → `V1Write` bridge → `WriteIntoDelta` command
- **V1 Streaming**: Uses `StreamSourceProvider`/`StreamSinkProvider` exclusively
- **V1 File Format**: `DeltaParquetFileFormat` extends `ParquetFileFormat`

### Key Files to Modify
- `spark/src/main/scala/org/apache/spark/sql/delta/sources/DeltaDataSource.scala`
- `spark/src/main/scala/org/apache/spark/sql/delta/catalog/DeltaTableV2.scala`
- `spark/src/main/scala/org/apache/spark/sql/delta/FallbackToV1Relations.scala`
- `spark/src/main/scala/org/apache/spark/sql/delta/sources/DeltaSource.scala`
- `spark/src/main/scala/org/apache/spark/sql/delta/DeltaParquetFileFormat.scala`

## Migration Phases

### Phase 1: Research & Foundation
**Goal**: Understand V2 API requirements and existing implementations

1. Study Spark's V2 API interfaces:
   - `Scan`, `ScanBuilder`, `Batch`, `PartitionReaderFactory`
   - `BatchWrite`, `DataWriterFactory`
   - `MicroBatchStream`, `StreamingWrite`

2. Analyze reference implementations:
   - Apache Iceberg's V2 implementation
   - Apache Hudi's V2 implementation
   - Spark's built-in V2 sources (CSV, JSON, Parquet)

3. Document required V2 capabilities:
   - `BATCH_READ` with filter/column pushdown
   - `BATCH_WRITE` with overwrite modes
   - `MICRO_BATCH_READ` for streaming
   - `STREAMING_WRITE` for streaming

4. Identify compatibility requirements:
   - Backward compatibility needs
   - Feature parity with current V1 implementation
   - Performance benchmarks to maintain

**Success Criteria**: Clear understanding of V2 API and migration scope documented

---

### Phase 2: Native V2 Batch Reads
**Goal**: Eliminate V1 fallback for batch reads

#### Step 2.1: Implement ScanBuilder
- Add `ScanBuilder` interface to `DeltaTableV2`
- Implement `newScanBuilder(CaseInsensitiveStringMap options)` method
- Create `DeltaScanBuilder` class

#### Step 2.2: Implement Scan with Pushdowns
- Create `DeltaScan` class implementing `Scan`
- Implement `SupportsPushDownFilters` for predicate pushdown
- Implement `SupportsPushDownRequiredColumns` for column pruning
- Implement `SupportsReportStatistics` for better query planning

#### Step 2.3: Implement Batch and Reader
- Create `DeltaBatch` class implementing `Batch`
- Create `DeltaPartitionReaderFactory` for creating readers
- Implement `DeltaPartitionReader` for columnar reads
- Support deletion vectors and column mapping in V2 reader

#### Step 2.4: Remove V1 Fallback
- Remove V1 fallback logic from `FallbackToV1Relations.scala`
- Update `DeltaTableV2` to not extend `V2TableWithV1Fallback`
- Remove `toBaseRelation` method from `DeltaTableV2`

#### Step 2.5: Testing
- Write unit tests for `DeltaScan` and `DeltaBatch`
- Test predicate pushdown with various filter types
- Test column pruning optimization
- Run integration tests for batch reads
- Performance benchmark vs V1 implementation

**Success Criteria**: All batch reads use native V2 API, tests pass, no performance regression

---

### Phase 3: Native V2 Batch Writes
**Goal**: Eliminate V1Write bridge for batch writes

#### Step 3.1: Implement Native V2 WriteBuilder
- Refactor `WriteIntoDeltaBuilder` to return `BatchWrite` (not `V1Write`)
- Ensure support for `SupportsOverwrite`, `SupportsTruncate`, `SupportsDynamicOverwrite`

#### Step 3.2: Implement BatchWrite
- Create `DeltaBatchWrite` class implementing `BatchWrite`
- Migrate transaction logic from `WriteIntoDelta` command
- Handle commit protocol and conflict resolution in V2 framework

#### Step 3.3: Implement DataWriterFactory
- Create `DeltaDataWriterFactory` implementing `DataWriterFactory`
- Create `DeltaDataWriter` for writing records
- Support deletion vectors, column mapping, CDC in V2 writer

#### Step 3.4: Support Write Modes
- Implement append mode
- Implement overwrite by filter
- Implement dynamic partition overwrite
- Implement truncate

#### Step 3.5: Testing
- Write unit tests for `DeltaBatchWrite` and `DeltaDataWriter`
- Test all write modes (append, overwrite, dynamic, truncate)
- Test schema evolution during writes
- Test conflict resolution and optimistic concurrency
- Performance benchmark vs V1 implementation

**Success Criteria**: All batch writes use native V2 API, tests pass, no performance regression

---

### Phase 4: V2 Streaming Support
**Goal**: Replace V1 streaming with V2 MicroBatchStream and StreamingWrite

#### Step 4.1: Implement MicroBatchStream for Reads
- Make `DeltaTableV2` implement `SupportsRead`
- Create `DeltaMicroBatchStream` class implementing `MicroBatchStream`
- Migrate logic from `DeltaSource` to `DeltaMicroBatchStream`
- Support offset management and incremental processing

#### Step 4.2: Implement StreamingWrite for Writes
- Create `DeltaStreamingWrite` class implementing `StreamingWrite`
- Migrate logic from `DeltaSink` to `DeltaStreamingWrite`
- Support exactly-once semantics and idempotent writes

#### Step 4.3: Remove V1 Streaming Providers
- Remove `StreamSourceProvider` from `DeltaDataSource`
- Remove `StreamSinkProvider` from `DeltaDataSource`
- Clean up V1 streaming code

#### Step 4.4: Testing
- Write unit tests for streaming read/write
- Test incremental processing and offset management
- Test exactly-once semantics
- Test fault tolerance and recovery
- Run end-to-end streaming integration tests

**Success Criteria**: All streaming uses V2 API, tests pass, exactly-once semantics maintained

---

### Phase 5: V2-Only Features Implementation
**Goal**: Leverage V2-only capabilities for improved functionality

#### Step 5.1: Dynamic Filter Pushdown
- Implement `SupportsDynamicFiltering` in `DeltaScan`
- Support runtime filter pushdown for better join performance
- Test with broadcast hash joins

#### Step 5.2: Advanced Partition Pruning
- Enhance partition pruning using V2 metadata APIs
- Implement bucket pruning if applicable
- Test query performance improvements

#### Step 5.3: Statistics and Metadata Propagation
- Implement `SupportsReportStatistics` for accurate cardinality estimates
- Propagate column statistics through V2 APIs
- Improve query planning with better statistics

#### Step 5.4: Vectorized Reads
- Optimize `DeltaPartitionReader` for vectorized execution
- Support columnar batch processing
- Benchmark performance improvements

#### Step 5.5: Testing
- Write tests for each V2-only feature
- Performance benchmarks showing improvements
- Integration tests with complex queries

**Success Criteria**: V2-only features working and demonstrating value

---

### Phase 6: Cleanup & Final Validation
**Goal**: Remove all V1 code and validate complete migration

#### Step 6.1: Remove V1 Interfaces
- Remove `RelationProvider` from `DeltaDataSource`
- Remove `CreatableRelationProviderShim` from `DeltaDataSource`
- Remove V1 streaming providers
- Clean up shim code

#### Step 6.2: Remove V1 Fallback Code
- Delete `FallbackToV1Relations.scala` (or mark deprecated)
- Remove V1 fallback paths from all code
- Update `DeltaTableV2` to be pure V2

#### Step 6.3: Comprehensive Testing
- Run full Delta Lake test suite
- Run performance benchmarks (TPC-DS, TPC-H)
- Test backward compatibility scenarios
- Test all Delta features: time travel, VACUUM, OPTIMIZE, etc.

#### Step 6.4: Documentation
- Update user documentation for V2 migration
- Document breaking changes (if any)
- Create migration guide for users
- Update API documentation

**Success Criteria**: All tests pass, documentation complete, ready for release

---

## Risk Assessment & Mitigation

### High Risks
1. **Performance Regression**: V2 implementation might be slower initially
   - Mitigation: Extensive benchmarking, performance testing in each phase

2. **Breaking Changes**: V2 behavior might differ from V1
   - Mitigation: Comprehensive compatibility testing, feature flags for gradual rollout

3. **Complex Refactoring**: Core read/write paths are complex
   - Mitigation: Incremental approach, extensive unit testing, code reviews

### Medium Risks
1. **Streaming Semantics**: V2 streaming might have subtle differences
   - Mitigation: Thorough testing of exactly-once semantics and fault tolerance

2. **Feature Parity**: Ensuring all V1 features work in V2
   - Mitigation: Feature checklist, integration tests for all Delta features

## Success Metrics
- ✅ All batch reads use native V2 API (no V1 fallback)
- ✅ All batch writes use native V2 API (no V1Write bridge)
- ✅ All streaming uses V2 MicroBatchStream/StreamingWrite
- ✅ V2-only features accessible and working
- ✅ All existing tests pass
- ✅ Performance parity or improvement vs V1
- ✅ No breaking changes for users

## Timeline Estimate
- **Phase 1**: 1 week (research)
- **Phase 2**: 3-4 weeks (batch reads - most complex)
- **Phase 3**: 3-4 weeks (batch writes - most critical)
- **Phase 4**: 2-3 weeks (streaming)
- **Phase 5**: 2-3 weeks (V2 features)
- **Phase 6**: 1-2 weeks (cleanup & validation)

**Total**: ~12-17 weeks for full migration

## Notes
- This is a large, complex migration affecting core Delta functionality
- Recommend using feature flags for gradual rollout
- Consider phased releases (Phase 2+3 in one release, Phase 4 in next, etc.)
- Extensive testing and performance validation critical for success
