/*
 * Copyright (2021) The Delta Lake Project Authors.
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

import org.apache.spark.sql.delta.test.DeltaSQLCommandTest

import org.apache.spark.sql.QueryTest
import org.apache.spark.sql.connector.catalog.Table
import org.apache.spark.sql.test.SharedSparkSession

/**
 * Test suite for DeltaTableV2.partitioning() method with column mapping support.
 * Verifies that partition information is correctly reported for storage-partitioned operations.
 */
class DeltaPartitioningSuite extends QueryTest
  with SharedSparkSession
  with DeltaSQLCommandTest {

  import testImplicits._

  /**
   * Helper method to get DeltaTableV2 instance from table name.
   */
  private def getTableV2(tableName: String): Table = {
    val catalog = spark.sessionState.catalogManager.currentCatalog
    val ident = org.apache.spark.sql.connector.catalog.Identifier.of(Array.empty[String], tableName)
    catalog.asInstanceOf[org.apache.spark.sql.connector.catalog.TableCatalog]
      .loadTable(ident)
  }

  test("partitioning() returns correct transforms for partitioned table") {
    withTable("partitioned_table") {
      // Create table partitioned by date
      sql(
        """
          |CREATE TABLE partitioned_table (id INT, name STRING, date DATE)
          |USING delta
          |PARTITIONED BY (date)
        """.stripMargin)

      // Load table as DeltaTableV2
      val table = getTableV2("partitioned_table")

      // Get partitioning transforms
      val transforms = table.partitioning()

      // Verify we have partition information
      assert(transforms.length == 1,
        s"Expected 1 partition transform but got ${transforms.length}")
      assert(transforms(0).toString.contains("date"),
        s"Expected partition on 'date' but got '${transforms(0)}'")
    }
  }

  test("partitioning() returns empty array for non-partitioned table") {
    withTable("non_partitioned_table") {
      // Create table WITHOUT partitioning
      sql(
        """
          |CREATE TABLE non_partitioned_table (id INT, name STRING, date DATE)
          |USING delta
        """.stripMargin)

      val table = getTableV2("non_partitioned_table")
      val transforms = table.partitioning()

      // Verify empty partitioning
      assert(transforms.isEmpty,
        s"Expected no partitioning but got ${transforms.length} transforms")
    }
  }

  test("partitioning() handles multi-column partitioning") {
    withTable("multi_partition_table") {
      // Create table partitioned by multiple columns
      sql(
        """
          |CREATE TABLE multi_partition_table (
          |  id INT,
          |  value INT,
          |  date DATE,
          |  region STRING
          |)
          |USING delta
          |PARTITIONED BY (date, region)
        """.stripMargin)

      val table = getTableV2("multi_partition_table")
      val transforms = table.partitioning()

      // Verify both partition columns are present
      assert(transforms.length == 2,
        s"Expected 2 partition transforms but got ${transforms.length}")
      val transformStrs = transforms.map(_.toString)
      assert(transformStrs.exists(_.contains("date")),
        "Expected partition on 'date'")
      assert(transformStrs.exists(_.contains("region")),
        "Expected partition on 'region'")
    }
  }

  test("partitioning() works with column mapping enabled") {
    withTable("column_mapped_table") {
      // Create table with column mapping
      sql(
        """
          |CREATE TABLE column_mapped_table (id INT, date DATE)
          |USING delta
          |PARTITIONED BY (date)
          |TBLPROPERTIES (
          |  'delta.columnMapping.mode' = 'name',
          |  'delta.minReaderVersion' = '2',
          |  'delta.minWriterVersion' = '5'
          |)
        """.stripMargin)

      val table = getTableV2("column_mapped_table")
      val transforms = table.partitioning()

      // Should have partition information (using physical column names internally)
      assert(transforms.length == 1,
        "Column-mapped table should have 1 partition transform")

      // The transform should reference a column (physical name will be different)
      assert(transforms(0).toString.nonEmpty,
        "Partition transform should not be empty for column-mapped table")
    }
  }

  test("partitioned join query executes successfully") {
    withTable("sales", "inventory") {
      // Create two tables, both partitioned by date
      sql(
        """
          |CREATE TABLE sales (product_id INT, quantity INT, date DATE)
          |USING delta
          |PARTITIONED BY (date)
        """.stripMargin)

      sql(
        """
          |CREATE TABLE inventory (product_id INT, stock INT, date DATE)
          |USING delta
          |PARTITIONED BY (date)
        """.stripMargin)

      // Insert test data
      sql("INSERT INTO sales VALUES (1, 100, DATE '2024-01-01'), (2, 200, DATE '2024-01-02')")
      sql(
        "INSERT INTO inventory VALUES (1, 50, DATE '2024-01-01'), (2, 150, DATE '2024-01-02')")

      // Perform join on partition key (date)
      val df = sql(
        """
          |SELECT s.product_id, s.quantity, i.stock
          |FROM sales s
          |JOIN inventory i ON s.date = i.date AND s.product_id = i.product_id
        """.stripMargin)

      // Verify correct results
      val results = df.collect()
      assert(results.length == 2, s"Expected 2 results but got ${results.length}")

      // Note: Whether shuffle is eliminated depends on Spark's optimizer and
      // the actual data distribution. This test just verifies correctness.
    }
  }
}
