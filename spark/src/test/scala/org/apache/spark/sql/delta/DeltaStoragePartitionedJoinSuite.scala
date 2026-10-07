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

import org.apache.spark.SparkThrowable
import org.apache.spark.sql.{DataFrame, QueryTest, Row}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.plans.physical.RangePartitioning
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.datasources.v2.BatchScanExec
import org.apache.spark.sql.execution.exchange.ShuffleExchangeExec
import org.apache.spark.sql.execution.joins.BroadcastHashJoinExec
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

  private def batchScans(plan: SparkPlan): Seq[BatchScanExec] =
    plan.collect { case b: BatchScanExec => b }

  /** Shuffles introduced for joins/aggregations, i.e. excluding range shuffles for ORDER BY. */
  private def joinShuffles(plan: SparkPlan): Seq[ShuffleExchangeExec] = plan.collect {
    case s: ShuffleExchangeExec if !s.outputPartitioning.isInstanceOf[RangePartitioning] => s
  }

  private def assertSPJPlan(query: DataFrame, expectSPJ: Boolean): Unit = {
    val executedPlan = query.queryExecution.executedPlan
    val shuffles = joinShuffles(executedPlan)
    if (expectSPJ) {
      val scans = batchScans(executedPlan)
      assert(scans.nonEmpty, "Expected BatchScanExec when SPJ is enabled")
      assert(scans.forall(_.scan.isInstanceOf[DeltaBatchScan]),
        "All scans should be DeltaBatchScan instances")
      assert(shuffles.isEmpty, s"Expected 0 join shuffle exchanges with SPJ, but found: $shuffles")
    } else {
      assert(shuffles.nonEmpty, "Expected join shuffle exchanges when SPJ is not applicable")
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
          "SELECT t1.id, t2.id, t1.part FROM t_sparse1 t1 " +
            "INNER JOIN t_sparse2 t2 ON t1.part = t2.part")

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

        checkAnswer(
          query,
          Seq((1, 10, 100, d1), (2, 20, 200, d2)).toDF("id", "other_id", "category", "date_col"))
        assertSPJPlan(query, expectSPJ = true)
      }
    }
  }

  test("SPJ with Timestamp partition column returns the same values as V1") {
    withTable("t_ts") {
      sql("CREATE TABLE t_ts (id INT, ts TIMESTAMP) USING delta PARTITIONED BY (ts)")
      sql("INSERT INTO t_ts VALUES (1, TIMESTAMP'2026-01-01 10:00:00'), " +
        "(2, TIMESTAMP'2026-06-01 23:59:59')")
      val expected = sql("SELECT * FROM t_ts").collect().toSeq

      withSPJConf(enabled = true) {
        val query = sql("SELECT * FROM t_ts")
        checkAnswer(query, expected)
        assert(batchScans(query.queryExecution.executedPlan).nonEmpty)
      }
    }
  }

  test("Partition values are read from the Delta log when not materialized in Parquet files") {
    withTable("t_nomat1", "t_nomat2") {
      Seq("t_nomat1", "t_nomat2").foreach { t =>
        sql(s"CREATE TABLE $t (part STRING, id INT, v STRING) USING delta PARTITIONED BY (part) " +
          "TBLPROPERTIES ('delta.writePartitionColumnsToParquet' = 'false')")
      }
      sql("INSERT INTO t_nomat1 VALUES ('a', 1, 'x'), ('b', 2, 'y')")
      sql("INSERT INTO t_nomat2 VALUES ('a', 10, 'p'), ('b', 20, 'q')")

      withSPJConf(enabled = true) {
        checkAnswer(sql("SELECT * FROM t_nomat1"), Seq(Row("a", 1, "x"), Row("b", 2, "y")))
        checkAnswer(sql("SELECT id, part FROM t_nomat1"), Seq(Row(1, "a"), Row(2, "b")))
        val query = sql(
          "SELECT t1.id, t2.id, t1.part FROM t_nomat1 t1 JOIN t_nomat2 t2 ON t1.part = t2.part")
        checkAnswer(query, Seq(Row(1, 10, "a"), Row(2, 20, "b")))
        assertSPJPlan(query, expectSPJ = true)
      }
    }
  }

  test("Partition column that is not the last column in the schema") {
    withTable("t_order") {
      sql("CREATE TABLE t_order (part STRING, id INT, v STRING) USING delta PARTITIONED BY (part)")
      sql("INSERT INTO t_order VALUES ('a', 1, 'x'), ('b', 2, 'y')")

      withSPJConf(enabled = true) {
        val query = sql("SELECT * FROM t_order")
        checkAnswer(query, Seq(Row("a", 1, "x"), Row("b", 2, "y")))
        assert(batchScans(query.queryExecution.executedPlan).nonEmpty)
        checkAnswer(sql("SELECT id, part FROM t_order"), Seq(Row(1, "a"), Row(2, "b")))
        checkAnswer(sql("SELECT v FROM t_order WHERE id = 2"), Seq(Row("y")))
        checkAnswer(sql("SELECT part FROM t_order"), Seq(Row("a"), Row("b")))
        checkAnswer(sql("SELECT count(*) FROM t_order"), Row(2L))
      }
    }
  }

  test("Time travel reads the requested version") {
    withTable("t_tt") {
      sql("CREATE TABLE t_tt (id INT, part STRING) USING delta PARTITIONED BY (part)")
      sql("INSERT INTO t_tt VALUES (1, 'a')")
      sql("INSERT INTO t_tt VALUES (2, 'b')")

      withSPJConf(enabled = true) {
        checkAnswer(sql("SELECT * FROM t_tt VERSION AS OF 1"), Seq(Row(1, "a")))
        checkAnswer(spark.read.option("versionAsOf", "1").table("t_tt"), Seq(Row(1, "a")))
        checkAnswer(sql("SELECT * FROM t_tt"), Seq(Row(1, "a"), Row(2, "b")))
      }
    }
  }

  test("DML on partitioned tables works with SPJ enabled") {
    withTable("t_dml", "t_src") {
      sql("CREATE TABLE t_dml (id INT, part STRING) USING delta PARTITIONED BY (part)")
      sql("CREATE TABLE t_src (id INT, part STRING) USING delta PARTITIONED BY (part)")
      sql("INSERT INTO t_dml VALUES (1, 'a'), (2, 'b')")
      sql("INSERT INTO t_src VALUES (2, 'b'), (3, 'c')")

      withSPJConf(enabled = true) {
        sql("UPDATE t_dml SET id = 10 WHERE part = 'a'")
        sql("DELETE FROM t_dml WHERE id = 2")
        sql("MERGE INTO t_dml t USING t_src s ON t.part = s.part " +
          "WHEN MATCHED THEN UPDATE SET * WHEN NOT MATCHED THEN INSERT *")
        sql("INSERT INTO t_dml SELECT * FROM t_src WHERE part = 'c'")
        checkAnswer(sql("SELECT * FROM t_dml"),
          Seq(Row(10, "a"), Row(2, "b"), Row(3, "c"), Row(3, "c")))
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
        // t2 is NOT partitioned by part1, so SPJ cannot eliminate shuffles
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
      spark.createDataFrame(sparkContext.emptyRDD[Row], df1.schema)
        .write.format("delta").partitionBy("part").saveAsTable("t_empty2")

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
        SQLConf.V2_BUCKETING_ENABLED.key -> "true",
        DeltaSQLConf.DELTA_STORAGE_PARTITIONED_JOIN_ENABLED.key -> "false"
      ) {
        val query = spark.sql("SELECT * FROM t_v1")
        checkAnswer(query, Seq((1, "p1"), (2, "p2")).toDF("id", "part"))
        assert(batchScans(query.queryExecution.executedPlan).isEmpty,
          "Should not use BatchScanExec when SPJ is disabled")
      }
    }
  }

  test("Fallback to V1 reader when Spark V2 bucketing is disabled") {
    withTable("t_v1_bucketing") {
      val df = Seq((1, "p1"), (2, "p2")).toDF("id", "part")
      df.write.format("delta").partitionBy("part").saveAsTable("t_v1_bucketing")

      withSQLConf(
        SQLConf.V2_BUCKETING_ENABLED.key -> "false",
        DeltaSQLConf.DELTA_STORAGE_PARTITIONED_JOIN_ENABLED.key -> "true"
      ) {
        val query = spark.sql("SELECT * FROM t_v1_bucketing")
        checkAnswer(query, Seq((1, "p1"), (2, "p2")).toDF("id", "part"))
        assert(batchScans(query.queryExecution.executedPlan).isEmpty,
          "Should not use BatchScanExec when spark.sql.sources.v2.bucketing.enabled is false")
      }
    }
  }

  test("Small tables read through the V2 scan are still broadcast") {
    withTable("t_stats1", "t_stats2") {
      Seq((1, "a", "p1"), (2, "b", "p2")).toDF("id", "v1", "part")
        .write.format("delta").partitionBy("part").saveAsTable("t_stats1")
      Seq((1, "x", "p1"), (2, "y", "p2")).toDF("id", "v2", "part")
        .write.format("delta").partitionBy("part").saveAsTable("t_stats2")

      withSPJConf(enabled = true) {
        withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "10MB") {
          // Join on a non-partition column: SPJ doesn't apply, a broadcast join is expected.
          val query = spark.sql(
            "SELECT t1.id, t1.v1, t2.v2 FROM t_stats1 t1 JOIN t_stats2 t2 ON t1.id = t2.id")
          checkAnswer(query, Seq(Row(1, "a", "x"), Row(2, "b", "y")))
          val plan = query.queryExecution.executedPlan
          assert(batchScans(plan).size == 2, "Both sides should use the V2 scan")
          assert(plan.collect { case j: BroadcastHashJoinExec => j }.nonEmpty,
            s"Expected a broadcast join, got:\n$plan")
        }
      }
    }
  }

  test("V2 scan reports the size of the selected files") {
    withTable("t_stats") {
      Seq((1, "p1"), (2, "p1"), (3, "p2")).toDF("id", "part")
        .write.format("delta").partitionBy("part").saveAsTable("t_stats")
      val snapshot = DeltaLog.forTable(spark, TableIdentifier("t_stats")).update()

      withSPJConf(enabled = true) {
        def scanSize(query: String): Long = {
          val scans = batchScans(spark.sql(query).queryExecution.executedPlan)
          assert(scans.size == 1)
          scans.head.scan.asInstanceOf[DeltaBatchScan].estimateStatistics().sizeInBytes()
            .getAsLong
        }

        assert(scanSize("SELECT * FROM t_stats") == snapshot.sizeInBytes)

        val p2Size = snapshot.allFiles.filter("partitionValues.part = 'p2'").collect()
          .map(_.size).sum
        assert(p2Size > 0 && p2Size < snapshot.sizeInBytes)
        assert(scanSize("SELECT * FROM t_stats WHERE part = 'p2'") == p2Size)
      }
    }
  }

  // E2E test scenarios modeled after Apache Iceberg:
  // (TestStoragePartitionedJoins & TestStoragePartitionedJoinsInRowLevelOperations)

  test("E2E SPJ: Three-way partitioned join without shuffle exchanges") {
    withTable("t_three1", "t_three2", "t_three3") {
      val d1 = Seq((1, "Alice", "hr"), (2, "Bob", "eng"), (3, "Carol", "sales"))
        .toDF("id", "name", "dept")
      val d2 = Seq((1, 1000, "hr"), (2, 2000, "eng"), (3, 3000, "sales"))
        .toDF("id", "salary", "dept")
      val d3 = Seq((1, "NYC", "hr"), (2, "SF", "eng"), (3, "CHI", "sales"))
        .toDF("id", "location", "dept")

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
        val scans = batchScans(executedPlan)
        assert(scans.size == 3, s"Expected 3 BatchScanExecs, got ${scans.size}")
        val shuffles = joinShuffles(executedPlan)
        assert(shuffles.isEmpty, s"Expected 0 join shuffles for 3-way SPJ, but found: $shuffles")
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
        assert(batchScans(executedPlan).nonEmpty, "Should use DeltaBatchScan")
        val shuffles = executedPlan.collect { case s: ShuffleExchangeExec => s }
        assert(shuffles.isEmpty,
          s"Expected 0 shuffle exchanges for partition-keyed aggregation, but found: $shuffles")
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
        val scans = batchScans(executedPlan)
        assert(scans.size == 2, s"Expected 2 BatchScanExecs for semi-join, got ${scans.size}")
        val shuffles = joinShuffles(executedPlan)
        assert(shuffles.isEmpty,
          s"Expected 0 join shuffles for semi-join with SPJ, but found: $shuffles")
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
        val leftQ = spark.sql(
          "SELECT t1.id, t2.id, t1.part FROM t_outer1 t1 " +
            "LEFT OUTER JOIN t_outer2 t2 ON t1.part = t2.part")
        checkAnswer(leftQ, Seq(Row(1, null, "p1"), Row(2, 20, "p2")))
        assertSPJPlan(leftQ, expectSPJ = true)

        val rightQ = spark.sql(
          "SELECT t1.id, t2.id, t2.part FROM t_outer1 t1 " +
            "RIGHT OUTER JOIN t_outer2 t2 ON t1.part = t2.part")
        checkAnswer(rightQ, Seq(Row(2, 20, "p2"), Row(null, 30, "p3")))
        assertSPJPlan(rightQ, expectSPJ = true)

        val fullQ = spark.sql(
          "SELECT t1.id, t2.id, COALESCE(t1.part, t2.part) FROM t_outer1 t1 " +
            "FULL OUTER JOIN t_outer2 t2 ON t1.part = t2.part")
        checkAnswer(fullQ, Seq(Row(1, null, "p1"), Row(2, 20, "p2"), Row(null, 30, "p3")))
        assertSPJPlan(fullQ, expectSPJ = true)
      }
    }
  }

  test("E2E SPJ: Join keys subset of partition keys (allowJoinKeysSubsetOfPartitionKeys)") {
    withTable("t_sub1", "t_sub2") {
      val df1 = Seq((1, "us", "ca"), (2, "us", "ny"), (3, "eu", "de"))
        .toDF("id", "region", "state")
      val df2 = Seq((10, "us", "tx"), (20, "eu", "fr")).toDF("id", "region", "state")

      df1.write.format("delta").partitionBy("region", "state").saveAsTable("t_sub1")
      df2.write.format("delta").partitionBy("region", "state").saveAsTable("t_sub2")

      withSPJConf(enabled = true) {
        withSQLConf(
          SQLConf.V2_BUCKETING_ALLOW_JOIN_KEYS_SUBSET_OF_PARTITION_KEYS.key -> "true"
        ) {
          // Join only on `region` (subset of `(region, state)`)
          val query = spark.sql(
            "SELECT t1.id, t2.id, t1.region FROM t_sub1 t1 JOIN t_sub2 t2 " +
              "ON t1.region = t2.region")
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
          "SELECT t1.id, t2.id, t1.part FROM t_prune1 t1 JOIN t_prune2 t2 " +
            "ON t1.part = t2.part WHERE t1.part = 'p2'")
        checkAnswer(query, Seq((2, 20, "p2")).toDF("id1", "id2", "part"))
        assertSPJPlan(query, expectSPJ = true)

        // Verify partition pruning reduced planned partitions to 1 on each side
        val scans = batchScans(query.queryExecution.executedPlan)
        assert(scans.forall(_.scan.asInstanceOf[DeltaBatchScan].planInputPartitions().length == 1),
          "Partition filter pushdown should prune scan to 1 partition")
      }
    }
  }

  private def numFilesWithDVs(table: String): Long =
    DeltaLog.forTable(spark, TableIdentifier(table)).update().allFiles
      .filter("deletionVector IS NOT NULL").count()

  /** Checks that `query` returns the same rows as the V1 reader and is planned with SPJ. */
  private def checkSPJAnswerMatchesV1(query: String): Unit = {
    val expected = withSPJConf(enabled = false) { sql(query).collect().toSeq }
    withSPJConf(enabled = true) {
      val df = sql(query)
      checkAnswer(df, expected)
      assertSPJPlan(df, expectSPJ = true)
    }
  }

  /** Inserts rows with a single task, so each partition gets one file and DELETEs write DVs. */
  private def insertValues(table: String, values: String): Unit =
    sql(s"INSERT INTO $table SELECT /*+ REPARTITION(1) */ * FROM VALUES $values")

  private def createDVTable(name: String, extraProps: String = ""): Unit = {
    sql(s"CREATE TABLE $name (id INT, v STRING, part STRING) USING delta PARTITIONED BY (part) " +
      s"TBLPROPERTIES ('delta.enableDeletionVectors' = 'true'$extraProps)")
  }

  for {
    useMetadataRowIndex <- Seq(true, false)
    vectorized <- Seq(true, false)
  } test(s"SPJ filters rows deleted by Deletion Vectors - " +
      s"useMetadataRowIndex=$useMetadataRowIndex, vectorized=$vectorized") {
    withSQLConf(
      DeltaSQLConf.DELETION_VECTORS_USE_METADATA_ROW_INDEX.key -> useMetadataRowIndex.toString,
      SQLConf.PARQUET_VECTORIZED_READER_ENABLED.key -> vectorized.toString) {
      withTable("t_dv1", "t_dv2") {
        createDVTable("t_dv1")
        createDVTable("t_dv2")
        insertValues("t_dv1", "(1, 'a', 'p1'), (2, 'b', 'p1'), (3, 'c', 'p1'), " +
          "(4, 'd', 'p2'), (5, 'e', 'p2'), (6, 'f', 'p3')")
        insertValues("t_dv2", "(10, 'x', 'p1'), (20, 'y', 'p2'), (21, 'z', 'p2'), " +
          "(30, 'w', 'p3')")
        sql("DELETE FROM t_dv1 WHERE id IN (2, 5)")
        sql("DELETE FROM t_dv2 WHERE id = 21")
        assert(numFilesWithDVs("t_dv1") == 2)
        assert(numFilesWithDVs("t_dv2") == 1)

        val query = "SELECT t1.id, t1.v, t2.id, t2.v, t1.part FROM t_dv1 t1 JOIN t_dv2 t2 " +
          "ON t1.part = t2.part"
        checkSPJAnswerMatchesV1(query)
        withSPJConf(enabled = true) {
          checkAnswer(sql(query), Seq(
            Row(1, "a", 10, "x", "p1"), Row(3, "c", 10, "x", "p1"),
            Row(4, "d", 20, "y", "p2"), Row(6, "f", 30, "w", "p3")))
        }
      }
    }
  }

  test("SPJ on Deletion Vector tables after multiple DELETEs and UPDATE") {
    withTable("t_dv1", "t_dv2") {
      createDVTable("t_dv1")
      createDVTable("t_dv2")
      sql("INSERT INTO t_dv1 SELECT /*+ REPARTITION(1) */ id, CAST(id AS STRING), " +
        "CONCAT('p', id % 3) FROM range(30)")
      sql("INSERT INTO t_dv2 SELECT /*+ REPARTITION(1) */ id, CAST(id AS STRING), " +
        "CONCAT('p', id % 3) FROM range(30)")
      sql("DELETE FROM t_dv1 WHERE id % 5 = 0")
      sql("DELETE FROM t_dv1 WHERE id % 7 = 0")
      sql("UPDATE t_dv2 SET v = 'updated' WHERE id % 4 = 0")
      assert(numFilesWithDVs("t_dv1") > 0)
      assert(numFilesWithDVs("t_dv2") > 0)

      checkSPJAnswerMatchesV1("SELECT t1.id, t2.id, t2.v, t1.part FROM t_dv1 t1 " +
        "JOIN t_dv2 t2 ON t1.part = t2.part AND t1.id = t2.id")
      checkSPJAnswerMatchesV1("SELECT part, count(*), sum(id) FROM t_dv1 GROUP BY part " +
        "ORDER BY part") // single table read with DVs; no join shuffle expected
    }
  }

  test("SPJ on Deletion Vector table with DVs in some partitions and a partition filter") {
    withTable("t_dv1", "t_dv2") {
      createDVTable("t_dv1")
      createDVTable("t_dv2")
      insertValues("t_dv1", "(1, 'a', 'p1'), (2, 'b', 'p1'), (3, 'c', 'p2'), " +
        "(4, 'd', 'p2'), (5, 'e', 'p3')")
      insertValues("t_dv2", "(10, 'x', 'p1'), (20, 'y', 'p2'), (30, 'z', 'p3')")
      // Only partition p2 gets a DV.
      sql("DELETE FROM t_dv1 WHERE id = 4")
      assert(numFilesWithDVs("t_dv1") == 1)

      checkSPJAnswerMatchesV1("SELECT t1.id, t2.id, t1.part FROM t_dv1 t1 JOIN t_dv2 t2 " +
        "ON t1.part = t2.part")
      // The DV-free partition alone.
      checkSPJAnswerMatchesV1("SELECT t1.id, t2.id, t1.part FROM t_dv1 t1 JOIN t_dv2 t2 " +
        "ON t1.part = t2.part WHERE t1.part = 'p1'")
      // The partition with the DV alone.
      checkSPJAnswerMatchesV1("SELECT t1.id, t2.id, t1.part FROM t_dv1 t1 JOIN t_dv2 t2 " +
        "ON t1.part = t2.part WHERE t1.part = 'p2'")
      // Data filter on a column of the table with DVs.
      checkSPJAnswerMatchesV1("SELECT t1.id, t2.id, t1.part FROM t_dv1 t1 JOIN t_dv2 t2 " +
        "ON t1.part = t2.part WHERE t1.id > 2")
    }
  }

  test("SPJ on Deletion Vector enabled table without any DV") {
    withTable("t_dv1", "t_dv2") {
      createDVTable("t_dv1")
      sql("CREATE TABLE t_dv2 (id INT, part STRING) USING delta PARTITIONED BY (part)")
      insertValues("t_dv1", "(1, 'a', 'p1'), (2, 'b', 'p2')")
      insertValues("t_dv2", "(10, 'p1'), (20, 'p2')")
      assert(numFilesWithDVs("t_dv1") == 0)
      checkSPJAnswerMatchesV1("SELECT t1.id, t2.id, t1.part FROM t_dv1 t1 JOIN t_dv2 t2 " +
        "ON t1.part = t2.part")
    }
  }

  test("SPJ on Deletion Vector tables with column mapping") {
    withTable("t_dv1", "t_dv2") {
      val cm = ", 'delta.columnMapping.mode' = 'name'"
      createDVTable("t_dv1", cm)
      createDVTable("t_dv2", cm)
      insertValues("t_dv1", "(1, 'a', 'p1'), (2, 'b', 'p1'), (3, 'c', 'p2')")
      insertValues("t_dv2", "(10, 'x', 'p1'), (20, 'y', 'p2')")
      sql("ALTER TABLE t_dv1 RENAME COLUMN v TO v_renamed")
      sql("DELETE FROM t_dv1 WHERE id = 1")
      assert(numFilesWithDVs("t_dv1") == 1)
      checkSPJAnswerMatchesV1("SELECT t1.id, t1.v_renamed, t2.id, t1.part FROM t_dv1 t1 " +
        "JOIN t_dv2 t2 ON t1.part = t2.part")
    }
  }

  test("Deletion Vector tables fall back to V1 when DV support is disabled") {
    withTable("t_dv1", "t_dv2") {
      createDVTable("t_dv1")
      sql("CREATE TABLE t_dv2 (id INT, part STRING) USING delta PARTITIONED BY (part)")
      insertValues("t_dv1", "(1, 'a', 'p1'), (2, 'b', 'p1'), (3, 'c', 'p2')")
      insertValues("t_dv2", "(10, 'p1'), (30, 'p2')")
      sql("DELETE FROM t_dv1 WHERE id = 2")

      withSPJConf(enabled = true) {
        withSQLConf(
          DeltaSQLConf.DELTA_STORAGE_PARTITIONED_JOIN_DELETION_VECTORS_ENABLED.key -> "false") {
          val query = spark.sql(
            "SELECT t1.id, t2.id, t1.part FROM t_dv1 t1 JOIN t_dv2 t2 ON t1.part = t2.part")
          checkAnswer(query, Seq((1, 10, "p1"), (3, 30, "p2")).toDF("id1", "id2", "part"))
          // Only the DV-free table is read through the V2 scan.
          assert(batchScans(query.queryExecution.executedPlan).size == 1)
        }
      }
    }
  }

  /**
   * Checks that `query` returns the same rows with SPJ enabled as with the V1 reader, and that
   * with SPJ enabled the table is (or is not) read through the V2 scan.
   */
  private def checkMatchesV1(query: => DataFrame, expectV2: Boolean = true): Unit = {
    val expected = withSPJConf(enabled = false) { query.collect().toSeq }
    withSPJConf(enabled = true) {
      val df = query
      checkAnswer(df, expected)
      assert(batchScans(df.queryExecution.executedPlan).nonEmpty == expectV2,
        s"Expected V2 scan: $expectV2\n${df.queryExecution.executedPlan}")
    }
  }

  private val fileMetadataFields = Seq("file_path", "file_name", "file_size", "file_block_start",
    "file_block_length", "file_modification_time")

  test("_metadata file fields read through the V2 scan match V1") {
    withTable("t_md") {
      sql("CREATE TABLE t_md (id INT, v STRING, part STRING) USING delta PARTITIONED BY (part)")
      insertValues("t_md", "(1, 'a', 'p1'), (2, 'b', 'p1'), (3, 'c', 'p2')")
      insertValues("t_md", "(4, 'd', 'p2'), (5, 'e', 'p3')")

      fileMetadataFields.foreach { field =>
        checkMatchesV1(sql(s"SELECT id, part, _metadata.$field FROM t_md"))
      }
      checkMatchesV1(sql("SELECT id, _metadata FROM t_md"))
      checkMatchesV1(sql("SELECT _metadata.file_name, count(*) FROM t_md GROUP BY 1"))
      // Only `_metadata`, no data or partition column.
      checkMatchesV1(sql("SELECT _metadata.file_size FROM t_md"))

      // A filter on a `_metadata` field is evaluated after the scan.
      val fileName = withSPJConf(enabled = false) {
        sql("SELECT _metadata.file_name FROM t_md WHERE id = 3").head().getString(0)
      }
      checkMatchesV1(sql(s"SELECT id FROM t_md WHERE _metadata.file_name = '$fileName'"))
      withSPJConf(enabled = true) {
        checkAnswer(sql(s"SELECT id FROM t_md WHERE _metadata.file_name = '$fileName'"),
          Seq(Row(3)))
      }

      // DataFrame API.
      checkMatchesV1(spark.table("t_md").select("id", "_metadata.file_path", "part"))
    }
  }

  test("A table column named _metadata hides the metadata column in the V2 scan") {
    withTable("t_md") {
      sql("CREATE TABLE t_md (id INT, _metadata STRING, part STRING) USING delta " +
        "PARTITIONED BY (part)")
      insertValues("t_md", "(1, 'x', 'p1'), (2, 'y', 'p2')")
      checkMatchesV1(sql("SELECT id, _metadata, part FROM t_md"))
      withSPJConf(enabled = true) {
        checkAnswer(sql("SELECT id, _metadata FROM t_md"), Seq(Row(1, "x"), Row(2, "y")))
      }
    }
  }

  test("SPJ join selecting _metadata columns avoids shuffle") {
    withTable("t_md1", "t_md2") {
      sql("CREATE TABLE t_md1 (id INT, part STRING) USING delta PARTITIONED BY (part)")
      sql("CREATE TABLE t_md2 (id INT, part STRING) USING delta PARTITIONED BY (part)")
      insertValues("t_md1", "(1, 'p1'), (2, 'p2'), (3, 'p3')")
      insertValues("t_md2", "(10, 'p1'), (20, 'p2'), (30, 'p3')")
      checkSPJAnswerMatchesV1("SELECT t1.id, t1._metadata.file_name, t2.id, " +
        "t2._metadata.file_size, t1.part FROM t_md1 t1 JOIN t_md2 t2 ON t1.part = t2.part")
    }
  }

  for (useMetadataRowIndex <- Seq(true, false)) {
    test(s"_metadata.row_index on a Deletion Vector table - " +
        s"useMetadataRowIndex=$useMetadataRowIndex") {
      withSQLConf(
        DeltaSQLConf.DELETION_VECTORS_USE_METADATA_ROW_INDEX.key -> useMetadataRowIndex.toString) {
        withTable("t_dv1") {
          createDVTable("t_dv1")
          insertValues("t_dv1", "(1, 'a', 'p1'), (2, 'b', 'p1'), (3, 'c', 'p1'), " +
            "(4, 'd', 'p2'), (5, 'e', 'p2')")
          sql("DELETE FROM t_dv1 WHERE id IN (2, 4)")
          assert(numFilesWithDVs("t_dv1") == 2)
          checkMatchesV1(sql("SELECT id, part, _metadata.row_index FROM t_dv1"))
          checkMatchesV1(sql("SELECT id, _metadata.row_index, _metadata.file_name FROM t_dv1"))
          withSPJConf(enabled = true) {
            checkAnswer(sql("SELECT id, _metadata.row_index FROM t_dv1"),
              Seq(Row(1, 0L), Row(3, 2L), Row(5, 1L)))
          }
        }
      }
    }
  }

  for {
    cmMode <- Seq("none", "name")
    withDVs <- Seq(false, true)
  } test(s"Row tracking _metadata fields read through the V2 scan match V1 - " +
      s"columnMapping=$cmMode, deletionVectors=$withDVs") {
    withTable("t_rt") {
      sql("CREATE TABLE t_rt (id INT, v STRING, part STRING) USING delta PARTITIONED BY (part) " +
        "TBLPROPERTIES ('delta.enableRowTracking' = 'true', " +
        s"'delta.columnMapping.mode' = '$cmMode', 'delta.enableDeletionVectors' = '$withDVs')")
      insertValues("t_rt", "(1, 'a', 'p1'), (2, 'b', 'p1'), (3, 'c', 'p2')")
      insertValues("t_rt", "(4, 'd', 'p2'), (5, 'e', 'p3')")

      val query = "SELECT id, v, part, _metadata.row_id, _metadata.row_commit_version, " +
        "_metadata.base_row_id, _metadata.default_row_commit_version, _metadata.row_index " +
        "FROM t_rt"
      // Before any update, row IDs are computed from base_row_id + row_index.
      checkMatchesV1(sql(query))
      // After an UPDATE, the rewritten rows carry materialized row IDs and commit versions.
      sql("UPDATE t_rt SET v = 'updated' WHERE id IN (1, 4)")
      sql("DELETE FROM t_rt WHERE id = 5")
      if (withDVs) assert(numFilesWithDVs("t_rt") > 0)
      checkMatchesV1(sql(query))
      checkMatchesV1(sql("SELECT id, _metadata.row_id FROM t_rt"))
      checkMatchesV1(sql("SELECT _metadata FROM t_rt"))
      // Row IDs are stable across the UPDATE.
      withSPJConf(enabled = true) {
        val rowIds = sql("SELECT id, _metadata.row_id FROM t_rt").collect()
        assert(rowIds.map(_.getLong(1)).distinct.length == rowIds.length)
      }
    }
  }

  test("_metadata on an unpartitioned table with SPJ enabled still uses V1") {
    withTable("t_unpart") {
      sql("CREATE TABLE t_unpart (id INT, v STRING) USING delta")
      sql("INSERT INTO t_unpart VALUES (1, 'a'), (2, 'b')")
      checkMatchesV1(sql("SELECT id, _metadata.file_name FROM t_unpart"), expectV2 = false)
    }
  }

  test("Change data feed reads work with SPJ enabled") {
    withTable("t_cdf") {
      sql("CREATE TABLE t_cdf (id INT, part STRING) USING delta PARTITIONED BY (part) " +
        "TBLPROPERTIES ('delta.enableChangeDataFeed' = 'true')")
      sql("INSERT INTO t_cdf VALUES (1, 'p1'), (2, 'p2')")
      sql("DELETE FROM t_cdf WHERE id = 2")
      checkMatchesV1(
        spark.read.option("readChangeFeed", "true").option("startingVersion", "0")
          .table("t_cdf").select("id", "part", "_change_type", "_commit_version"),
        expectV2 = false)
      checkMatchesV1(
        sql("SELECT id, part, _change_type FROM table_changes('t_cdf', 0)"),
        expectV2 = false)
    }
  }

  for (withDVs <- Seq(false, true)) {
    test(s"DML filtering on _metadata behaves the same with SPJ enabled as with V1 - " +
        s"deletionVectors=$withDVs") {
      // V1 rejects some of these statements (e.g. when it writes the rewritten files, as
      // `_metadata` is not a table column). With SPJ enabled, the `_metadata` references
      // resolved on the V2 target must be moved to the V1 target
      // (`DeltaRelation.toV1WithMetadataReferences`) to get the same outcome instead of an
      // internal "missing attribute" error.
      // Runs each statement on a fresh table and returns its outcome: the error condition, or the
      // resulting table contents.
      def outcomes(spjEnabled: Boolean): Seq[String] = withSPJConf(spjEnabled) {
        Seq(
          "UPDATE t_md SET v = 'updated' WHERE _metadata.file_name = '<file>'",
          "DELETE FROM t_md WHERE _metadata.file_name = '<file>'",
          "MERGE INTO t_md t USING (SELECT 3 AS id) s ON t.id = s.id " +
            "AND t._metadata.file_name = '<file>' WHEN MATCHED THEN UPDATE SET v = 'merged'"
        ).map { statement =>
          var outcome = ""
          withTable("t_md") {
            sql("CREATE TABLE t_md (id INT, v STRING, part STRING) USING delta " +
              s"PARTITIONED BY (part) TBLPROPERTIES ('delta.enableDeletionVectors' = '$withDVs')")
            insertValues("t_md", "(1, 'a', 'p1'), (2, 'b', 'p2')")
            insertValues("t_md", "(3, 'c', 'p1'), (4, 'd', 'p1')")
            val file =
              sql("SELECT _metadata.file_name FROM t_md WHERE id = 3").head().getString(0)
            outcome = try {
              sql(statement.replace("<file>", file))
              sql("SELECT id, v FROM t_md ORDER BY id").collect().mkString(",")
            } catch {
              case e: SparkThrowable => e.getCondition
            }
          }
          outcome
        }
      }
      assert(outcomes(spjEnabled = true) === outcomes(spjEnabled = false))
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
        val query = spark.sql(
          "SELECT t1.id, t2.id, t1.part FROM t_cm1 t1 JOIN t_cm2 t2 ON t1.part = t2.part")
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
        // Equality join on tables containing NULL partition values should still avoid shuffle
        val query = spark.sql(
          "SELECT t1.id, t2.id, t1.part FROM t_null1 t1 JOIN t_null2 t2 ON t1.part = t2.part")
        checkAnswer(query, Seq((1, 10, "p1")).toDF("id1", "id2", "part"))
        assertSPJPlan(query, expectSPJ = true)
      }
    }
  }
}
