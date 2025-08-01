/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.expressions

import java.sql.{Date, Timestamp}
import java.time.{Duration, LocalDate, LocalDateTime, LocalTime, Period}
import java.time.temporal.ChronoUnit
import java.util.{Calendar, Locale, TimeZone}

import org.apache.spark.{SparkFunSuite, SparkIllegalArgumentException}
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.TypeCheckResult.DataTypeMismatch
import org.apache.spark.sql.catalyst.expressions.Cast._
import org.apache.spark.sql.catalyst.expressions.codegen.CodegenContext
import org.apache.spark.sql.catalyst.util.DateTimeConstants._
import org.apache.spark.sql.catalyst.util.DateTimeTestUtils._
import org.apache.spark.sql.catalyst.util.DateTimeUtils._
import org.apache.spark.sql.catalyst.util.IntervalUtils
import org.apache.spark.sql.catalyst.util.IntervalUtils.microsToDuration
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.sql.types.DataTypeTestUtils.{dayTimeIntervalTypes, yearMonthIntervalTypes}
import org.apache.spark.sql.types.DayTimeIntervalType.{DAY, HOUR, MINUTE, SECOND}
import org.apache.spark.sql.types.UpCastRule.numericPrecedence
import org.apache.spark.sql.types.YearMonthIntervalType.{MONTH, YEAR}
import org.apache.spark.unsafe.types.UTF8String
import org.apache.spark.util.ThreadUtils

/**
 * Common test suite for [[Cast]] with ansi mode on and off. It only includes test cases that work
 * for both ansi on and off.
 */
abstract class CastSuiteBase extends SparkFunSuite with ExpressionEvalHelper {

  protected def evalMode: EvalMode.Value

  protected def cast(v: Any, targetType: DataType, timeZoneId: Option[String] = None): Cast = {
    v match {
      case lit: Expression => Cast(lit, targetType, timeZoneId, evalMode)
      case _ => Cast(Literal(v), targetType, timeZoneId, evalMode)
    }
  }

  // expected cannot be null
  protected def checkCast(v: Any, expected: Any): Unit = {
    checkEvaluation(cast(v, Literal(expected).dataType), expected)
  }

  protected def checkNullCast(from: DataType, to: DataType): Unit = {
    checkEvaluation(cast(Literal.create(null, from), to, UTC_OPT), null)
  }

  protected def verifyCastFailure(c: Cast, expected: DataTypeMismatch): Unit = {
    val typeCheckResult = c.checkInputDataTypes()
    assert(typeCheckResult.isFailure)
    assert(typeCheckResult.isInstanceOf[DataTypeMismatch])
    val mismatch = typeCheckResult.asInstanceOf[DataTypeMismatch]
    assert(mismatch === expected)
  }

  test("null cast") {
    import DataTypeTestUtils._

    atomicTypes.zip(atomicTypes).foreach { case (from, to) =>
      checkNullCast(from, to)
    }

    atomicTypes.foreach(dt => checkNullCast(NullType, dt))
    (atomicTypes ++ timeTypes).foreach(dt => checkNullCast(dt, StringType))
    checkNullCast(StringType, BinaryType)
    checkNullCast(StringType, BooleanType)
    numericTypes.foreach(dt => checkNullCast(dt, BooleanType))

    checkNullCast(StringType, TimestampType)
    checkNullCast(DateType, TimestampType)

    checkNullCast(StringType, DateType)
    checkNullCast(TimestampType, DateType)

    checkNullCast(StringType, CalendarIntervalType)
    numericTypes.foreach(dt => checkNullCast(StringType, dt))
    numericTypes.foreach(dt => checkNullCast(BooleanType, dt))
    for (from <- numericTypes; to <- numericTypes) checkNullCast(from, to)
  }

  test("cast string to date") {
    var c = Calendar.getInstance()
    c.set(12345, 0, 1, 0, 0, 0)
    c.set(Calendar.MILLISECOND, 0)
    checkEvaluation(Cast(Literal("12345"), DateType), new Date(c.getTimeInMillis))
    c.set(12345, 11, 18, 0, 0, 0)
    c.set(Calendar.MILLISECOND, 0)
    checkEvaluation(Cast(Literal("12345-12-18"), DateType), new Date(c.getTimeInMillis))
    c.set(2015, 0, 1, 0, 0, 0)
    c.set(Calendar.MILLISECOND, 0)
    checkEvaluation(Cast(Literal("2015"), DateType), new Date(c.getTimeInMillis))
    c = Calendar.getInstance()
    c.set(2015, 2, 1, 0, 0, 0)
    c.set(Calendar.MILLISECOND, 0)
    checkEvaluation(Cast(Literal("2015-03"), DateType), new Date(c.getTimeInMillis))
    c = Calendar.getInstance()
    c.set(2015, 2, 18, 0, 0, 0)
    c.set(Calendar.MILLISECOND, 0)
    checkEvaluation(Cast(Literal("2015-03-18"), DateType), new Date(c.getTimeInMillis))
    checkEvaluation(Cast(Literal("2015-03-18 "), DateType), new Date(c.getTimeInMillis))
    checkEvaluation(Cast(Literal("2015-03-18 123142"), DateType), new Date(c.getTimeInMillis))
    checkEvaluation(Cast(Literal("2015-03-18T123123"), DateType), new Date(c.getTimeInMillis))
    checkEvaluation(Cast(Literal("2015-03-18T"), DateType), new Date(c.getTimeInMillis))
  }

  test("cast string to timestamp") {
    ThreadUtils.parmap(
      ALL_TIMEZONES,
      prefix = "CastSuiteBase-cast-string-to-timestamp",
      maxThreads = Runtime.getRuntime.availableProcessors
    ) { zid =>
      def checkCastStringToTimestamp(str: String, expected: Timestamp): Unit = {
        checkEvaluation(cast(Literal(str), TimestampType, Option(zid.getId)), expected)
      }

      val tz = TimeZone.getTimeZone(zid)
      var c = Calendar.getInstance(tz)
      c.set(2015, 0, 1, 0, 0, 0)
      c.set(Calendar.MILLISECOND, 0)
      checkCastStringToTimestamp("2015", new Timestamp(c.getTimeInMillis))
      c = Calendar.getInstance(tz)
      c.set(2015, 2, 1, 0, 0, 0)
      c.set(Calendar.MILLISECOND, 0)
      checkCastStringToTimestamp("2015-03", new Timestamp(c.getTimeInMillis))
      c = Calendar.getInstance(tz)
      c.set(2015, 2, 18, 0, 0, 0)
      c.set(Calendar.MILLISECOND, 0)
      checkCastStringToTimestamp("2015-03-18", new Timestamp(c.getTimeInMillis))
      checkCastStringToTimestamp("2015-03-18 ", new Timestamp(c.getTimeInMillis))

      c = Calendar.getInstance(tz)
      c.set(2015, 2, 18, 12, 3, 17)
      c.set(Calendar.MILLISECOND, 0)
      checkCastStringToTimestamp("2015-03-18 12:03:17", new Timestamp(c.getTimeInMillis))
      checkCastStringToTimestamp("2015-03-18T12:03:17", new Timestamp(c.getTimeInMillis))

      // If the string value includes timezone string, it represents the timestamp string
      // in the timezone regardless of the timeZoneId parameter.
      c = Calendar.getInstance(TimeZone.getTimeZone(UTC))
      c.set(2015, 2, 18, 12, 3, 17)
      c.set(Calendar.MILLISECOND, 0)
      checkCastStringToTimestamp("2015-03-18T12:03:17Z", new Timestamp(c.getTimeInMillis))
      checkCastStringToTimestamp("2015-03-18 12:03:17Z", new Timestamp(c.getTimeInMillis))

      c = Calendar.getInstance(TimeZone.getTimeZone("GMT-01:00"))
      c.set(2015, 2, 18, 12, 3, 17)
      c.set(Calendar.MILLISECOND, 0)
      checkCastStringToTimestamp("2015-03-18T12:03:17-1:0", new Timestamp(c.getTimeInMillis))
      checkCastStringToTimestamp("2015-03-18T12:03:17-01:00", new Timestamp(c.getTimeInMillis))

      c = Calendar.getInstance(TimeZone.getTimeZone("GMT+07:30"))
      c.set(2015, 2, 18, 12, 3, 17)
      c.set(Calendar.MILLISECOND, 0)
      checkCastStringToTimestamp("2015-03-18T12:03:17+07:30", new Timestamp(c.getTimeInMillis))

      c = Calendar.getInstance(TimeZone.getTimeZone("GMT+07:03"))
      c.set(2015, 2, 18, 12, 3, 17)
      c.set(Calendar.MILLISECOND, 0)
      checkCastStringToTimestamp("2015-03-18T12:03:17+7:3", new Timestamp(c.getTimeInMillis))

      // tests for the string including milliseconds.
      c = Calendar.getInstance(tz)
      c.set(2015, 2, 18, 12, 3, 17)
      c.set(Calendar.MILLISECOND, 123)
      checkCastStringToTimestamp("2015-03-18 12:03:17.123", new Timestamp(c.getTimeInMillis))
      checkCastStringToTimestamp("2015-03-18T12:03:17.123", new Timestamp(c.getTimeInMillis))

      // If the string value includes timezone string, it represents the timestamp string
      // in the timezone regardless of the timeZoneId parameter.
      c = Calendar.getInstance(TimeZone.getTimeZone(UTC))
      c.set(2015, 2, 18, 12, 3, 17)
      c.set(Calendar.MILLISECOND, 456)
      checkCastStringToTimestamp("2015-03-18T12:03:17.456Z", new Timestamp(c.getTimeInMillis))
      checkCastStringToTimestamp("2015-03-18 12:03:17.456Z", new Timestamp(c.getTimeInMillis))

      c = Calendar.getInstance(TimeZone.getTimeZone("GMT-01:00"))
      c.set(2015, 2, 18, 12, 3, 17)
      c.set(Calendar.MILLISECOND, 123)
      checkCastStringToTimestamp("2015-03-18T12:03:17.123-1:0", new Timestamp(c.getTimeInMillis))
      checkCastStringToTimestamp("2015-03-18T12:03:17.123-01:00", new Timestamp(c.getTimeInMillis))

      c = Calendar.getInstance(TimeZone.getTimeZone("GMT+07:30"))
      c.set(2015, 2, 18, 12, 3, 17)
      c.set(Calendar.MILLISECOND, 123)
      checkCastStringToTimestamp("2015-03-18T12:03:17.123+07:30", new Timestamp(c.getTimeInMillis))

      c = Calendar.getInstance(TimeZone.getTimeZone("GMT+07:03"))
      c.set(2015, 2, 18, 12, 3, 17)
      c.set(Calendar.MILLISECOND, 123)
      checkCastStringToTimestamp("2015-03-18T12:03:17.123+7:3", new Timestamp(c.getTimeInMillis))
    }
  }

  test("cast from boolean") {
    checkEvaluation(cast(true, IntegerType), 1)
    checkEvaluation(cast(false, IntegerType), 0)
    checkEvaluation(cast(true, StringType), "true")
    checkEvaluation(cast(false, StringType), "false")
    checkEvaluation(cast(cast(1, BooleanType), IntegerType), 1)
    checkEvaluation(cast(cast(0, BooleanType), IntegerType), 0)
  }

  test("cast from int") {
    checkCast(0, false)
    checkCast(1, true)
    checkCast(-5, true)
    checkCast(1, 1.toByte)
    checkCast(1, 1.toShort)
    checkCast(1, 1)
    checkCast(1, 1.toLong)
    checkCast(1, 1.0f)
    checkCast(1, 1.0)
    checkCast(123, "123")

    checkEvaluation(cast(123, DecimalType.USER_DEFAULT), Decimal(123))
    checkEvaluation(cast(123, DecimalType(3, 0)), Decimal(123))
    checkEvaluation(cast(1, LongType), 1.toLong)
  }

  test("cast from long") {
    checkCast(0L, false)
    checkCast(1L, true)
    checkCast(-5L, true)
    checkCast(1L, 1.toByte)
    checkCast(1L, 1.toShort)
    checkCast(1L, 1)
    checkCast(1L, 1.toLong)
    checkCast(1L, 1.0f)
    checkCast(1L, 1.0)
    checkCast(123L, "123")

    checkEvaluation(cast(123L, DecimalType.USER_DEFAULT), Decimal(123))
    checkEvaluation(cast(123L, DecimalType(3, 0)), Decimal(123))
  }

  test("cast from float") {
    checkCast(0.0f, false)
    checkCast(0.5f, true)
    checkCast(-5.0f, true)
    checkCast(1.5f, 1.toByte)
    checkCast(1.5f, 1.toShort)
    checkCast(1.5f, 1)
    checkCast(1.5f, 1.toLong)
    checkCast(1.5f, 1.5)
    checkCast(1.5f, "1.5")
  }

  test("cast from double") {
    checkCast(0.0, false)
    checkCast(0.5, true)
    checkCast(-5.0, true)
    checkCast(1.5, 1.toByte)
    checkCast(1.5, 1.toShort)
    checkCast(1.5, 1)
    checkCast(1.5, 1.toLong)
    checkCast(1.5, 1.5f)
    checkCast(1.5, "1.5")
  }

  test("cast from string") {
    assert(!cast("abcdef", StringType).nullable)
    assert(!cast("abcdef", BinaryType).nullable)
    assert(cast("abcdef", BooleanType).nullable)
    assert(cast("abcdef", TimestampType).nullable)
    assert(cast("abcdef", LongType).nullable)
    assert(cast("abcdef", IntegerType).nullable)
    assert(cast("abcdef", ShortType).nullable)
    assert(cast("abcdef", ByteType).nullable)
    assert(cast("abcdef", DecimalType.USER_DEFAULT).nullable)
    assert(cast("abcdef", DecimalType(4, 2)).nullable)
    assert(cast("abcdef", DoubleType).nullable)
    assert(cast("abcdef", FloatType).nullable)
  }

  test("cast from timestamp") {
    val millis = 15 * 1000 + 3
    val seconds = millis * 1000 + 3
    val ts = new Timestamp(millis)
    val tss = new Timestamp(seconds)
    checkEvaluation(cast(ts, ShortType), 15.toShort)
    checkEvaluation(cast(ts, IntegerType), 15)
    checkEvaluation(cast(ts, LongType), 15.toLong)
    checkEvaluation(cast(ts, FloatType), 15.003f)
    checkEvaluation(cast(ts, DoubleType), 15.003)

    checkEvaluation(cast(cast(tss, ShortType), TimestampType),
      fromJavaTimestamp(ts) * MILLIS_PER_SECOND)
    checkEvaluation(cast(cast(tss, IntegerType), TimestampType),
      fromJavaTimestamp(ts) * MILLIS_PER_SECOND)
    checkEvaluation(cast(cast(tss, LongType), TimestampType),
      fromJavaTimestamp(ts) * MILLIS_PER_SECOND)
    checkEvaluation(
      cast(cast(millis.toFloat / MILLIS_PER_SECOND, TimestampType), FloatType),
      millis.toFloat / MILLIS_PER_SECOND)
    checkEvaluation(
      cast(cast(millis.toDouble / MILLIS_PER_SECOND, TimestampType), DoubleType),
      millis.toDouble / MILLIS_PER_SECOND)
    checkEvaluation(
      cast(cast(Decimal(1), TimestampType), DecimalType.SYSTEM_DEFAULT),
      Decimal(1))

    // A test for higher precision than millis
    checkEvaluation(cast(cast(0.000001, TimestampType), DoubleType), 0.000001)
  }

  test("data type casting") {
    val sd = "1970-01-01"
    val d = Date.valueOf(sd)
    val zts = sd + " 00:00:00"
    val sts = sd + " 00:00:02"
    val nts = sts + ".1"
    val ts = withDefaultTimeZone(UTC)(Timestamp.valueOf(nts))

    for (tz <- ALL_TIMEZONES) {
      val timeZoneId = Option(tz.getId)
      var c = Calendar.getInstance(TimeZoneUTC)
      c.set(2015, 2, 8, 2, 30, 0)
      checkEvaluation(
        cast(cast(new Timestamp(c.getTimeInMillis), StringType, timeZoneId),
          TimestampType, timeZoneId),
        millisToMicros(c.getTimeInMillis))
      c = Calendar.getInstance(TimeZoneUTC)
      c.set(2015, 10, 1, 2, 30, 0)
      checkEvaluation(
        cast(cast(new Timestamp(c.getTimeInMillis), StringType, timeZoneId),
          TimestampType, timeZoneId),
        millisToMicros(c.getTimeInMillis))
    }

    checkEvaluation(cast("abdef", StringType), "abdef")
    checkEvaluation(cast("12.65", DecimalType.SYSTEM_DEFAULT), Decimal(12.65))

    checkEvaluation(cast(cast(sd, DateType), StringType), sd)
    checkEvaluation(cast(cast(d, StringType), DateType), 0)
    checkEvaluation(cast(cast(nts, TimestampType, UTC_OPT), StringType, UTC_OPT), nts)
    checkEvaluation(
      cast(cast(ts, StringType, UTC_OPT), TimestampType, UTC_OPT),
      fromJavaTimestamp(ts))

    // all convert to string type to check
    checkEvaluation(
      cast(cast(cast(nts, TimestampType, UTC_OPT), DateType, UTC_OPT), StringType),
      sd)
    checkEvaluation(
      cast(cast(cast(ts, DateType, UTC_OPT), TimestampType, UTC_OPT), StringType, UTC_OPT),
      zts)

    checkEvaluation(cast(cast("abdef", BinaryType), StringType), "abdef")

    checkEvaluation(cast(cast(cast(cast(
      cast(cast("5", ByteType), ShortType), IntegerType), FloatType), DoubleType), LongType),
      5.toLong)

    checkEvaluation(cast("23", DoubleType), 23d)
    checkEvaluation(cast("23", IntegerType), 23)
    checkEvaluation(cast("23", FloatType), 23f)
    checkEvaluation(cast("23", DecimalType.USER_DEFAULT), Decimal(23))
    checkEvaluation(cast("23", ByteType), 23.toByte)
    checkEvaluation(cast("23", ShortType), 23.toShort)
    checkEvaluation(cast(123, IntegerType), 123)

    checkEvaluation(cast(Literal.create(null, IntegerType), ShortType), null)
  }

  test("cast and add") {
    checkEvaluation(Add(Literal(23d), cast(true, DoubleType)), 24d)
    checkEvaluation(Add(Literal(23), cast(true, IntegerType)), 24)
    checkEvaluation(Add(Literal(23f), cast(true, FloatType)), 24f)
    checkEvaluation(Add(Literal(Decimal(23)), cast(true, DecimalType.USER_DEFAULT)), Decimal(24))
    checkEvaluation(Add(Literal(23.toByte), cast(true, ByteType)), 24.toByte)
    checkEvaluation(Add(Literal(23.toShort), cast(true, ShortType)), 24.toShort)
  }

  test("from decimal") {
    checkCast(Decimal(0.0), false)
    checkCast(Decimal(0.5), true)
    checkCast(Decimal(-5.0), true)
    checkCast(Decimal(1.5), 1.toByte)
    checkCast(Decimal(1.5), 1.toShort)
    checkCast(Decimal(1.5), 1)
    checkCast(Decimal(1.5), 1.toLong)
    checkCast(Decimal(1.5), 1.5f)
    checkCast(Decimal(1.5), 1.5)
    checkCast(Decimal(1.5), "1.5")
  }

  test("cast from array") {
    val array = Literal.create(Seq("123", "true", "f", null),
      ArrayType(StringType, containsNull = true))
    val array_notNull = Literal.create(Seq("123", "true", "f"),
      ArrayType(StringType, containsNull = false))

    checkNullCast(ArrayType(StringType), ArrayType(IntegerType))

    {
      val array = Literal.create(Seq.empty, ArrayType(NullType, containsNull = false))
      val ret = cast(array, ArrayType(IntegerType, containsNull = false))
      assert(ret.resolved)
      checkEvaluation(ret, Seq.empty)
    }

    {
      val ret = cast(array, ArrayType(BooleanType, containsNull = false))
      assert(ret.resolved === false)
    }

    {
      val ret = cast(array, IntegerType)
      assert(ret.resolved === false)
    }
  }

  test("cast from map") {
    val map = Literal.create(
      Map("a" -> "123", "b" -> "true", "c" -> "f", "d" -> null),
      MapType(StringType, StringType, valueContainsNull = true))
    val map_notNull = Literal.create(
      Map("a" -> "123", "b" -> "true", "c" -> "f"),
      MapType(StringType, StringType, valueContainsNull = false))

    checkNullCast(MapType(StringType, IntegerType), MapType(StringType, StringType))

    {
      val ret = cast(map, MapType(StringType, BooleanType, valueContainsNull = false))
      assert(ret.resolved === false)
    }

    {
      val ret = cast(map, IntegerType)
      assert(ret.resolved === false)
    }
  }

  test("cast from struct") {
    checkNullCast(
      StructType(Seq(
        StructField("a", StringType),
        StructField("b", IntegerType))),
      StructType(Seq(
        StructField("a", StringType),
        StructField("b", StringType))))

    val struct = Literal.create(
      InternalRow(
        UTF8String.fromString("123"),
        UTF8String.fromString("true"),
        UTF8String.fromString("f"),
        null),
      StructType(Seq(
        StructField("a", StringType, nullable = true),
        StructField("b", StringType, nullable = true),
        StructField("c", StringType, nullable = true),
        StructField("d", StringType, nullable = true))))
    val struct_notNull = Literal.create(
      InternalRow(
        UTF8String.fromString("123"),
        UTF8String.fromString("true"),
        UTF8String.fromString("f")),
      StructType(Seq(
        StructField("a", StringType, nullable = false),
        StructField("b", StringType, nullable = false),
        StructField("c", StringType, nullable = false))))

    {
      val ret = cast(struct, StructType(Seq(
        StructField("a", BooleanType, nullable = true),
        StructField("b", BooleanType, nullable = true),
        StructField("c", BooleanType, nullable = false),
        StructField("d", BooleanType, nullable = true))))
      assert(ret.resolved === false)
    }

    {
      val ret = cast(struct, StructType(Seq(
        StructField("a", StringType, nullable = true),
        StructField("b", StringType, nullable = true),
        StructField("c", StringType, nullable = true))))
      assert(ret.resolved === false)
    }
    {
      val ret = cast(struct, IntegerType)
      assert(ret.resolved === false)
    }
  }

  test("cast struct with a timestamp field") {
    val originalSchema = new StructType().add("tsField", TimestampType, nullable = false)
    // nine out of ten times I'm casting a struct, it's to normalize its fields nullability
    val targetSchema = new StructType().add("tsField", TimestampType, nullable = true)

    val inp = Literal.create(InternalRow(0L), originalSchema)
    val expected = InternalRow(0L)
    checkEvaluation(cast(inp, targetSchema), expected)
  }

  test("cast between string and interval") {
    import org.apache.spark.unsafe.types.CalendarInterval

    checkEvaluation(Cast(Literal(""), CalendarIntervalType), null)
    checkEvaluation(Cast(Literal("interval -3 month 1 day 7 hours"), CalendarIntervalType),
      new CalendarInterval(-3, 1, 7 * MICROS_PER_HOUR))
    checkEvaluation(Cast(Literal.create(
      new CalendarInterval(15, 9, -3 * MICROS_PER_HOUR), CalendarIntervalType),
      StringType),
      "1 years 3 months 9 days -3 hours")
    checkEvaluation(Cast(Literal("INTERVAL 1 Second 1 microsecond"), CalendarIntervalType),
      new CalendarInterval(0, 0, 1000001))
    checkEvaluation(Cast(Literal("1 MONTH 1 Microsecond"), CalendarIntervalType),
      new CalendarInterval(1, 0, 1))
  }

  test("cast string to boolean") {
    checkCast("t", true)
    checkCast("true", true)
    checkCast("tRUe", true)
    checkCast("y", true)
    checkCast("yes", true)
    checkCast("1", true)
    checkCast("f", false)
    checkCast("false", false)
    checkCast("FAlsE", false)
    checkCast("n", false)
    checkCast("no", false)
    checkCast("0", false)
  }

  protected def createCastMismatch(
      srcType: DataType,
      targetType: DataType,
      errorSubClass: String,
      extraParams: Map[String, String] = Map.empty): DataTypeMismatch = {
    val baseParams = Map(
      "srcType" -> toSQLType(srcType),
      "targetType" -> toSQLType(targetType)
    )
    DataTypeMismatch(errorSubClass, baseParams ++ extraParams)
  }

  protected def checkInvalidCastFromNumericTypeToDateType(): Unit = {
    val errorSubClass = if (evalMode == EvalMode.LEGACY) {
      "CAST_WITHOUT_SUGGESTION"
    } else {
      "CAST_WITH_FUNC_SUGGESTION"
    }
    val funcParams = if (evalMode == EvalMode.LEGACY) {
      Map.empty[String, String]
    } else {
      Map("functionNames" -> "`DATE_FROM_UNIX_DATE`")
    }
    Seq(1.toByte, 1.toShort, 1, 1L, 1.0.toFloat, 1.0).foreach { testValue =>
      val expectedError =
        createCastMismatch(Literal(testValue).dataType, DateType, errorSubClass, funcParams)
      assert(cast(testValue, DateType).checkInputDataTypes() == expectedError)
    }
  }
  protected def checkInvalidCastFromNumericTypeToTimestampNTZType(): Unit = {
    // All numeric types: `CAST_WITHOUT_SUGGESTION`
    Seq(1.toByte, 1.toShort, 1, 1L, 1.0.toFloat, 1.0).foreach { testValue =>
      val expectedError =
        createCastMismatch(Literal(testValue).dataType, TimestampNTZType, "CAST_WITHOUT_SUGGESTION")
      assert(cast(testValue, TimestampNTZType).checkInputDataTypes() == expectedError)
    }
  }

  test("SPARK-16729 type checking for casting to date type") {
    assert(cast("1234", DateType).checkInputDataTypes().isSuccess)
    assert(cast(new Timestamp(1), DateType).checkInputDataTypes().isSuccess)
    assert(cast(false, DateType).checkInputDataTypes() ==
      DataTypeMismatch(
        errorSubClass = "CAST_WITHOUT_SUGGESTION",
        messageParameters = Map(
          "srcType" -> "\"BOOLEAN\"",
          "targetType" -> "\"DATE\""
        )
      )
    )
    checkInvalidCastFromNumericTypeToDateType()
  }

  test("SPARK-20302 cast with same structure") {
    val from = new StructType()
      .add("a", IntegerType)
      .add("b", new StructType().add("b1", LongType))

    val to = new StructType()
      .add("a1", IntegerType)
      .add("b1", new StructType().add("b11", LongType))

    val input = Row(10, Row(12L))

    checkEvaluation(cast(Literal.create(input, from), to), input)
  }

  test("SPARK-22500: cast for struct should not generate codes beyond 64KB") {
    val N = 25

    val fromInner = new StructType(
      (1 to N).map(i => StructField(s"s$i", DoubleType)).toArray)
    val toInner = new StructType(
      (1 to N).map(i => StructField(s"i$i", IntegerType)).toArray)
    val inputInner = Row.fromSeq((1 to N).map(i => i + 0.5))
    val outputInner = Row.fromSeq((1 to N))
    val fromOuter = new StructType(
      (1 to N).map(i => StructField(s"s$i", fromInner)).toArray)
    val toOuter = new StructType(
      (1 to N).map(i => StructField(s"s$i", toInner)).toArray)
    val inputOuter = Row.fromSeq((1 to N).map(_ => inputInner))
    val outputOuter = Row.fromSeq((1 to N).map(_ => outputInner))
    checkEvaluation(cast(Literal.create(inputOuter, fromOuter), toOuter), outputOuter)
  }

  test("SPARK-22570: Cast should not create a lot of global variables") {
    val ctx = new CodegenContext
    cast("1", IntegerType).genCode(ctx)
    cast("2", LongType).genCode(ctx)
    assert(ctx.inlinedMutableStates.length == 0)
  }

  test("up-cast") {
    def isCastSafe(from: NumericType, to: NumericType): Boolean = (from, to) match {
      case (_, dt: DecimalType) => dt.isWiderThan(from)
      case (dt: DecimalType, _) => dt.isTighterThan(to)
      case _ => numericPrecedence.indexOf(from) <= numericPrecedence.indexOf(to)
    }

    def makeComplexTypes(dt: NumericType, nullable: Boolean): Seq[DataType] = {
      Seq(
        new StructType().add("a", dt, nullable).add("b", dt, nullable),
        ArrayType(dt, nullable),
        MapType(dt, dt, nullable),
        ArrayType(new StructType().add("a", dt, nullable), nullable),
        new StructType().add("a", ArrayType(dt, nullable), nullable)
      )
    }

    import DataTypeTestUtils._
    numericTypes.foreach { from =>
      val (safeTargetTypes, unsafeTargetTypes) = numericTypes.partition(to => isCastSafe(from, to))

      safeTargetTypes.foreach { to =>
        assert(Cast.canUpCast(from, to), s"It should be possible to up-cast $from to $to")

        // If the nullability is compatible, we can up-cast complex types too.
        Seq(true -> true, false -> false, false -> true).foreach { case (fn, tn) =>
          makeComplexTypes(from, fn).zip(makeComplexTypes(to, tn)).foreach {
            case (complexFromType, complexToType) =>
              assert(Cast.canUpCast(complexFromType, complexToType))
          }
        }

        makeComplexTypes(from, true).zip(makeComplexTypes(to, false)).foreach {
          case (complexFromType, complexToType) =>
            assert(!Cast.canUpCast(complexFromType, complexToType))
        }
      }

      unsafeTargetTypes.foreach { to =>
        assert(!Cast.canUpCast(from, to), s"It shouldn't be possible to up-cast $from to $to")
        makeComplexTypes(from, true).zip(makeComplexTypes(to, true)).foreach {
          case (complexFromType, complexToType) =>
            assert(!Cast.canUpCast(complexFromType, complexToType))
        }
      }
    }
    numericTypes.foreach { dt =>
      makeComplexTypes(dt, true).foreach { complexType =>
        assert(!Cast.canUpCast(complexType, StringType))
      }
    }

    atomicTypes.foreach { atomicType =>
      assert(Cast.canUpCast(NullType, atomicType))
    }

    dayTimeIntervalTypes.foreach { from =>
      dayTimeIntervalTypes.foreach { to =>
        assert(Cast.canUpCast(from, to))
      }
    }

    yearMonthIntervalTypes.foreach { from =>
      yearMonthIntervalTypes.foreach { to =>
        assert(Cast.canUpCast(from, to))
      }
    }

    {
      assert(Cast.canUpCast(DateType, TimestampType))
      assert(Cast.canUpCast(DateType, TimestampNTZType))
      assert(Cast.canUpCast(TimestampType, TimestampNTZType))
      assert(Cast.canUpCast(TimestampNTZType, TimestampType))
      assert(Cast.canUpCast(IntegerType, StringType("UTF8_LCASE")))
      assert(Cast.canUpCast(CalendarIntervalType, StringType("UTF8_LCASE")))
      assert(!Cast.canUpCast(TimestampType, DateType))
      assert(!Cast.canUpCast(TimestampNTZType, DateType))
    }
  }

  test("SPARK-40389: canUpCast: return false if casting decimal to integral types can cause" +
    " overflow") {
    Seq(ByteType, ShortType, IntegerType, LongType).foreach { integralType =>
      val decimalType = DecimalType.forType(integralType)
      assert(!Cast.canUpCast(decimalType, integralType))
      assert(Cast.canUpCast(integralType, decimalType))
    }
  }

  test("SPARK-27671: cast from nested null type in struct") {
    import DataTypeTestUtils._

    atomicTypes.foreach { atomicType =>
      val struct = Literal.create(
        InternalRow(null),
        StructType(Seq(StructField("a", NullType, nullable = true))))

      val ret = cast(struct, StructType(Seq(
        StructField("a", atomicType, nullable = true))))
      assert(ret.resolved)
      checkEvaluation(ret, InternalRow(null))
    }
  }

  test("Process Infinity, -Infinity, NaN in case insensitive manner") {
    Seq("inf", "+inf", "infinity", "+infiNity", " infinity ").foreach { value =>
      checkEvaluation(cast(value, FloatType), Float.PositiveInfinity)
    }
    Seq("-infinity", "-infiniTy", "  -infinity  ", "  -inf  ").foreach { value =>
      checkEvaluation(cast(value, FloatType), Float.NegativeInfinity)
    }
    Seq("inf", "+inf", "infinity", "+infiNity", " infinity ").foreach { value =>
      checkEvaluation(cast(value, DoubleType), Double.PositiveInfinity)
    }
    Seq("-infinity", "-infiniTy", "  -infinity  ", "  -inf  ").foreach { value =>
      checkEvaluation(cast(value, DoubleType), Double.NegativeInfinity)
    }
    Seq("nan", "nAn", " nan ").foreach { value =>
      checkEvaluation(cast(value, FloatType), Float.NaN)
    }
    Seq("nan", "nAn", " nan ").foreach { value =>
      checkEvaluation(cast(value, DoubleType), Double.NaN)
    }
  }

  test("SPARK-22825 Cast array to string") {
    val ret1 = cast(Literal.create(Array(1, 2, 3, 4, 5)), StringType)
    checkEvaluation(ret1, "[1, 2, 3, 4, 5]")
    val ret2 = cast(Literal.create(Array("ab", "cde", "f")), StringType)
    checkEvaluation(ret2, "[ab, cde, f]")
    Seq(false, true).foreach { omitNull =>
      withSQLConf(SQLConf.LEGACY_COMPLEX_TYPES_TO_STRING.key -> omitNull.toString) {
        val ret3 = cast(Literal.create(Array("ab", null, "c")), StringType)
        checkEvaluation(ret3, s"[ab,${if (omitNull) "" else " null"}, c]")
      }
    }
    val ret4 =
      cast(Literal.create(Array("ab".getBytes, "cde".getBytes, "f".getBytes)), StringType)
    checkEvaluation(ret4, "[ab, cde, f]")
    val ret5 = cast(
      Literal.create(Array("2014-12-03", "2014-12-04", "2014-12-06").map(Date.valueOf)),
      StringType)
    checkEvaluation(ret5, "[2014-12-03, 2014-12-04, 2014-12-06]")
    val ret6 = cast(
      Literal.create(Array("2014-12-03 13:01:00", "2014-12-04 15:05:00")
        .map(Timestamp.valueOf)),
      StringType)
    checkEvaluation(ret6, "[2014-12-03 13:01:00, 2014-12-04 15:05:00]")
    val ret7 = cast(Literal.create(Array(Array(1, 2, 3), Array(4, 5))), StringType)
    checkEvaluation(ret7, "[[1, 2, 3], [4, 5]]")
    val ret8 = cast(
      Literal.create(Array(Array(Array("a"), Array("b", "c")), Array(Array("d")))),
      StringType)
    checkEvaluation(ret8, "[[[a], [b, c]], [[d]]]")
  }

  test("SPARK-33291: Cast array with null elements to string") {
    Seq(false, true).foreach { omitNull =>
      withSQLConf(SQLConf.LEGACY_COMPLEX_TYPES_TO_STRING.key -> omitNull.toString) {
        val ret1 = cast(Literal.create(Array(null, null)), StringType)
        checkEvaluation(
          ret1,
          s"[${if (omitNull) "" else "null"},${if (omitNull) "" else " null"}]")
      }
    }
  }

  test("SPARK-22973 Cast map to string") {
    Seq(
      false -> ("{", "}"),
      true -> ("[", "]")).foreach { case (legacyCast, (lb, rb)) =>
      withSQLConf(SQLConf.LEGACY_COMPLEX_TYPES_TO_STRING.key -> legacyCast.toString) {
        val ret1 = cast(Literal.create(Map(1 -> "a", 2 -> "b", 3 -> "c")), StringType)
        checkEvaluation(ret1, s"${lb}1 -> a, 2 -> b, 3 -> c$rb")
        val ret2 = cast(
          Literal.create(Map("1" -> null, "2" -> "a".getBytes, "3" -> null, "4" -> "c".getBytes)),
          StringType)
        val nullStr = if (legacyCast) "" else " null"
        checkEvaluation(ret2, s"${lb}1 ->$nullStr, 2 -> a, 3 ->$nullStr, 4 -> c$rb")
        val ret3 = cast(
          Literal.create(Map(
            1 -> Date.valueOf("2014-12-03"),
            2 -> Date.valueOf("2014-12-04"),
            3 -> Date.valueOf("2014-12-05"))),
          StringType)
        checkEvaluation(ret3, s"${lb}1 -> 2014-12-03, 2 -> 2014-12-04, 3 -> 2014-12-05$rb")
        val ret4 = cast(
          Literal.create(Map(
            1 -> Timestamp.valueOf("2014-12-03 13:01:00"),
            2 -> Timestamp.valueOf("2014-12-04 15:05:00"))),
          StringType)
        checkEvaluation(ret4, s"${lb}1 -> 2014-12-03 13:01:00, 2 -> 2014-12-04 15:05:00$rb")
        val ret5 = cast(
          Literal.create(Map(
            1 -> Array(1, 2, 3),
            2 -> Array(4, 5, 6))),
          StringType)
        checkEvaluation(ret5, s"${lb}1 -> [1, 2, 3], 2 -> [4, 5, 6]$rb")
      }
    }
  }

  test("SPARK-22981 Cast struct to string") {
    Seq(
      false -> ("{", "}"),
      true -> ("[", "]")).foreach { case (legacyCast, (lb, rb)) =>
      withSQLConf(SQLConf.LEGACY_COMPLEX_TYPES_TO_STRING.key -> legacyCast.toString) {
        val ret1 = cast(Literal.create((1, "a", 0.1)), StringType)
        checkEvaluation(ret1, s"${lb}1, a, 0.1$rb")
        val ret2 = cast(Literal.create(Tuple3[Int, String, String](1, null, "a")), StringType)
        checkEvaluation(ret2, s"${lb}1,${if (legacyCast) "" else " null"}, a$rb")
        val ret3 = cast(Literal.create(
          (Date.valueOf("2014-12-03"), Timestamp.valueOf("2014-12-03 15:05:00"))), StringType)
        checkEvaluation(ret3, s"${lb}2014-12-03, 2014-12-03 15:05:00$rb")
        val ret4 = cast(Literal.create(((1, "a"), 5, 0.1)), StringType)
        checkEvaluation(ret4, s"$lb${lb}1, a$rb, 5, 0.1$rb")
        val ret5 = cast(Literal.create((Seq(1, 2, 3), "a", 0.1)), StringType)
        checkEvaluation(ret5, s"$lb[1, 2, 3], a, 0.1$rb")
        val ret6 = cast(Literal.create((1, Map(1 -> "a", 2 -> "b", 3 -> "c"))), StringType)
        checkEvaluation(ret6, s"${lb}1, ${lb}1 -> a, 2 -> b, 3 -> c$rb$rb")
      }
    }
  }

  test("SPARK-33291: Cast struct with null elements to string") {
    Seq(
      false -> ("{", "}"),
      true -> ("[", "]")).foreach { case (legacyCast, (lb, rb)) =>
      withSQLConf(SQLConf.LEGACY_COMPLEX_TYPES_TO_STRING.key -> legacyCast.toString) {
        val ret1 = cast(Literal.create(Tuple2[String, String](null, null)), StringType)
        checkEvaluation(
          ret1,
          s"$lb${if (legacyCast) "" else "null"},${if (legacyCast) "" else " null"}$rb")
      }
    }
  }

  test("SPARK-34667: cast year-month interval to string") {
    Seq(
      Period.ofMonths(0) -> "0-0",
      Period.ofMonths(1) -> "0-1",
      Period.ofMonths(-1) -> "-0-1",
      Period.ofYears(1) -> "1-0",
      Period.ofYears(-1) -> "-1-0",
      Period.ofYears(10).plusMonths(10) -> "10-10",
      Period.ofYears(-123).minusMonths(6) -> "-123-6",
      Period.ofMonths(Int.MaxValue) -> "178956970-7",
      Period.ofMonths(Int.MinValue) -> "-178956970-8"
    ).foreach { case (period, intervalPayload) =>
      checkEvaluation(
        Cast(Literal(period), StringType),
        s"INTERVAL '$intervalPayload' YEAR TO MONTH")
    }

    yearMonthIntervalTypes.foreach { it =>
      checkConsistencyBetweenInterpretedAndCodegen(
        (child: Expression) => Cast(child, StringType), it)
    }
  }

  test("SPARK-34668: cast day-time interval to string") {
    Seq(
      Duration.ZERO -> "0 00:00:00",
      Duration.of(1, ChronoUnit.MICROS) -> "0 00:00:00.000001",
      Duration.ofMillis(-1) -> "-0 00:00:00.001",
      Duration.ofMillis(1234) -> "0 00:00:01.234",
      Duration.ofSeconds(-9).minus(999999, ChronoUnit.MICROS) -> "-0 00:00:09.999999",
      Duration.ofMinutes(30).plusMillis(59010) -> "0 00:30:59.01",
      Duration.ofHours(-23).minusSeconds(59) -> "-0 23:00:59",
      Duration.ofDays(1).plus(12345678, ChronoUnit.MICROS) -> "1 00:00:12.345678",
      Duration.ofDays(-1234).minusHours(23).minusMinutes(59).minusSeconds(59).minusMillis(999) ->
        "-1234 23:59:59.999",
      microsToDuration(Long.MaxValue) -> "106751991 04:00:54.775807",
      microsToDuration(Long.MinValue + 1) -> "-106751991 04:00:54.775807",
      microsToDuration(Long.MinValue) -> "-106751991 04:00:54.775808"
    ).foreach { case (period, intervalPayload) =>
      checkEvaluation(
        Cast(Literal(period), StringType),
        s"INTERVAL '$intervalPayload' DAY TO SECOND")
    }

    dayTimeIntervalTypes.foreach { it =>
      checkConsistencyBetweenInterpretedAndCodegen((child: Expression) =>
        Cast(child, StringType), it)
    }
  }

  private val specialTs = Seq(
    "0001-01-01T00:00:00", // the fist timestamp of Common Era
    "1582-10-15T23:59:59", // the cutover date from Julian to Gregorian calendar
    "1970-01-01T00:00:00", // the epoch timestamp
    "9999-12-31T23:59:59"  // the last supported timestamp according to SQL standard
  )

  test("SPARK-35698: cast timestamp without time zone to string") {
    specialTs.foreach { s =>
      checkEvaluation(cast(LocalDateTime.parse(s), StringType), s.replace("T", " "))
    }
  }

  test("SPARK-35711: cast timestamp without time zone to timestamp with local time zone") {
    outstandingZoneIds.foreach { zoneId =>
      withDefaultTimeZone(zoneId) {
        specialTs.foreach { s =>
          val input = LocalDateTime.parse(s)
          val expectedTs = Timestamp.valueOf(s.replace("T", " "))
          checkEvaluation(cast(input, TimestampType), expectedTs)
        }
      }
    }
  }

  test("SPARK-35716: cast timestamp without time zone to date type") {
    specialTs.foreach { s =>
      val dt = LocalDateTime.parse(s)
      checkEvaluation(cast(dt, DateType), LocalDate.parse(s.split("T")(0)))
    }
  }

  test("SPARK-35718: cast date type to timestamp without timezone") {
    specialTs.foreach { s =>
      val inputDate = LocalDate.parse(s.split("T")(0))
      // The hour/minute/second of the expect result should be 0
      val expectedTs = LocalDateTime.parse(s.split("T")(0) + "T00:00:00")
      checkEvaluation(cast(inputDate, TimestampNTZType), expectedTs)
    }
  }

  test("SPARK-35719: cast timestamp with local time zone to timestamp without timezone") {
    outstandingZoneIds.foreach { zoneId =>
      withDefaultTimeZone(zoneId) {
        specialTs.foreach { s =>
          val input = Timestamp.valueOf(s.replace("T", " "))
          val expectedTs = LocalDateTime.parse(s)
          checkEvaluation(cast(input, TimestampNTZType), expectedTs)
        }
      }
    }
  }

  test("disallow type conversions between Numeric types and Timestamp without time zone type") {
    import DataTypeTestUtils.numericTypes
    checkInvalidCastFromNumericTypeToTimestampNTZType()
    verifyCastFailure(
      cast(Literal(0L), TimestampNTZType),
      DataTypeMismatch(
        "CAST_WITHOUT_SUGGESTION",
        Map("srcType" -> "\"BIGINT\"", "targetType" -> "\"TIMESTAMP_NTZ\"")))

    val timestampNTZLiteral = Literal.create(LocalDateTime.now(), TimestampNTZType)
    numericTypes.foreach { numericType =>
      verifyCastFailure(
        cast(timestampNTZLiteral, numericType),
        DataTypeMismatch(
          "CAST_WITHOUT_SUGGESTION",
          Map("srcType" -> "\"TIMESTAMP_NTZ\"", "targetType" -> s""""${numericType.sql}"""")))
    }
  }

  test("allow type conversions between calendar interval type and char/varchar types") {
    Seq(CharType(10), VarcharType(10))
      .foreach { typ =>
        assert(cast(Literal.default(CalendarIntervalType), typ).checkInputDataTypes().isSuccess)
    }
  }

  test("SPARK-35720: cast string to timestamp without timezone") {
    specialTs.foreach { s =>
      val expectedTs = LocalDateTime.parse(s)
      checkEvaluation(cast(s, TimestampNTZType), expectedTs)
      // Trim spaces before casting
      checkEvaluation(cast("  " + s + "   ", TimestampNTZType), expectedTs)
      // The result is independent of timezone
      outstandingZoneIds.foreach { zoneId =>
        checkEvaluation(cast(s + zoneId.toString, TimestampNTZType), expectedTs)
        val tsWithMicros = s + ".123456"
        val expectedTsWithNanoSeconds = LocalDateTime.parse(tsWithMicros)
        checkEvaluation(cast(tsWithMicros + zoneId.toString, TimestampNTZType),
          expectedTsWithNanoSeconds)
      }
    }
    // The input string can contain date only
    checkEvaluation(cast("2021-06-17", TimestampNTZType),
      LocalDateTime.of(2021, 6, 17, 0, 0))
  }

  test("SPARK-35112: Cast string to day-time interval") {
    checkEvaluation(cast(Literal.create("0 0:0:0"), DayTimeIntervalType()), 0L)
    checkEvaluation(cast(Literal.create(" interval '0 0:0:0' Day TO second   "),
      DayTimeIntervalType()), 0L)
    checkEvaluation(cast(Literal.create("INTERVAL '1 2:03:04' DAY TO SECOND"),
      DayTimeIntervalType()), 93784000000L)
    checkEvaluation(cast(Literal.create("INTERVAL '1 03:04:00' DAY TO SECOND"),
      DayTimeIntervalType()), 97440000000L)
    checkEvaluation(cast(Literal.create("INTERVAL '1 03:04:00.0000' DAY TO SECOND"),
      DayTimeIntervalType()), 97440000000L)
    checkEvaluation(cast(Literal.create("1 2:03:04"), DayTimeIntervalType()), 93784000000L)
    checkEvaluation(cast(Literal.create("INTERVAL '-10 2:03:04' DAY TO SECOND"),
      DayTimeIntervalType()), -871384000000L)
    checkEvaluation(cast(Literal.create("-10 2:03:04"), DayTimeIntervalType()), -871384000000L)
    checkEvaluation(cast(Literal.create("-106751991 04:00:54.775808"), DayTimeIntervalType()),
      Long.MinValue)
    checkEvaluation(cast(Literal.create("106751991 04:00:54.775807"), DayTimeIntervalType()),
      Long.MaxValue)

    Seq("-106751991 04:00:54.775808", "106751991 04:00:54.775807").foreach { interval =>
      val ansiInterval = s"INTERVAL '$interval' DAY TO SECOND"
      checkEvaluation(
        cast(cast(Literal.create(interval), DayTimeIntervalType()), StringType), ansiInterval)
      checkEvaluation(cast(cast(Literal.create(ansiInterval),
        DayTimeIntervalType()), StringType), ansiInterval)
    }

    Seq("INTERVAL '-106751991 04:00:54.775809' DAY TO SECOND",
      "INTERVAL '106751991 04:00:54.775808' DAY TO SECOND").foreach { interval =>
      checkExceptionInExpression[ArithmeticException](
        cast(Literal.create(interval), DayTimeIntervalType()),
        "long overflow")
    }

    Seq(Byte.MaxValue, Short.MaxValue, Int.MaxValue, Long.MaxValue, Long.MinValue + 1,
      Long.MinValue).foreach { duration =>
      val interval = Literal.create(
        Duration.of(duration, ChronoUnit.MICROS),
        DayTimeIntervalType())
      checkEvaluation(cast(cast(interval, StringType), DayTimeIntervalType()), duration)
    }
  }

  test("SPARK-35111: Cast string to year-month interval") {
    checkEvaluation(cast(Literal.create("INTERVAL '1-0' YEAR TO MONTH"),
      YearMonthIntervalType()), 12)
    checkEvaluation(cast(Literal.create("INTERVAL '-1-0' YEAR TO MONTH"),
      YearMonthIntervalType()), -12)
    checkEvaluation(cast(Literal.create("INTERVAL -'-1-0' YEAR TO MONTH"),
      YearMonthIntervalType()), 12)
    checkEvaluation(cast(Literal.create("INTERVAL +'-1-0' YEAR TO MONTH"),
      YearMonthIntervalType()), -12)
    checkEvaluation(cast(Literal.create("INTERVAL +'+1-0' YEAR TO MONTH"),
      YearMonthIntervalType()), 12)
    checkEvaluation(cast(Literal.create("INTERVAL +'1-0' YEAR TO MONTH"),
      YearMonthIntervalType()), 12)
    checkEvaluation(cast(Literal.create(" interval +'1-0' YEAR  TO MONTH "),
      YearMonthIntervalType()), 12)
    checkEvaluation(cast(Literal.create(" -1-0 "), YearMonthIntervalType()), -12)
    checkEvaluation(cast(Literal.create("-1-0"), YearMonthIntervalType()), -12)
    checkEvaluation(cast(Literal.create(null, StringType), YearMonthIntervalType()), null)

    Seq("0-0", "10-1", "-178956970-7", "178956970-7", "-178956970-8").foreach { interval =>
      val ansiInterval = s"INTERVAL '$interval' YEAR TO MONTH"
      checkEvaluation(
        cast(cast(Literal.create(interval), YearMonthIntervalType()), StringType), ansiInterval)
      checkEvaluation(cast(cast(Literal.create(ansiInterval),
        YearMonthIntervalType()), StringType), ansiInterval)
    }

    Seq("INTERVAL '-178956970-9' YEAR TO MONTH", "INTERVAL '178956970-8' YEAR TO MONTH")
      .foreach { interval =>
        checkErrorInExpression[SparkIllegalArgumentException](
          cast(Literal.create(interval), YearMonthIntervalType()),
          "INVALID_INTERVAL_FORMAT.INTERVAL_PARSING",
          Map(
            "interval" -> "year-month",
            "input" -> interval))
      }

    Seq(Byte.MaxValue, Short.MaxValue, Int.MaxValue, Int.MinValue + 1, Int.MinValue)
      .foreach { period =>
        val interval = Literal.create(Period.ofMonths(period), YearMonthIntervalType())
        checkEvaluation(cast(cast(interval, StringType), YearMonthIntervalType()), period)
      }
  }

  test("SPARK-35820: Support cast DayTimeIntervalType in different fields") {
    val duration = Duration.ofSeconds(12345678L, 123456789)
    Seq((DayTimeIntervalType(DAY, DAY), 12268800000000L, -12268800000000L),
      (DayTimeIntervalType(DAY, HOUR), 12344400000000L, -12344400000000L),
      (DayTimeIntervalType(DAY, MINUTE), 12345660000000L, -12345660000000L),
      (DayTimeIntervalType(DAY, SECOND), 12345678123456L, -12345678123457L),
      (DayTimeIntervalType(HOUR, HOUR), 12344400000000L, -12344400000000L),
      (DayTimeIntervalType(HOUR, MINUTE), 12345660000000L, -12345660000000L),
      (DayTimeIntervalType(HOUR, SECOND), 12345678123456L, -12345678123457L),
      (DayTimeIntervalType(MINUTE, MINUTE), 12345660000000L, -12345660000000L),
      (DayTimeIntervalType(MINUTE, SECOND), 12345678123456L, -12345678123457L),
      (DayTimeIntervalType(SECOND, SECOND), 12345678123456L, -12345678123457L))
      .foreach { case (dt, positive, negative) =>
        checkEvaluation(
          cast(Literal.create(duration, DayTimeIntervalType(DAY, SECOND)), dt), positive)
        checkEvaluation(
          cast(Literal.create(duration.negated(), DayTimeIntervalType(DAY, SECOND)), dt), negative)
      }
  }

  test("SPARK-35819: Support cast YearMonthIntervalType in different fields") {
    val ym = cast(Literal.create("1-1"), YearMonthIntervalType(YEAR, MONTH))
    Seq(YearMonthIntervalType(YEAR) -> 12,
      YearMonthIntervalType(YEAR, MONTH) -> 13,
      YearMonthIntervalType(MONTH) -> 13)
      .foreach { case (dt, value) =>
        checkEvaluation(cast(ym, dt), value)
      }
  }

  test("SPARK-35768: Take into account year-month interval fields in cast") {
    Seq(("1-1", YearMonthIntervalType(YEAR, MONTH), 13),
      ("-1-1", YearMonthIntervalType(YEAR, MONTH), -13))
      .foreach { case (str, dataType, ym) =>
        checkEvaluation(cast(Literal.create(str), dataType), ym)
        checkEvaluation(cast(Literal.create(s"INTERVAL '$str' YEAR TO MONTH"), dataType), ym)
        checkEvaluation(cast(Literal.create(s"INTERVAL -'$str' YEAR TO MONTH"), dataType), -ym)
      }

    Seq(("13", YearMonthIntervalType(YEAR), 13 * 12),
      ("13", YearMonthIntervalType(MONTH), 13),
      ("-13", YearMonthIntervalType(YEAR), -13 * 12),
      ("-13", YearMonthIntervalType(MONTH), -13))
      .foreach { case (str, dataType, value) =>
        checkEvaluation(cast(Literal.create(str), dataType), value)
        if (dataType == YearMonthIntervalType(YEAR)) {
          checkEvaluation(cast(Literal.create(s"INTERVAL '$str' YEAR"), dataType), value)
          checkEvaluation(cast(Literal.create(s"INTERVAL '$str' year"), dataType), value)
        } else {
          checkEvaluation(cast(Literal.create(s"INTERVAL '$str' MONTH"), dataType), value)
          checkEvaluation(cast(Literal.create(s"INTERVAL '$str' month"), dataType), value)
        }
      }

    Seq("INTERVAL '1-1' YEAR", "INTERVAL '1-1' MONTH").foreach { interval =>
      val dataType = YearMonthIntervalType()
      checkErrorInExpression[SparkIllegalArgumentException](
        cast(Literal.create(interval), dataType),
        "INVALID_INTERVAL_FORMAT.UNMATCHED_FORMAT_STRING",
        Map(
          "typeName" -> "interval year to month",
          "intervalStr" -> "year-month",
          "supportedFormat" -> "`[+|-]y-m`, `INTERVAL [+|-]'[+|-]y-m' YEAR TO MONTH`",
          "input" -> interval)
      )
    }
    Seq(("1", YearMonthIntervalType(YEAR, MONTH)),
      ("1", YearMonthIntervalType(YEAR, MONTH)),
      ("1-1", YearMonthIntervalType(YEAR)),
      ("1-1", YearMonthIntervalType(MONTH)),
      ("INTERVAL '1-1' YEAR TO MONTH", YearMonthIntervalType(YEAR)),
      ("INTERVAL '1-1' YEAR TO MONTH", YearMonthIntervalType(MONTH)),
      ("INTERVAL '1' YEAR", YearMonthIntervalType(YEAR, MONTH)),
      ("INTERVAL '1' YEAR", YearMonthIntervalType(MONTH)),
      ("INTERVAL '1' MONTH", YearMonthIntervalType(YEAR)),
      ("INTERVAL '1' MONTH", YearMonthIntervalType(YEAR, MONTH)))
      .foreach { case (interval, dataType) =>
        checkErrorInExpression[SparkIllegalArgumentException](
          cast(Literal.create(interval), dataType),
          "INVALID_INTERVAL_FORMAT.UNMATCHED_FORMAT_STRING",
          Map(
            "typeName" -> dataType.typeName,
            "intervalStr" -> "year-month",
            "supportedFormat" ->
              IntervalUtils.supportedFormat(("year-month", dataType.startField, dataType.endField))
                .map(format => s"`$format`").mkString(", "),
            "input" -> interval))
      }
  }

  test("SPARK-35735: Take into account day-time interval fields in cast") {
    def typeName(dataType: DayTimeIntervalType): String = {
      if (dataType.startField == dataType.endField) {
        DayTimeIntervalType.fieldToString(dataType.startField).toUpperCase(Locale.ROOT)
      } else {
        s"${DayTimeIntervalType.fieldToString(dataType.startField)} TO " +
          s"${DayTimeIntervalType.fieldToString(dataType.endField)}".toUpperCase(Locale.ROOT)
      }
    }

    Seq(("1", DayTimeIntervalType(DAY, DAY), (86400) * MICROS_PER_SECOND),
      ("-1", DayTimeIntervalType(DAY, DAY), -(86400) * MICROS_PER_SECOND),
      ("1 01", DayTimeIntervalType(DAY, HOUR), (86400 + 3600) * MICROS_PER_SECOND),
      ("-1 01", DayTimeIntervalType(DAY, HOUR), -(86400 + 3600) * MICROS_PER_SECOND),
      ("1 01:01", DayTimeIntervalType(DAY, MINUTE), (86400 + 3600 + 60) * MICROS_PER_SECOND),
      ("-1 01:01", DayTimeIntervalType(DAY, MINUTE), -(86400 + 3600 + 60) * MICROS_PER_SECOND),
      ("1 01:01:01.12345", DayTimeIntervalType(DAY, SECOND),
        ((86400 + 3600 + 60 + 1.12345) * MICROS_PER_SECOND).toLong),
      ("-1 01:01:01.12345", DayTimeIntervalType(DAY, SECOND),
        (-(86400 + 3600 + 60 + 1.12345) * MICROS_PER_SECOND).toLong),

      ("01", DayTimeIntervalType(HOUR, HOUR), (3600) * MICROS_PER_SECOND),
      ("-01", DayTimeIntervalType(HOUR, HOUR), -(3600) * MICROS_PER_SECOND),
      ("01:01", DayTimeIntervalType(HOUR, MINUTE), (3600 + 60) * MICROS_PER_SECOND),
      ("-01:01", DayTimeIntervalType(HOUR, MINUTE), -(3600 + 60) * MICROS_PER_SECOND),
      ("01:01:01.12345", DayTimeIntervalType(HOUR, SECOND),
        ((3600 + 60 + 1.12345) * MICROS_PER_SECOND).toLong),
      ("-01:01:01.12345", DayTimeIntervalType(HOUR, SECOND),
        (-(3600 + 60 + 1.12345) * MICROS_PER_SECOND).toLong),

      ("01", DayTimeIntervalType(MINUTE, MINUTE), (60) * MICROS_PER_SECOND),
      ("-01", DayTimeIntervalType(MINUTE, MINUTE), -(60) * MICROS_PER_SECOND),
      ("01:01", DayTimeIntervalType(MINUTE, SECOND), ((60 + 1) * MICROS_PER_SECOND)),
      ("01:01.12345", DayTimeIntervalType(MINUTE, SECOND),
        ((60 + 1.12345) * MICROS_PER_SECOND).toLong),
      ("-01:01.12345", DayTimeIntervalType(MINUTE, SECOND),
        (-(60 + 1.12345) * MICROS_PER_SECOND).toLong),

      ("01.12345", DayTimeIntervalType(SECOND, SECOND), ((1.12345) * MICROS_PER_SECOND).toLong),
      ("-01.12345", DayTimeIntervalType(SECOND, SECOND), (-(1.12345) * MICROS_PER_SECOND).toLong))
      .foreach { case (str, dataType, dt) =>
        checkEvaluation(cast(Literal.create(str), dataType), dt)
        checkEvaluation(
          cast(Literal.create(s"INTERVAL '$str' ${typeName(dataType)}"), dataType), dt)
        checkEvaluation(
          cast(Literal.create(s"INTERVAL -'$str' ${typeName(dataType)}"), dataType), -dt)
      }

    // Check max value
    Seq(("INTERVAL '106751991' DAY", DayTimeIntervalType(DAY), 106751991L * MICROS_PER_DAY),
      ("INTERVAL '106751991 04' DAY TO HOUR", DayTimeIntervalType(DAY, HOUR), 9223372036800000000L),
      ("INTERVAL '106751991 04:00' DAY TO MINUTE",
        DayTimeIntervalType(DAY, MINUTE), 9223372036800000000L),
      ("INTERVAL '106751991 04:00:54.775807' DAY TO SECOND", DayTimeIntervalType(), Long.MaxValue),
      ("INTERVAL '2562047788' HOUR", DayTimeIntervalType(HOUR), 9223372036800000000L),
      ("INTERVAL '2562047788:00' HOUR TO MINUTE",
        DayTimeIntervalType(HOUR, MINUTE), 9223372036800000000L),
      ("INTERVAL '2562047788:00:54.775807' HOUR TO SECOND",
        DayTimeIntervalType(HOUR, SECOND), Long.MaxValue),
      ("INTERVAL '153722867280' MINUTE", DayTimeIntervalType(MINUTE), 9223372036800000000L),
      ("INTERVAL '153722867280:54.775807' MINUTE TO SECOND",
        DayTimeIntervalType(MINUTE, SECOND), Long.MaxValue),
      ("INTERVAL '9223372036854.775807' SECOND", DayTimeIntervalType(SECOND), Long.MaxValue))
      .foreach { case (interval, dataType, dt) =>
        checkEvaluation(cast(Literal.create(interval), dataType), dt)
        checkEvaluation(cast(Literal.create(interval.toLowerCase(Locale.ROOT)), dataType), dt)
      }

    Seq(("INTERVAL '-106751991' DAY", DayTimeIntervalType(DAY), -106751991L * MICROS_PER_DAY),
      ("INTERVAL '-106751991 04' DAY TO HOUR",
        DayTimeIntervalType(DAY, HOUR), -9223372036800000000L),
      ("INTERVAL '-106751991 04:00' DAY TO MINUTE",
        DayTimeIntervalType(DAY, MINUTE), -9223372036800000000L),
      ("INTERVAL '-106751991 04:00:54.775808' DAY TO SECOND", DayTimeIntervalType(), Long.MinValue),
      ("INTERVAL '-2562047788' HOUR", DayTimeIntervalType(HOUR), -9223372036800000000L),
      ("INTERVAL '-2562047788:00' HOUR TO MINUTE",
        DayTimeIntervalType(HOUR, MINUTE), -9223372036800000000L),
      ("INTERVAL '-2562047788:00:54.775808' HOUR TO SECOND",
        DayTimeIntervalType(HOUR, SECOND), Long.MinValue),
      ("INTERVAL '-153722867280' MINUTE", DayTimeIntervalType(MINUTE), -9223372036800000000L),
      ("INTERVAL '-153722867280:54.775808' MINUTE TO SECOND",
        DayTimeIntervalType(MINUTE, SECOND), Long.MinValue),
      ("INTERVAL '-9223372036854.775808' SECOND", DayTimeIntervalType(SECOND), Long.MinValue))
      .foreach { case (interval, dataType, dt) =>
        checkEvaluation(cast(Literal.create(interval), dataType), dt)
      }

    Seq(
      ("INTERVAL '1 01:01:01.12345' DAY TO SECOND", DayTimeIntervalType(DAY, HOUR)),
      ("INTERVAL '1 01:01:01.12345' DAY TO HOUR", DayTimeIntervalType(DAY, SECOND)),
      ("INTERVAL '1 01:01:01.12345' DAY TO MINUTE", DayTimeIntervalType(DAY, MINUTE)),
      ("1 01:01:01.12345", DayTimeIntervalType(DAY, DAY)),
      ("1 01:01:01.12345", DayTimeIntervalType(DAY, HOUR)),
      ("1 01:01:01.12345", DayTimeIntervalType(DAY, MINUTE)),

      ("INTERVAL '01:01:01.12345' HOUR TO SECOND", DayTimeIntervalType(DAY, HOUR)),
      ("INTERVAL '01:01:01.12345' HOUR TO HOUR", DayTimeIntervalType(DAY, SECOND)),
      ("INTERVAL '01:01:01.12345' HOUR TO MINUTE", DayTimeIntervalType(DAY, MINUTE)),
      ("01:01:01.12345", DayTimeIntervalType(DAY, DAY)),
      ("01:01:01.12345", DayTimeIntervalType(HOUR, HOUR)),
      ("01:01:01.12345", DayTimeIntervalType(DAY, MINUTE)),
      ("INTERVAL '1.23' DAY", DayTimeIntervalType(DAY)),
      ("INTERVAL '1.23' HOUR", DayTimeIntervalType(HOUR)),
      ("INTERVAL '1.23' MINUTE", DayTimeIntervalType(MINUTE)),
      ("INTERVAL '1.23' SECOND", DayTimeIntervalType(MINUTE)),
      ("1.23", DayTimeIntervalType(DAY)),
      ("1.23", DayTimeIntervalType(HOUR)),
      ("1.23", DayTimeIntervalType(MINUTE)),
      ("1.23", DayTimeIntervalType(MINUTE)))
      .foreach { case (interval, dataType) =>
        checkErrorInExpression[SparkIllegalArgumentException](
          cast(Literal.create(interval), dataType),
          "INVALID_INTERVAL_FORMAT.UNMATCHED_FORMAT_STRING_WITH_NOTICE",
          Map("intervalStr" -> "day-time",
            "typeName" -> dataType.typeName,
            "input" -> interval,
            "supportedFormat" ->
              IntervalUtils.supportedFormat(("day-time", dataType.startField, dataType.endField))
                .map(format => s"`$format`").mkString(", "))
        )
      }

    // Check first field outof bound
    Seq(("INTERVAL '1067519911' DAY", DayTimeIntervalType(DAY)),
      ("INTERVAL '10675199111 04' DAY TO HOUR", DayTimeIntervalType(DAY, HOUR)),
      ("INTERVAL '1067519911 04:00' DAY TO MINUTE", DayTimeIntervalType(DAY, MINUTE)),
      ("INTERVAL '1067519911 04:00:54.775807' DAY TO SECOND", DayTimeIntervalType()),
      ("INTERVAL '25620477881' HOUR", DayTimeIntervalType(HOUR)),
      ("INTERVAL '25620477881:00' HOUR TO MINUTE", DayTimeIntervalType(HOUR, MINUTE)),
      ("INTERVAL '25620477881:00:54.775807' HOUR TO SECOND", DayTimeIntervalType(HOUR, SECOND)),
      ("INTERVAL '1537228672801' MINUTE", DayTimeIntervalType(MINUTE)),
      ("INTERVAL '1537228672801:54.7757' MINUTE TO SECOND", DayTimeIntervalType(MINUTE, SECOND)),
      ("INTERVAL '92233720368541.775807' SECOND", DayTimeIntervalType(SECOND)))
      .foreach { case (interval, dataType) =>
        checkErrorInExpression[SparkIllegalArgumentException](
          cast(Literal.create(interval), dataType),
          "INVALID_INTERVAL_FORMAT.UNMATCHED_FORMAT_STRING_WITH_NOTICE",
          Map("intervalStr" -> "day-time",
            "typeName" -> dataType.typeName,
            "input" -> interval,
            "supportedFormat" ->
              IntervalUtils.supportedFormat(("day-time", dataType.startField, dataType.endField))
                .map(format => s"`$format`").mkString(", ")))
      }
  }

  test("cast ANSI intervals to/from decimals") {
    Seq(
      (Duration.ZERO, DayTimeIntervalType(DAY), DecimalType(10, 3)) -> Decimal(0, 10, 3),
      (Duration.ofHours(-1), DayTimeIntervalType(HOUR), DecimalType(10, 1)) -> Decimal(-10, 10, 1),
      (Duration.ofMinutes(1), DayTimeIntervalType(MINUTE), DecimalType(8, 2)) -> Decimal(100, 8, 2),
      (Duration.ofSeconds(59), DayTimeIntervalType(SECOND), DecimalType(6, 0)) -> Decimal(59, 6, 0),
      (Duration.ofSeconds(-60).minusMillis(1), DayTimeIntervalType(SECOND),
        DecimalType(10, 3)) -> Decimal(-60.001, 10, 3),
      (Duration.ZERO, DayTimeIntervalType(DAY, SECOND), DecimalType(10, 6)) -> Decimal(0, 10, 6),
      (Duration.ofHours(-23).minusMinutes(59).minusSeconds(59).minusNanos(123456000),
        DayTimeIntervalType(HOUR, SECOND), DecimalType(18, 6)) -> Decimal(-86399.123456, 18, 6),
      (Period.ZERO, YearMonthIntervalType(YEAR), DecimalType(5, 2)) -> Decimal(0, 5, 2),
      (Period.ofMonths(-1), YearMonthIntervalType(MONTH),
        DecimalType(8, 0)) -> Decimal(-1, 8, 0),
      (Period.ofYears(-1).minusMonths(1), YearMonthIntervalType(YEAR, MONTH),
        DecimalType(8, 3)) -> Decimal(-13000, 8, 3)
    ).foreach { case ((duration, intervalType, targetType), expected) =>
      checkEvaluation(
        Cast(Literal.create(duration, intervalType), targetType),
        expected)
      checkEvaluation(
        Cast(Literal.create(expected, targetType), intervalType),
        duration)
    }

    dayTimeIntervalTypes.foreach { it =>
      checkConsistencyBetweenInterpretedAndCodegenAllowingException((child: Expression) =>
        Cast(child, DecimalType.USER_DEFAULT), it)
      checkConsistencyBetweenInterpretedAndCodegenAllowingException((child: Expression) =>
        Cast(child, it), DecimalType.USER_DEFAULT)
    }

    yearMonthIntervalTypes.foreach { it =>
      checkConsistencyBetweenInterpretedAndCodegenAllowingException((child: Expression) =>
        Cast(child, DecimalType.USER_DEFAULT), it)
      checkConsistencyBetweenInterpretedAndCodegenAllowingException((child: Expression) =>
        Cast(child, it), DecimalType.USER_DEFAULT)
    }
  }

  test("SPARK-39865: toString() and sql() methods of CheckOverflowInTableInsert") {
    val cast = Cast(Literal(1.0), IntegerType)
    val expr = CheckOverflowInTableInsert(cast, "column_1")
    assert(expr.sql == cast.sql)
    assert(expr.toString == cast.toString)
  }

  test("SPARK-43336: Casting between Timestamp and TimestampNTZ requires timezone") {
    val timestampLiteral = Literal.create(1L, TimestampType)
    val timestampNTZLiteral = Literal.create(1L, TimestampNTZType)
    assert(!Cast(timestampLiteral, TimestampNTZType).resolved)
    assert(!Cast(timestampNTZLiteral, TimestampType).resolved)
  }

  test("Casting between TimestampType and StringType requires timezone") {
    val timestampLiteral = Literal.create(1L, TimestampType)
    assert(!Cast(timestampLiteral, StringType).resolved)
    assert(!Cast(timestampLiteral, StringType("UTF8_LCASE")).resolved)
  }

  test(s"Casting from char/varchar") {
    Seq(CharType(10), VarcharType(10)).foreach { typ =>
      Seq(
        IntegerType -> ("123", 123),
        LongType -> ("123 ", 123L),
        BooleanType -> ("true ", true),
        BooleanType -> ("false", false),
        DoubleType -> ("1.2", 1.2)
      ).foreach { case (toType, (from, to)) =>
        checkEvaluation(cast(Literal.create(from, typ), toType), to)
      }
    }
  }

  test("Casting to char/varchar") {
    Seq(CharType(10), VarcharType(10)).foreach { typ =>
      Seq(
        IntegerType -> (123, "123"),
        LongType -> (123L, "123"),
        BooleanType -> (true, "true"),
        BooleanType -> (false, "false"),
        DoubleType -> (1.2, "1.2")
      ).foreach { case (fromType, (from, to)) =>
        val paddedTo = if (typ.isInstanceOf[CharType]) {
          to.padTo(10, ' ')
        } else {
          to
        }
        checkEvaluation(cast(Literal.create(from, fromType), typ), paddedTo)
      }
    }
  }

  test("cast time to string") {
    Seq(
      LocalTime.MIDNIGHT -> "00:00:00",
      LocalTime.NOON -> "12:00:00",
      LocalTime.of(23, 59, 59) -> "23:59:59",
      LocalTime.of(23, 59, 59, 1000000) -> "23:59:59.001",
      LocalTime.of(23, 59, 59, 999999000) -> "23:59:59.999999"
    ).foreach { case (time, expectedStr) =>
      checkEvaluation(Cast(Literal(time), StringType), expectedStr)
    }

    checkConsistencyBetweenInterpretedAndCodegen(
      (child: Expression) => Cast(child, StringType), TimeType())
  }

  test("cast string to time") {
    checkEvaluation(cast(Literal.create("0:0:0"), TimeType()), 0L)
    checkEvaluation(cast(Literal.create(" 01:2:3.01   "), TimeType(2)), localTime(1, 2, 3, 10000))
    checkEvaluation(cast(Literal.create(" 12:13:14.999"),
      TimeType(3)), localTime(12, 13, 14, 999 * 1000))
    checkEvaluation(cast(Literal.create("23:0:59.0001 "), TimeType(4)), localTime(23, 0, 59, 100))
    checkEvaluation(cast(Literal.create("23:59:0.99999"),
      TimeType(5)), localTime(23, 59, 0, 999990))
    checkEvaluation(cast(Literal.create("23:59:59.000001     "),
      TimeType(6)), localTime(23, 59, 59, 1))
  }

  test("context independent foldable") {
    val array = Literal.create(Seq(1, 2, 3), ArrayType(IntegerType))
    val targetArrayType = ArrayType(StringType, containsNull = true)
    val map = Literal.create(
      Map("a" -> "123", "b" -> "true", "c" -> "f"),
      MapType(StringType, StringType, valueContainsNull = true))
    val targetMapType = MapType(StringType, StringType, valueContainsNull = false)
    val struct = Literal.create(
      Row(1, "2", true, null),
      StructType(Seq(
        StructField("a", IntegerType),
        StructField("b", StringType),
        StructField("c", BooleanType),
        StructField("d", NullType))))
    val targetStructType = StructType(Seq(
      StructField("a", StringType),
      StructField("b", IntegerType),
      StructField("c", BooleanType),
      StructField("d", BooleanType)))
    Seq(
      cast(Literal(1), StringType),
      cast(Literal(1), DateType),
      cast(Literal(1L), TimestampNTZType),
      cast(Literal("1"), IntegerType),
      cast(Literal("true"), BooleanType),
      cast(Literal("false"), BooleanType),
      cast(Literal(1.5), IntegerType),
      cast(Literal(1.5), LongType),
      cast(Literal(100), ByteType),
      cast(Literal(1000), ShortType),
      cast(Literal(1), DoubleType),
      cast(Literal(2.5), FloatType),
      cast(Literal(123), DecimalType(10, 2)),
      cast(Literal("1.5"), DoubleType),
      cast(Literal("2023-01-01"), DateType),
      cast(Literal(true), StringType),
      cast(Literal(false), IntegerType),
      cast(Literal(Duration.ofDays(1)), StringType),
      cast(Literal(Period.ofMonths(1)), StringType),
      cast(Literal(Decimal(10.5)), IntegerType),
      cast(Literal(null, NullType), BooleanType),
      cast(array, targetArrayType),
      cast(map, targetMapType),
      cast(struct, targetStructType)
    ).foreach { expr =>
      assert(expr.foldable, s"Expression $expr should be foldable")
      assert(expr.contextIndependentFoldable,
        s"Expression $expr should be context independent foldable")
    }
  }

  test("context dependent foldable") {
    val array = Literal.create(Seq("1", "2", "3"), ArrayType(StringType))
    val dateArray = Literal.create(Seq(
      Date.valueOf("2023-01-01"),
      Date.valueOf("2023-01-02"),
      Date.valueOf("2023-01-03")), ArrayType(DateType))
    val targetArrayType = ArrayType(TimestampType, containsNull = true)

    val map = Literal.create(
      Map("a" -> "123", "b" -> "true", "c" -> "f"),
      MapType(StringType, StringType, valueContainsNull = true))
    val dateMap = Literal.create(
      Map("a" -> Date.valueOf("2023-01-01"),
        "b" -> Date.valueOf("2023-01-02"),
        "c" -> Date.valueOf("2023-01-03")),
      MapType(StringType, DateType, valueContainsNull = true))
    val targetMapType = MapType(StringType, TimestampType, valueContainsNull = true)
    val struct = Literal.create(
      Row(1, "2", true, null),
      StructType(Seq(
        StructField("a", IntegerType),
        StructField("b", StringType),
        StructField("c", BooleanType),
        StructField("d", NullType))))
    val dateStruct = Literal.create(
      Row(1, Date.valueOf("2023-01-02"), true, null),
      StructType(Seq(
        StructField("a", IntegerType),
        StructField("b", DateType),
        StructField("c", BooleanType),
        StructField("d", NullType))))
    val targetStructType = StructType(Seq(
      StructField("a", StringType),
      StructField("b", TimestampType),
      StructField("c", BooleanType),
      StructField("d", BooleanType)))
    Seq(
      cast(Literal("abc"), TimestampType),
      cast(Literal(Date.valueOf("2023-01-01")), TimestampType),
      cast(array, targetArrayType),
      cast(dateArray, targetArrayType),
      cast(map, targetMapType),
      cast(dateMap, targetMapType),
      cast(struct, targetStructType),
      cast(dateStruct, targetStructType)
    ).foreach { expr =>
      assert(expr.foldable, s"Expression $expr should be foldable")
      assert(!expr.contextIndependentFoldable,
        s"Expression $expr should not be context independent foldable")
    }
  }

  test("SPARK-51562: cast alias - time function") {
    import org.apache.spark.sql.catalyst.analysis.FunctionRegistry
    import org.apache.spark.sql.catalyst.FunctionIdentifier
    // Test that time() function is registered and works correctly.
    val registry = FunctionRegistry.builtin
    val timeFunction = registry.lookupFunctionBuilder(FunctionIdentifier("time"))
    assert(timeFunction.isDefined, "time function should be registered in FunctionRegistry")
    // Test that time() function creates a proper Cast expression.
    val stringInput = Literal("12:34:56")
    val timeExpr = timeFunction.get(Seq(stringInput))
    assert(timeExpr.isInstanceOf[Cast])
    // The return type of the cast expression should be TimeType().
    val castExpr = timeExpr.asInstanceOf[Cast]
    assert(castExpr.dataType === TimeType())

    // Test basic string to time conversions using the alias.
    checkEvaluation(timeExpr, localTime(12, 34, 56))
    val timeExprWithMillis = timeFunction.get(Seq(Literal("12:34:56.789")))
    checkEvaluation(timeExprWithMillis, localTime(12, 34, 56, 789000))
    val timeExprWithMicros = timeFunction.get(Seq(Literal("12:34:56.789012")))
    checkEvaluation(timeExprWithMicros, localTime(12, 34, 56, 789012))

    // Test null inputs.
    val timeExprNull = timeFunction.get(Seq(Literal.create(null, StringType)))
    checkEvaluation(timeExprNull, null)
  }

  test("cast time to time") {
    checkEvaluation(cast(Literal(localTime(), TimeType(0)), TimeType(0)), 0L)
    checkEvaluation(cast(Literal(localTime(0, 0, 0, 1), TimeType(6)), TimeType(6)),
      localTime(0, 0, 0, 1))
    checkEvaluation(cast(Literal(localTime(0, 0, 0, 19), TimeType(6)), TimeType(5)),
      localTime(0, 0, 0, 10))
    checkEvaluation(cast(Literal(localTime(23, 59, 59, 999990), TimeType(5)), TimeType(6)),
      localTime(23, 59, 59, 999990))
    checkEvaluation(cast(Literal(localTime(23, 59, 59, 999999), TimeType(6)), TimeType(5)),
      localTime(23, 59, 59, 999990))
    checkEvaluation(cast(Literal(localTime(11, 58, 59, 123400), TimeType(4)), TimeType(5)),
      localTime(11, 58, 59, 123400))
    checkEvaluation(cast(Literal(localTime(19, 2, 3, 765000), TimeType(3)), TimeType(2)),
      localTime(19, 2, 3, 760000))

    for (sp <- TimeType.MIN_PRECISION to TimeType.MAX_PRECISION) {
      for (tp <- TimeType.MIN_PRECISION to TimeType.MAX_PRECISION) {
        checkConsistencyBetweenInterpretedAndCodegen(
          (child: Expression) => Cast(child, TimeType(sp)), TimeType(tp))
      }
    }
  }

  test("SPARK-52620: cast time to decimal with sufficient precision and scale") {
    // Test various TIME values converted to DecimalType(14, 9), which always has sufficient
    // precision and scale to represent the number of (nano)seconds since midnight. Note that
    // 5 decimal places are sufficient to represent the maximum number of seconds in any TIME
    // value, while 9 decimal places are needed to account for nanosecond fractional values.
    Seq(
      // 00:00:00 -> 0
      (LocalTime.MIDNIGHT, Decimal(0)),
      // 01:00:00 -> 3600
      (LocalTime.of(1, 0, 0), Decimal(3600)),
      // 01:01:00 -> 3660
      (LocalTime.of(1, 1, 0), Decimal(3660)),
      // 01:01:01 -> 3661
      (LocalTime.of(1, 1, 1), Decimal(3661)),
      // 01:02:03 -> 3723
      (LocalTime.of(1, 2, 3), Decimal(3723)),
      // 12:00:00 -> 43200
      (LocalTime.NOON, Decimal(43200)),
      // 23:59:59 -> 86399
      (LocalTime.of(23, 59, 59), Decimal(86399)),
      // 23:59:59.1 -> 86399.1
      (LocalTime.of(23, 59, 59, 100000000), Decimal(86399.1)),
      // 23:59:59.01 -> 86399.01
      (LocalTime.of(23, 59, 59, 10000000), Decimal(86399.01)),
      // 23:59:59.001 -> 86399.001
      (LocalTime.of(23, 59, 59, 1000000), Decimal(86399.001)),
      // 23:59:59.0001 -> 86399.0001
      (LocalTime.of(23, 59, 59, 100000), Decimal(86399.0001)),
      // 23:59:59.00001 -> 86399.00001
      (LocalTime.of(23, 59, 59, 10000), Decimal(86399.00001)),
      // 23:59:59.000001 -> 86399.000001
      (LocalTime.of(23, 59, 59, 1000), Decimal(86399.000001)),
      // 23:59:59.0000001 -> 86399.0000001
      (LocalTime.of(23, 59, 59, 100), Decimal(86399.0000001)),
      // 23:59:59.00000001 -> 86399.00000001
      (LocalTime.of(23, 59, 59, 10), Decimal(86399.00000001)),
      // 23:59:59.000000001 -> 86399.000000001
      (LocalTime.of(23, 59, 59, 1), Decimal(86399.000000001))
    ).foreach { case (time, expectedDecimal) =>
      checkEvaluation(Cast(Literal(time), DecimalType(14, 9)), expectedDecimal)
    }

    // Test with different decimal precision and scale. The precision and scale of the
    // DecimalType should be sufficient to represent the number of seconds since midnight.
    // However, if the scale of the DecimalType is larger than necessary, the fractional
    // seconds will be padded with zeros - which is the correct and expected behavior.
    checkEvaluation(Cast(Literal(LocalTime.NOON), DecimalType(5, 0)), Decimal(43200, 5, 0))
    checkEvaluation(Cast(Literal(LocalTime.NOON), DecimalType(6, 1)), Decimal(43200.0, 6, 1))
    checkEvaluation(Cast(Literal(LocalTime.NOON), DecimalType(7, 2)), Decimal(43200.00, 7, 2))
    checkEvaluation(Cast(Literal(LocalTime.NOON), DecimalType(8, 3)), Decimal(43200.000, 8, 3))
    checkEvaluation(Cast(Literal(LocalTime.NOON), DecimalType(9, 4)), Decimal(43200.0000, 9, 4))
    checkEvaluation(Cast(Literal(LocalTime.NOON), DecimalType(10, 5)), Decimal(43200.00000, 10, 5))
    checkEvaluation(Cast(Literal(LocalTime.NOON), DecimalType(11, 6)), Decimal(43200.000000, 11, 6))
  }

  test("SPARK-52620: cast time to decimal with insufficient scale") {
    // Decimal time precision loss happens if the scale of the target the DecimalType is not
    // sufficient to represent the fractional seconds of the TIME value. The following tests
    // check that the cast operation correctly rounds the fractional seconds to the nearest
    // decimal value that can be represented by the DecimalType, as limited by its scale.

    // The following test cases will test DecimalType(1,0). Here, the scale is 0, meaning
    // that the fractional seconds will be ignored, and rounded to the nearest whole second.
    var precision = 1
    var scale = 0
    Seq(
      // 00:00:00.1 -> 0 (when scale is: 0)
      (LocalTime.of(0, 0, 0, 100000000), 0),
      // 00:00:00.4 -> 0 (when scale is: 0)
      (LocalTime.of(0, 0, 0, 400000000), 0),
      // 00:00:00.49 -> 0 (when scale is: 0)
      (LocalTime.of(0, 0, 0, 490000000), 0),
      // 00:00:00.499999999 -> 0 (when scale is: 0)
      (LocalTime.of(0, 0, 0, 499999999), 0),
      // 00:00:00.5 -> 0 (when scale is: 0)
      (LocalTime.of(0, 0, 0, 500000000), 1),
      // 00:00:00.500000001 -> 1 (when scale is: 0)
      (LocalTime.of(0, 0, 0, 500000001), 1),
      // 00:00:00.599999999 -> 1 (when scale is: 0)
      (LocalTime.of(0, 0, 0, 599999999), 1),
      // 00:00:00.999999999 -> 1 (when scale is: 0)
      (LocalTime.of(0, 0, 0, 999999999), 1)
    ).foreach { case (timeValue, decimalValue) =>
      checkEvaluation(
        expression = Cast(Literal(timeValue), DecimalType(precision, scale)),
        expected = Decimal(decimalValue, precision, scale)
      )
    }

    // The following test cases will test DecimalType(2,1). Here, the scale is 1, meaning
    // that the fractional seconds will be rounded to the nearest tenth of a second.
    precision = 2
    scale = 1
    Seq(
      // 00:00:00.1 -> 0.1 (when scale is: 1)
      (LocalTime.of(0, 0, 0, 100000000), 0.1),
      // 00:00:00.4 -> 0.4 (when scale is: 1)
      (LocalTime.of(0, 0, 0, 400000000), 0.4),
      // 00:00:00.49 -> 0.5 (when scale is: 1)
      (LocalTime.of(0, 0, 0, 490000000), 0.5),
      // 00:00:00.499999999 -> 0.5 (when scale is: 1)
      (LocalTime.of(0, 0, 0, 499999999), 0.5),
      // 00:00:00.5 -> 0.5 (when scale is: 1)
      (LocalTime.of(0, 0, 0, 500000000), 0.5),
      // 00:00:00.500000001 -> 0.5 (when scale is: 1)
      (LocalTime.of(0, 0, 0, 500000001), 0.5),
      // 00:00:00.599999999 -> 0.6 (when scale is: 1)
      (LocalTime.of(0, 0, 0, 599999999), 0.6),
      // 00:00:00.999999999 -> 1.0 (when scale is: 1)
      (LocalTime.of(0, 0, 0, 999999999), 1.0)
    ).foreach { case (timeValue, decimalValue) =>
      checkEvaluation(
        expression = Cast(Literal(timeValue), DecimalType(precision, scale)),
        expected = Decimal(decimalValue, precision, scale)
      )
    }
  }

  test("SPARK-52619: cast time to integral types") {
    // Test normal cases that should work with a small number like 112 seconds after midnight
    val smallTime = Literal.create(LocalTime.of(0, 1, 52), TimeType(6))
    checkEvaluation(cast(smallTime, ByteType), 112.toByte)
    checkEvaluation(cast(smallTime, ShortType), 112.toShort)
    checkEvaluation(cast(smallTime, IntegerType), 112)
    checkEvaluation(cast(smallTime, LongType), 112L)

    // Test midnight to all integral types
    val midnight = Literal.create(LocalTime.MIDNIGHT, TimeType(6))
    checkEvaluation(cast(midnight, ByteType), 0.toByte)
    checkEvaluation(cast(midnight, ShortType), 0.toShort)
    checkEvaluation(cast(midnight, IntegerType), 0)
    checkEvaluation(cast(midnight, LongType), 0L)

    // Precision rounding/truncation tests with fractional seconds
    val time0 = Literal.create(LocalTime.NOON, TimeType(0))
    val time2 = Literal.create(LocalTime.of(12, 0, 0, 120000000), TimeType(2))
    val time4 = Literal.create(LocalTime.of(12, 0, 0, 345600000), TimeType(4))
    val oneTwoThreeTime5 = Literal.create(LocalTime.of(1, 2, 3, 555550000), TimeType(5))
    val maxTime4 = Literal.create(LocalTime.of(23, 59, 59, 999900000), TimeType(4))
    val fractional5 = Literal.create(LocalTime.of(0, 0, 17, 500000000), TimeType(1))
    val fractional000001 = Literal.create(LocalTime.of(0, 0, 17, 1000), TimeType(6))
    val fractional999999 = Literal.create(LocalTime.of(0, 0, 17, 999999000), TimeType(6))
    val fractional6 = Literal.create(LocalTime.of(0, 0, 17, 600000000), TimeType(1))
    val fractional4 = Literal.create(LocalTime.of(0, 0, 17, 400000000), TimeType(1))
    val fractional555 = Literal.create(LocalTime.of(0, 0, 17, 555000000), TimeType(3))
    checkEvaluation(cast(fractional5, IntegerType), 17)
    checkEvaluation(cast(fractional5, LongType), 17L)
    checkEvaluation(cast(fractional000001, IntegerType), 17)
    checkEvaluation(cast(fractional999999, IntegerType), 17)
    checkEvaluation(cast(fractional6, IntegerType), 17)
    checkEvaluation(cast(fractional4, IntegerType), 17)
    checkEvaluation(cast(fractional555, IntegerType), 17)
    checkEvaluation(cast(time0, IntegerType), 43200)
    checkEvaluation(cast(time2, IntegerType), 43200)
    checkEvaluation(cast(time4, IntegerType), 43200)
    checkEvaluation(cast(oneTwoThreeTime5, IntegerType), 3723)
    checkEvaluation(cast(oneTwoThreeTime5, LongType), 3723L)
    checkEvaluation(cast(maxTime4, IntegerType), 86399)
    checkEvaluation(cast(maxTime4, LongType), 86399L)
  }
}
