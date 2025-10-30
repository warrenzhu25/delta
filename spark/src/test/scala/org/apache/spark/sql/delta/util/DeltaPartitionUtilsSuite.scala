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

package org.apache.spark.sql.delta.util

import org.apache.spark.sql.delta.actions.AddFile
import org.apache.spark.sql.delta.test.DeltaSQLCommandTest
import org.apache.spark.sql.types.{IntegerType, StringType, StructField, StructType}

import org.apache.spark.SparkFunSuite

class DeltaPartitionUtilsSuite extends SparkFunSuite with DeltaSQLCommandTest {

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
    assert(groupingKey.fields(0).dataType == IntegerType)
    assert(groupingKey.fields(1).name == "month")
    assert(groupingKey.fields(1).dataType == IntegerType)
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

  test("groupingKeyType with case insensitive partition column names") {
    val schema = StructType(Seq(
      StructField("id", IntegerType),
      StructField("Date", StringType),
      StructField("value", IntegerType)
    ))
    val partitionColumns = Seq("date")

    val groupingKey = DeltaPartitionUtils.groupingKeyType(schema, partitionColumns)

    assert(groupingKey.fields.length == 1)
    assert(groupingKey.fields(0).name == "Date")
    assert(groupingKey.fields(0).dataType == StringType)
  }

  test("isValidForStoragePartitionedJoin with partitions") {
    assert(DeltaPartitionUtils.isValidForStoragePartitionedJoin(Seq("date")))
    assert(DeltaPartitionUtils.isValidForStoragePartitionedJoin(Seq("year", "month")))
  }

  test("isValidForStoragePartitionedJoin without partitions") {
    assert(!DeltaPartitionUtils.isValidForStoragePartitionedJoin(Seq.empty))
  }

  test("partitionValuesToRow extracts values correctly") {
    val partitionSchema = StructType(Seq(
      StructField("date", StringType),
      StructField("country", StringType)
    ))

    val addFile = AddFile(
      path = "date=2023-01-01/country=US/file1.parquet",
      partitionValues = Map("date" -> "2023-01-01", "country" -> "US"),
      size = 1000,
      modificationTime = 1234567890,
      dataChange = true
    )

    val row = DeltaPartitionUtils.partitionValuesToRow(addFile, partitionSchema, spark)

    assert(row.numFields == 2)
    assert(row.getString(0) == "2023-01-01")
    assert(row.getString(1) == "US")
  }

  test("partitionValuesToRow handles missing partition values") {
    val partitionSchema = StructType(Seq(
      StructField("date", StringType),
      StructField("country", StringType)
    ))

    val addFile = AddFile(
      path = "date=2023-01-01/file1.parquet",
      partitionValues = Map("date" -> "2023-01-01"),
      size = 1000,
      modificationTime = 1234567890,
      dataChange = true
    )

    val row = DeltaPartitionUtils.partitionValuesToRow(addFile, partitionSchema, spark)

    assert(row.numFields == 2)
    assert(row.getString(0) == "2023-01-01")
    assert(row.isNullAt(1))
  }
}
