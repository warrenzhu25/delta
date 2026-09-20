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

package org.apache.spark.sql.delta

import java.sql.Date

import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.spark.sql.delta.test.DeltaSQLCommandTest
import org.apache.spark.sql.delta.v2.DeltaBatchScan

import org.apache.spark.sql.{DataFrame, QueryTest}
import org.apache.spark.sql.execution.datasources.v2.BatchScanExec
import org.apache.spark.sql.execution.exchange.ShuffleExchangeExec
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession

/**
 * Test suite for Delta Lake Storage-Partitioned Join (SPJ).
 * Follows patterns from Apache Iceberg's `TestStoragePartitionedJoins`.
 */
class DeltaStoragePartitionedJoinSuite extends QueryTest
  with SharedSparkSession
  with DeltaSQLCommandTest {

  import testImplicits._

  private def withSPJConf[T](enabled: Boolean)(f: => T): T = {
    withSQLConf(
      SQLConf.V2_BUCKETING_ENABLED.key -> enabled.toString,
      SQLConf.V2_BUCKETING_PUSH_PART_VALUES_ENABLED.key -> "true",
      SQLConf.REQUIRE_ALL_CLUSTER_KEYS_FOR_CO_PARTITION.key -> "false",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false",
      DeltaSQLConf.DELTA_STORAGE_PARTITIONED_JOIN_ENABLED.key -> enabled.toString
    )(f)
  }

  private def assertSPJPlan(query: DataFrame, expectSPJ: Boolean): Unit = {
    val executedPlan = query.queryExecution.executedPlan
    val joinShuffles = executedPlan.collect {
      case s: ShuffleExchangeExec if !s.outputPartitioning.isInstanceOf[org.apache.spark.sql.catalyst.plans.physical.RangePartitioning] => s
    }
    val scans = executedPlan.collect { case b: BatchScanExec => b }

    if (expectSPJ) {
      assert(scans.nonEmpty, "Expected BatchScanExec when SPJ is enabled")
      assert(scans.forall(_.scan.isInstanceOf[DeltaBatchScan]),
        "All scans should be DeltaBatchScan instances")
      assert(joinShuffles.isEmpty,
        s"Expected 0 join shuffle exchanges with SPJ, but found: $joinShuffles")
    } else {
      assert(joinShuffles.nonEmpty,
        "Expected join shuffle exchanges when SPJ is not applicable")
    }
  }

  test("Delta Storage-Partitioned Join eliminates shuffle exchange on single partition key") {
    withTable("t1", "t2") {
      val df1 = Seq((1, "a", "p1"), (2, "b", "p2"), (3, "c", "p3")).toDF("id", "val1", "part")
      val df2 = Seq((1, "x", "p1"), (2, "y", "p2"), (3, "z", "p3")).toDF("id", "val2", "part")

      df1.write.format("delta").partitionBy("part").saveAsTable("t1")
      df2.write.format("delta").partitionBy("part").saveAsTable("t2")

      withSPJConf(enabled = true) {
        val query = spark.sql(
          "SELECT t1.id, t1.val1, t2.val2, t1.part FROM t1 JOIN t2 ON t1.part = t2.part")

        checkAnswer(
          query,
          Seq((1, "a", "x", "p1"), (2, "b", "y", "p2"), (3, "c", "z", "p3"))
            .toDF("id", "val1", "val2", "part")
        )
        assertSPJPlan(query, expectSPJ = true)
      }
    }
  }

  test("SPJ with multiple partition keys (composite partition)") {
    withTable("t_comp1", "t_comp2") {
      val df1 = Seq(
        (1, "p1", 2026, "v1"),
        (2, "p2", 2026, "v2"),
        (3, "p1", 2025, "v3")
      ).toDF("id", "dept", "yr", "val1")

      val df2 = Seq(
        (10, "p1", 2026, "x1"),
        (20, "p2", 2026, "x2"),
        (30, "p1", 2025, "x3")
      ).toDF("id", "dept", "yr", "val2")

      df1.write.format("delta").partitionBy("dept", "yr").saveAsTable("t_comp1")
      df2.write.format("delta").partitionBy("dept", "yr").saveAsTable("t_comp2")

      withSPJConf(enabled = true) {
        val query = spark.sql(
          """SELECT t1.id, t2.id, t1.dept, t1.yr
            |FROM t_comp1 t1 JOIN t_comp2 t2
            |ON t1.dept = t2.dept AND t1.yr = t2.yr
            |""".stripMargin)

        checkAnswer(
          query,
          Seq(
            (1, 10, "p1", 2026),
            (2, 20, "p2", 2026),
            (3, 30, "p1", 2025)
          ).toDF("id1", "id2", "dept", "yr")
        )
        assertSPJPlan(query, expectSPJ = true)
      }
    }
  }

  test("SPJ with mismatched/disjoint partition values (pushPartValues)") {
    withTable("t_sparse1", "t_sparse2") {
      // t_sparse1 has p1, p2
      val df1 = Seq((1, "p1"), (2, "p2")).toDF("id", "part")
      // t_sparse2 has p2, p3 (p1 missing on side 2, p3 missing on side 1)
      val df2 = Seq((2, "p2"), (3, "p3")).toDF("id", "part")

      df1.write.format("delta").partitionBy("part").saveAsTable("t_sparse1")
      df2.write.format("delta").partitionBy("part").saveAsTable("t_sparse2")

      withSPJConf(enabled = true) {
        val query = spark.sql(
          "SELECT t1.id, t2.id, t1.part FROM t_sparse1 t1 INNER JOIN t_sparse2 t2 ON t1.part = t2.part")

        checkAnswer(query, Seq((2, 2, "p2")).toDF("id1", "id2", "part"))
        assertSPJPlan(query, expectSPJ = true)
      }
    }
  }

  test("SPJ with multiple files per partition") {
    withTable("t_multi1", "t_multi2") {
      // Append twice to create multiple files in each partition
      val dfA1 = Seq((1, "a", "p1"), (2, "b", "p2")).toDF("id", "val", "part")
      val dfA2 = Seq((3, "c", "p1"), (4, "d", "p2")).toDF("id", "val", "part")
      dfA1.write.format("delta").partitionBy("part").saveAsTable("t_multi1")
      dfA2.write.format("delta").mode("append").partitionBy("part").saveAsTable("t_multi1")

      val dfB1 = Seq((10, "x", "p1"), (20, "y", "p2")).toDF("id", "val", "part")
      val dfB2 = Seq((30, "z", "p1"), (40, "w", "p2")).toDF("id", "val", "part")
      dfB1.write.format("delta").partitionBy("part").saveAsTable("t_multi2")
      dfB2.write.format("delta").mode("append").partitionBy("part").saveAsTable("t_multi2")

      withSPJConf(enabled = true) {
        val query = spark.sql(
          "SELECT t1.id, t2.id, t1.part FROM t_multi1 t1 JOIN t_multi2 t2 ON t1.part = t2.part")

        val expected = Seq(
          (1, 10, "p1"), (1, 30, "p1"), (3, 10, "p1"), (3, 30, "p1"),
          (2, 20, "p2"), (2, 40, "p2"), (4, 20, "p2"), (4, 40, "p2")
        ).toDF("id1", "id2", "part")

        checkAnswer(query, expected)
        assertSPJPlan(query, expectSPJ = true)
      }
    }
  }

  test("SPJ with typed partition columns: Date and Integer") {
    withTable("t_date1", "t_date2") {
      val d1 = Date.valueOf("2026-01-01")
      val d2 = Date.valueOf("2026-06-01")

      val df1 = Seq((1, 100, d1), (2, 200, d2)).toDF("id", "category", "date_col")
      val df2 = Seq((10, 100, d1), (20, 200, d2)).toDF("other_id", "category", "date_col")

      df1.write.format("delta").partitionBy("date_col", "category").saveAsTable("t_date1")
      df2.write.format("delta").partitionBy("date_col", "category").saveAsTable("t_date2")

      withSPJConf(enabled = true) {
        val query = spark.sql(
          """SELECT t1.id, t2.other_id, t1.category, t1.date_col
            |FROM t_date1 t1 JOIN t_date2 t2
            |ON t1.date_col = t2.date_col AND t1.category = t2.category
            |""".stripMargin)

        checkAnswer(query, Seq((1, 10, 100, d1), (2, 20, 200, d2)).toDF("id", "other_id", "category", "date_col"))
        assertSPJPlan(query, expectSPJ = true)
      }
    }
  }

  test("Incompatible partition columns fall back to shuffle") {
    withTable("t_incompat1", "t_incompat2") {
      val df1 = Seq((1, "p1", "other")).toDF("id", "part1", "part2")
      val df2 = Seq((1, "p1", "other")).toDF("id", "part1", "part2")

      // t_incompat1 partitioned by part1, t_incompat2 partitioned by part2
      df1.write.format("delta").partitionBy("part1").saveAsTable("t_incompat1")
      df2.write.format("delta").partitionBy("part2").saveAsTable("t_incompat2")

      withSPJConf(enabled = true) {
        // Join on t1.part1 = t2.part1: t2 is NOT partitioned by part1, so SPJ cannot eliminate shuffles
        val query = spark.sql(
          "SELECT t1.id, t2.id FROM t_incompat1 t1 JOIN t_incompat2 t2 ON t1.part1 = t2.part1")

        checkAnswer(query, Seq((1, 1)).toDF("id1", "id2"))
        assertSPJPlan(query, expectSPJ = false)
      }
    }
  }

  test("One side unpartitioned falls back to shuffle") {
    withTable("t_part", "t_unpart") {
      val df1 = Seq((1, "p1"), (2, "p2")).toDF("id", "part")
      val df2 = Seq((1, "p1"), (2, "p2")).toDF("id", "part")

      df1.write.format("delta").partitionBy("part").saveAsTable("t_part")
      df2.write.format("delta").saveAsTable("t_unpart") // unpartitioned

      withSPJConf(enabled = true) {
        val query = spark.sql(
          "SELECT t1.id, t2.id FROM t_part t1 JOIN t_unpart t2 ON t1.part = t2.part")

        checkAnswer(query, Seq((1, 1), (2, 2)).toDF("id1", "id2"))
        assertSPJPlan(query, expectSPJ = false)
      }
    }
  }

  test("Empty partitioned table in SPJ falls back to shuffle without error") {
    withTable("t_empty1", "t_empty2") {
      val df1 = Seq((1, "p1"), (2, "p2")).toDF("id", "part")
      df1.write.format("delta").partitionBy("part").saveAsTable("t_empty1")

      // Empty table with same partition schema
      spark.createDataFrame(
        sparkContext.emptyRDD[org.apache.spark.sql.Row],
        df1.schema
      ).write.format("delta").partitionBy("part").saveAsTable("t_empty2")

      withSPJConf(enabled = true) {
        val query = spark.sql(
          "SELECT t1.id, t2.id FROM t_empty1 t1 JOIN t_empty2 t2 ON t1.part = t2.part")

        // Like Iceberg (testJoinsWithEmptyTable), empty tables cannot co-partition and safely
        // fall back to shuffle while producing correct empty results.
        assert(query.collect().isEmpty)
        assertSPJPlan(query, expectSPJ = false)
      }
    }
  }

  test("Fallback to V1 reader when SPJ is disabled") {
    withTable("t_v1") {
      val df = Seq((1, "p1"), (2, "p2")).toDF("id", "part")
      df.write.format("delta").partitionBy("part").saveAsTable("t_v1")

      withSQLConf(
        DeltaSQLConf.DELTA_STORAGE_PARTITIONED_JOIN_ENABLED.key -> "false"
      ) {
        val query = spark.sql("SELECT * FROM t_v1")
        checkAnswer(query, Seq((1, "p1"), (2, "p2")).toDF("id", "part"))

        val executedPlan = query.queryExecution.executedPlan
        val batchScans = executedPlan.collect { case b: BatchScanExec => b }
        assert(batchScans.isEmpty, "Should not use BatchScanExec when SPJ is disabled")
      }
    }
  }

  // E2E test scenarios modeled after Apache Iceberg:
  // (TestStoragePartitionedJoins & TestStoragePartitionedJoinsInRowLevelOperations)

  test("E2E SPJ: Three-way partitioned join without shuffle exchanges") {
    withTable("t_three1", "t_three2", "t_three3") {
      val d1 = Seq((1, "Alice", "hr"), (2, "Bob", "eng"), (3, "Carol", "sales")).toDF("id", "name", "dept")
      val d2 = Seq((1, 1000, "hr"), (2, 2000, "eng"), (3, 3000, "sales")).toDF("id", "salary", "dept")
      val d3 = Seq((1, "NYC", "hr"), (2, "SF", "eng"), (3, "CHI", "sales")).toDF("id", "location", "dept")

      d1.write.format("delta").partitionBy("dept").saveAsTable("t_three1")
      d2.write.format("delta").partitionBy("dept").saveAsTable("t_three2")
      d3.write.format("delta").partitionBy("dept").saveAsTable("t_three3")

      withSPJConf(enabled = true) {
        val query = spark.sql(
          """SELECT t1.name, t2.salary, t3.location, t1.dept
            |FROM t_three1 t1
            |JOIN t_three2 t2 ON t1.dept = t2.dept
            |JOIN t_three3 t3 ON t1.dept = t3.dept
            |""".stripMargin)

        checkAnswer(
          query,
          Seq(
            ("Alice", 1000, "NYC", "hr"),
            ("Bob", 2000, "SF", "eng"),
            ("Carol", 3000, "CHI", "sales")
          ).toDF("name", "salary", "location", "dept")
        )

        val executedPlan = query.queryExecution.executedPlan
        val scans = executedPlan.collect { case b: BatchScanExec => b }
        assert(scans.size == 3, s"Expected 3 BatchScanExecs, got ${scans.size}")
        val joinShuffles = executedPlan.collect {
          case s: ShuffleExchangeExec if !s.outputPartitioning.isInstanceOf[org.apache.spark.sql.catalyst.plans.physical.RangePartitioning] => s
        }
        assert(joinShuffles.isEmpty, s"Expected 0 join shuffles for 3-way SPJ, but found: $joinShuffles")
      }
    }
  }

  test("E2E SPJ: Aggregation with group by partition key avoids shuffle exchange") {
    withTable("t_agg") {
      val df = Seq(
        (1, 100, "hr"), (2, 200, "hr"), (3, 300, "hr"),
        (4, 400, "eng"), (5, 500, "eng"),
        (6, 600, "sales")
      ).toDF("id", "amount", "dept")

      df.write.format("delta").partitionBy("dept").saveAsTable("t_agg")

      withSPJConf(enabled = true) {
        val query = spark.sql(
          "SELECT dept, sum(amount) as total FROM t_agg GROUP BY dept")

        checkAnswer(
          query,
          Seq(("hr", 600L), ("eng", 900L), ("sales", 600L)).toDF("dept", "total")
        )

        val executedPlan = query.queryExecution.executedPlan
        val scans = executedPlan.collect { case b: BatchScanExec => b }
        assert(scans.nonEmpty, "Should use DeltaBatchScan")
        val shuffles = executedPlan.collect { case s: ShuffleExchangeExec => s }
        assert(shuffles.isEmpty, s"Expected 0 shuffle exchanges for partition-keyed aggregation, but found: $shuffles")
      }
    }
  }

  test("E2E SPJ: Subquery / Semi-Join on partition key avoids shuffle exchange") {
    withTable("t_main", "t_filter") {
      val dMain = Seq((1, "hr"), (2, "eng"), (3, "sales"), (4, "marketing")).toDF("id", "dept")
      val dFilter = Seq(("hr", "active"), ("eng", "active")).toDF("dept", "status")

      dMain.write.format("delta").partitionBy("dept").saveAsTable("t_main")
      dFilter.write.format("delta").partitionBy("dept").saveAsTable("t_filter")

      withSPJConf(enabled = true) {
        val query = spark.sql(
          """SELECT id, dept FROM t_main
            |WHERE dept IN (SELECT dept FROM t_filter WHERE status = 'active')
            |""".stripMargin)

        checkAnswer(query, Seq((1, "hr"), (2, "eng")).toDF("id", "dept"))

        val executedPlan = query.queryExecution.executedPlan
        val scans = executedPlan.collect { case b: BatchScanExec => b }
        assert(scans.size == 2, s"Expected 2 BatchScanExecs for semi-join, got ${scans.size}")
        val joinShuffles = executedPlan.collect {
          case s: ShuffleExchangeExec if !s.outputPartitioning.isInstanceOf[org.apache.spark.sql.catalyst.plans.physical.RangePartitioning] => s
        }
        assert(joinShuffles.isEmpty, s"Expected 0 join shuffles for semi-join with SPJ, but found: $joinShuffles")
      }
    }
  }

  test("E2E SPJ: Left, Right, and Full Outer Joins without shuffle exchanges") {
    withTable("t_outer1", "t_outer2") {
      val df1 = Seq((1, "a", "p1"), (2, "b", "p2")).toDF("id", "val1", "part")
      val df2 = Seq((20, "y", "p2"), (30, "z", "p3")).toDF("id", "val2", "part")

      df1.write.format("delta").partitionBy("part").saveAsTable("t_outer1")
      df2.write.format("delta").partitionBy("part").saveAsTable("t_outer2")

      withSPJConf(enabled = true) {
        // Left Outer Join
        val leftQ = spark.sql(
          "SELECT t1.id, t2.id, t1.part FROM t_outer1 t1 LEFT OUTER JOIN t_outer2 t2 ON t1.part = t2.part")
        checkAnswer(leftQ, Seq((Some(1), None, "p1"), (Some(2), Some(20), "p2")).toDF("id1", "id2", "part"))
        assertSPJPlan(leftQ, expectSPJ = true)

        // Right Outer Join
        val rightQ = spark.sql(
          "SELECT t1.id, t2.id, t2.part FROM t_outer1 t1 RIGHT OUTER JOIN t_outer2 t2 ON t1.part = t2.part")
        checkAnswer(rightQ, Seq((Some(2), Some(20), "p2"), (None, Some(30), "p3")).toDF("id1", "id2", "part"))
        assertSPJPlan(rightQ, expectSPJ = true)

        // Full Outer Join
        val fullQ = spark.sql(
          "SELECT t1.id, t2.id, COALESCE(t1.part, t2.part) FROM t_outer1 t1 FULL OUTER JOIN t_outer2 t2 ON t1.part = t2.part")
        checkAnswer(fullQ, Seq((Some(1), None, "p1"), (Some(2), Some(20), "p2"), (None, Some(30), "p3")).toDF("id1", "id2", "part"))
        assertSPJPlan(fullQ, expectSPJ = true)
      }
    }
  }

  test("E2E SPJ: Join keys subset of partition keys (allowJoinKeysSubsetOfPartitionKeys)") {
    withTable("t_sub1", "t_sub2") {
      val df1 = Seq((1, "us", "ca"), (2, "us", "ny"), (3, "eu", "de")).toDF("id", "region", "state")
      val df2 = Seq((10, "us", "tx"), (20, "eu", "fr")).toDF("id", "region", "state")

      df1.write.format("delta").partitionBy("region", "state").saveAsTable("t_sub1")
      df2.write.format("delta").partitionBy("region", "state").saveAsTable("t_sub2")

      withSPJConf(enabled = true) {
        withSQLConf(
          SQLConf.V2_BUCKETING_ALLOW_JOIN_KEYS_SUBSET_OF_PARTITION_KEYS.key -> "true"
        ) {
          // Join only on `region` (subset of `(region, state)`)
          val query = spark.sql(
            "SELECT t1.id, t2.id, t1.region FROM t_sub1 t1 JOIN t_sub2 t2 ON t1.region = t2.region")
          checkAnswer(
            query,
            Seq((1, 10, "us"), (2, 10, "us"), (3, 20, "eu")).toDF("id1", "id2", "region")
          )
          assertSPJPlan(query, expectSPJ = true)
        }
      }
    }
  }

  test("SPJ with WHERE partition filter pushdown prunes non-matching partitions") {
    withTable("t_prune1", "t_prune2") {
      val df1 = Seq((1, "p1"), (2, "p2"), (3, "p3")).toDF("id", "part")
      val df2 = Seq((10, "p1"), (20, "p2"), (30, "p3")).toDF("id", "part")

      df1.write.format("delta").partitionBy("part").saveAsTable("t_prune1")
      df2.write.format("delta").partitionBy("part").saveAsTable("t_prune2")

      withSPJConf(enabled = true) {
        val query = spark.sql(
          "SELECT t1.id, t2.id, t1.part FROM t_prune1 t1 JOIN t_prune2 t2 ON t1.part = t2.part WHERE t1.part = 'p2'")
        checkAnswer(query, Seq((2, 20, "p2")).toDF("id1", "id2", "part"))
        assertSPJPlan(query, expectSPJ = true)

        val scans = query.queryExecution.executedPlan.collect { case b: BatchScanExec => b }
        // Verify partition pruning reduced planned partitions to 1 on each side
        assert(scans.forall(_.scan.asInstanceOf[DeltaBatchScan].planInputPartitions().length == 1),
          "Partition filter pushdown should prune scan to 1 partition")
      }
    }
  }

  test("Deletion Vector enabled table safely falls back to V1 and filters deleted rows") {
    withTable("t_dv1", "t_dv2") {
      sql("CREATE TABLE t_dv1 (id INT, part STRING) USING delta PARTITIONED BY (part) " +
        "TBLPROPERTIES ('delta.enableDeletionVectors' = 'true')")
      sql("CREATE TABLE t_dv2 (id INT, part STRING) USING delta PARTITIONED BY (part)")

      sql("INSERT INTO t_dv1 VALUES (1, 'p1'), (2, 'p1'), (3, 'p2')")
      sql("INSERT INTO t_dv2 VALUES (10, 'p1'), (30, 'p2')")
      // Delete one row from p1 to generate a Deletion Vector
      sql("DELETE FROM t_dv1 WHERE id = 2")

      withSPJConf(enabled = true) {
        val query = spark.sql("SELECT t1.id, t2.id, t1.part FROM t_dv1 t1 JOIN t_dv2 t2 ON t1.part = t2.part")
        // Verify deleted row id = 2 is NOT returned
        checkAnswer(query, Seq((1, 10, "p1"), (3, 30, "p2")).toDF("id1", "id2", "part"))
      }
    }
  }

  test("SPJ with Delta Column Mapping (name mode)") {
    withTable("t_cm1", "t_cm2") {
      sql("CREATE TABLE t_cm1 (id INT, part STRING) USING delta PARTITIONED BY (part) " +
        "TBLPROPERTIES ('delta.columnMapping.mode' = 'name')")
      sql("CREATE TABLE t_cm2 (id INT, part STRING) USING delta PARTITIONED BY (part) " +
        "TBLPROPERTIES ('delta.columnMapping.mode' = 'name')")

      sql("INSERT INTO t_cm1 VALUES (1, 'p1'), (2, 'p2')")
      sql("INSERT INTO t_cm2 VALUES (10, 'p1'), (20, 'p2')")

      withSPJConf(enabled = true) {
        val query = spark.sql("SELECT t1.id, t2.id, t1.part FROM t_cm1 t1 JOIN t_cm2 t2 ON t1.part = t2.part")
        checkAnswer(query, Seq((1, 10, "p1"), (2, 20, "p2")).toDF("id1", "id2", "part"))
        assertSPJPlan(query, expectSPJ = true)
      }
    }
  }

  test("SPJ with tables containing NULL partition values") {
    withTable("t_null1", "t_null2") {
      val df1 = Seq((1, Some("p1")), (2, None)).toDF("id", "part")
      val df2 = Seq((10, Some("p1")), (20, None)).toDF("id", "part")

      df1.write.format("delta").partitionBy("part").saveAsTable("t_null1")
      df2.write.format("delta").partitionBy("part").saveAsTable("t_null2")

      withSPJConf(enabled = true) {
        // Equality join on tables that contain NULL partition values should still eliminate shuffle
        val query = spark.sql("SELECT t1.id, t2.id, t1.part FROM t_null1 t1 JOIN t_null2 t2 ON t1.part = t2.part")
        checkAnswer(query, Seq((1, 10, "p1")).toDF("id1", "id2", "part"))
        assertSPJPlan(query, expectSPJ = true)
      }
    }
  }
}
