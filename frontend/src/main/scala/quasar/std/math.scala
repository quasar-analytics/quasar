/*
 * Copyright 2014–2017 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.std

import slamdata.Predef._
import quasar.{Data, DateTimeInterval, Func, UnaryFunc, BinaryFunc, Type, Mapping, SemanticError}
import quasar.fp._
import quasar.fp.ski._
import quasar.frontend.logicalplan.{LogicalPlan => LP, _}
import quasar.DataDateTimeExtractors._

import scala.math.BigDecimal.RoundingMode

import matryoshka._
import scalaz._, Scalaz._, Validation.{failure, success}
import shapeless._

trait MathLib extends Library {
  private val MathRel = Type.Numeric ⨿ Type.Interval
  private val MathAbs = Type.Numeric ⨿ Type.Interval ⨿ Type.Temporal
  private val LocalTemporal = Type.LocalDate ⨿ Type.LocalDateTime ⨿ Type.LocalTime

  // TODO[monocle]: Unit unapply needs to do Boolean instead of Option[Unit]
  // val Zero = Prism.partial[Data, Unit] {
  //   case Data.Number(v) if v ≟ 0 => ()
  // } (κ(Data.Int(0)))
  // Be careful when using this. Zero() creates an Int(0), but it *matches* Dec(0) too.
  object Zero {
    def apply() = Data.Int(0)
    def unapply(obj: Data): Boolean = obj match {
      case Data.Number(v) if v ≟ 0 => true
      case _                       => false
    }
  }
  object One {
    def apply() = Data.Int(1)
    def unapply(obj: Data): Boolean = obj match {
      case Data.Number(v) if v ≟ 1 => true
      case _                       => false
    }
  }

  object ZeroF {
    def apply() = Constant(Zero())
    def unapply[A](obj: LP[A]): Boolean = obj match {
      case Constant(Zero()) => true
      case _                 => false
    }
  }
  object OneF {
    def apply() = Constant(One())
    def unapply[A](obj: LP[A]): Boolean = obj match {
      case Constant(One()) => true
      case _                => false
    }
  }

  object TZero {
    def apply() = Type.Const(Zero())
    def unapply(obj: Type): Boolean = obj match {
      case Type.Const(Zero()) => true
      case _                  => false
    }
  }
  object TOne {
    def apply() = Type.Const(One())
    def unapply(obj: Type): Boolean = obj match {
      case Type.Const(One()) => true
      case _                 => false
    }
  }

  /** Adds two numeric values, promoting to decimal if either operand is
    * decimal.
    */
  val Add = BinaryFunc(
    Mapping,
    "Adds two numeric or temporal values",
    MathAbs,
    Func.Input2(MathAbs, MathAbs),
    new Func.Simplifier {
      def apply[T]
        (orig: LP[T])
        (implicit TR: Recursive.Aux[T, LP], TC: Corecursive.Aux[T, LP]) =
        orig match {
          case Invoke(_, Sized(Embed(x), Embed(ZeroF()))) => x.some
          case Invoke(_, Sized(Embed(ZeroF()), Embed(x))) => x.some
          case _                                           => None
        }
    },
    partialTyperV[nat._2] {
      case Sized(Type.Const(Data.Int(v1)), Type.Const(Data.Int(v2)))             => success(Type.Const(Data.Int(v1 + v2)))
      case Sized(Type.Const(Data.Number(v1)), Type.Const(Data.Number(v2)))       => success(Type.Const(Data.Dec(v1 + v2)))
      case Sized(Type.Const(Data.Interval(i1)), Type.Const(Data.Interval(i2))) =>
        success(Type.Const(Data.Interval(i1 plus i2)))
      case Sized(Type.Const(CanLensDateTime(dt)), Type.Const(Data.Interval(i))) =>
        success(Type.Const(dt.peeks(i.addTo)))
      case Sized(Type.Const(CanLensTime(t)), Type.Const(Data.Interval(DateTimeInterval.TimeLike(i)))) =>
        success(Type.Const(t.peeks(_.plus(i))))
      case Sized(Type.Const(CanLensDate(d)), Type.Const(Data.Interval(DateTimeInterval.DateLike(i)))) =>
        success(Type.Const(d.peeks(_.plus(i))))
      case Sized(Type.Const(Data.Interval(i)), Type.Const(CanLensDateTime(dt))) =>
        success(Type.Const(dt.peeks(i.addTo)))
      case Sized(Type.Const(Data.Interval(DateTimeInterval.TimeLike(i))), Type.Const(CanLensTime(t))) =>
        success(Type.Const(t.peeks(_.plus(i))))
      case Sized(Type.Const(Data.Interval(DateTimeInterval.DateLike(i))), Type.Const(CanLensDate(d))) =>
        success(Type.Const(d.peeks(_.plus(i))))
      case Sized(Type.Const(CanLensDate(_)), Type.Const(Data.Interval(_))) =>
        failure(NonEmptyList(SemanticError.GenericError("Intervals containing time information can't be added to dates")))
      case Sized(Type.Const(CanLensTime(_)), Type.Const(Data.Interval(_))) =>
        failure(NonEmptyList(SemanticError.GenericError("Intervals containing date information can't be added to times")))
      case Sized(Type.Const(Data.Interval(_)), Type.Const(CanLensDate(_))) =>
        failure(NonEmptyList(SemanticError.GenericError("Intervals containing time information can't be added to dates")))
      case Sized(Type.Const(Data.Interval(_)), Type.Const(CanLensTime(_))) =>
        failure(NonEmptyList(SemanticError.GenericError("Intervals containing date information can't be added to times")))
      case Sized(t1, t2)
        if (Type.OffsetDateTime ⨿ Type.OffsetTime ⨿ Type.OffsetDate ⨿
                    Type.LocalDateTime ⨿ Type.LocalDate ⨿ Type.LocalTime ⨿ Type.Interval).contains(t1) &&
            Type.Interval.contains(t2) =>
        success(t1.widenConst)
      case Sized(t1, t2)
        if (Type.OffsetDateTime ⨿ Type.OffsetTime ⨿ Type.OffsetDate ⨿
                    Type.LocalDateTime ⨿ Type.LocalDate ⨿ Type.LocalTime ⨿ Type.Interval).contains(t2) &&
            Type.Interval.contains(t1) =>
        success(t2.widenConst)
    } ||| numericWidening,
    partialUntyperOV[nat._2](t => 
      Type.typecheck(Type.Interval, t).fold(κ(t match {
        case Type.Int                      => Some(success(Func.Input2(Type.Int, Type.Int)))
        case t if Type.Numeric.contains(t) => Some(success(Func.Input2(Type.Numeric, Type.Numeric)))
        case _                             => None
        }),
      κ(Some(success(Func.Input2(t, Type.Interval)))))))

  /**
   * Multiplies two numeric values, promoting to decimal if either operand is decimal.
   */
  val Multiply = BinaryFunc(
    Mapping,
    "Multiplies two numeric values or one interval and one numeric value",
    MathRel,
    Func.Input2(MathRel, MathRel),
    new Func.Simplifier {
      def apply[T]
        (orig: LP[T])
        (implicit TR: Recursive.Aux[T, LP], TC: Corecursive.Aux[T, LP]) =
        orig match {
          case Invoke(_, Sized(Embed(x), Embed(OneF()))) => x.some
          case Invoke(_, Sized(Embed(OneF()), Embed(x))) => x.some
          case _                                          => None
        }
    },
    (partialTyper[nat._2] {
      case Sized(TZero(), t) if Type.Numeric.contains(t) => Type.Const(Data.Dec(0))
      case Sized(t, TZero()) if Type.Numeric.contains(t) => Type.Const(Data.Dec(0))

      case Sized(Type.Const(Data.Int(v1)), Type.Const(Data.Int(v2)))       => Type.Const(Data.Int(v1 * v2))
      case Sized(Type.Const(Data.Number(v1)), Type.Const(Data.Number(v2))) => Type.Const(Data.Dec(v1 * v2))

      // TODO: handle interval multiplied by Dec (not provided by threeten). See SD-582.
      // Is that actually sensical? It doesn't sound like it to me; you can't "carry"
      // fractional units down to smaller units most of the time.
      case Sized(Type.Const(Data.Interval(v1)), Type.Const(Data.Int(v2))) => Type.Const(Data.Interval(v1.multiply(v2.intValue)))
      case Sized(Type.Const(Data.Int(v1)), Type.Const(Data.Interval(v2))) => Type.Const(Data.Interval(v2.multiply(v1.intValue)))
      case Sized(t1, t2) if (Type.Int contains t2) && (Type.Interval contains t1) => Type.Interval
      case Sized(t1, t2) if (Type.Int contains t1) && (Type.Interval contains t2) => Type.Interval
    }) ||| numericWidening,
    partialUntyper[nat._2] {
      case Type.Interval => Func.Input2(Type.Int ⨿ Type.Interval, Type.Int ⨿ Type.Interval)
      case Type.Int => Func.Input2(Type.Int, Type.Int)
      case Type.Dec => Func.Input2(Type.Numeric, Type.Numeric)
      case _        => Func.Input2(MathRel, MathRel)
    })

  val Power = BinaryFunc(
    Mapping,
    "Raises the first argument to the power of the second",
    Type.Numeric,
    Func.Input2(Type.Numeric, Type.Numeric),
    new Func.Simplifier {
      def apply[T]
        (orig: LP[T])
        (implicit TR: Recursive.Aux[T, LP], TC: Corecursive.Aux[T, LP]) =
        orig match {
          case Invoke(_, Sized(Embed(x), Embed(OneF()))) => x.some
          case _                                         => None
        }
    },
    partialTyper[nat._2] {
      case Sized(_, TZero()) => TOne()
      case Sized(v1, TOne()) => v1
      case Sized(TZero(), _) => TZero()

      case Sized(Type.Const(Data.Int(v1)), Type.Const(Data.Int(v2))) if v2.isValidInt    => Type.Const(Data.Int(v1.pow(v2.toInt)))
      case Sized(Type.Const(Data.Number(v1)), Type.Const(Data.Int(v2))) if v2.isValidInt => Type.Const(Data.Dec(v1.pow(v2.toInt)))
    } ||| numericWidening,
    partialUntyper[nat._2] {
      case Type.Int => Func.Input2(Type.Int, Type.Int)
      case Type.Dec => Func.Input2(Type.Numeric, Type.Numeric)
    })

  /** Subtracts one value from another, promoting to decimal if either operand
    * is decimal.
    */
    // TODO: document change
  val Subtract = BinaryFunc(
    Mapping,
    "Subtracts two numeric or temporal values",
    MathAbs,
    Func.Input2(MathAbs, MathAbs),
    new Func.Simplifier {
      def apply[T]
        (orig: LP[T])
        (implicit TR: Recursive.Aux[T, LP], TC: Corecursive.Aux[T, LP]) =
        orig match {
          case Invoke(_, Sized(Embed(x), Embed(ZeroF()))) => x.some
          case Invoke(_, Sized(Embed(ZeroF()), x))        => Negate(x).some
          case _                                           => None
        }
    },
    partialTyper[nat._2] {
      case Sized(v1, TZero()) if Type.Numeric.contains(v1) => v1

      case Sized(Type.Const(Data.Int(v1)), Type.Const(Data.Int(v2)))       =>
        Type.Const(Data.Int(v1 - v2))
      case Sized(Type.Const(Data.Number(v1)), Type.Const(Data.Number(v2))) =>
        Type.Const(Data.Dec(v1 - v2))
      case Sized(Type.Const(CanLensDateTime(v1)), Type.Const(Data.Interval(v2))) =>
        Type.Const(v1.peeks(v2.subtractFrom))
      case Sized(Type.Const(CanLensDate(v1)), Type.Const(Data.Interval(DateTimeInterval.DateLike(v2)))) =>
        Type.Const(v1.peeks(_.minus(v2)))
      case Sized(Type.Const(CanLensTime(v1)), Type.Const(Data.Interval(DateTimeInterval.TimeLike(v2)))) =>
        Type.Const(v1.peeks(_.minus(v2)))
      case Sized(Type.Const(Data.LocalDateTime(v1)), Type.Const(Data.LocalDateTime(v2))) =>
        val y = v1.getYear - v2.getYear
        val mo = v1.getMonth.getValue - v2.getMonth.getValue
        val nv2 = v2.minusMonths(mo.toLong)
        val d = v1.getDayOfMonth - nv2.getDayOfMonth
        val h = v1.getHour - v2.getHour
        val mi = v1.getMinute - v2.getMinute
        val s = v1.getSecond - v2.getSecond
        val n = v1.getNano - v2.getNano
        Type.Const(Data.Interval(DateTimeInterval(y, mo, d, h * 3600L + mi * 60L + s, n.toLong)))
      case Sized(Type.Const(Data.LocalDate(v1)), Type.Const(Data.LocalDate(v2))) =>
        val y = v1.getYear - v2.getYear
        val m = v1.getMonth.getValue - v2.getMonth.getValue
        val nv2 = v2.minusMonths(m.toLong)
        val d = v1.getDayOfMonth - nv2.getDayOfMonth
        Type.Const(Data.Interval(DateTimeInterval(y, m, d, 0L, 0L)))
      case Sized(Type.Const(Data.LocalTime(v1)), Type.Const(Data.LocalTime(v2))) =>
        val h = v1.getHour - v2.getHour
        val m = v1.getMinute - v2.getMinute
        val s = v1.getSecond - v2.getSecond
        val n = v1.getNano - v2.getNano
        Type.Const(Data.Interval(DateTimeInterval(0, 0, 0, h * 3600L + m * 60L + s, n.toLong)))
      case Sized(Type.LocalDateTime.superOf(_), Type.LocalDateTime.superOf(_)) => Type.Interval
      case Sized(Type.OffsetDateTime.superOf(_), Type.Interval.superOf(_)) => Type.OffsetDateTime
      case Sized(Type.OffsetDate.superOf(_), Type.Interval.superOf(_)) => Type.OffsetDate
      case Sized(Type.OffsetTime.superOf(_), Type.Interval.superOf(_)) => Type.OffsetTime
      case Sized(Type.LocalDate.superOf(_), Type.LocalDate.superOf(_)) => Type.Interval
      case Sized(Type.LocalDate.superOf(_), Type.Interval.superOf(_))  => Type.LocalDate
      case Sized(Type.LocalTime.superOf(_), Type.Interval.superOf(_))  => Type.LocalTime
      case Sized(Type.LocalTime.superOf(_), Type.LocalTime.superOf(_)) => Type.Interval
      case Sized(Type.Interval.superOf(_), Type.Interval.superOf(_))   => Type.Interval
      case Sized(Type.Temporal.superOf(ty), Type.Interval.superOf(_))  => ty.widenConst
      case Sized(Type.Temporal.superOf(t1), Type.Temporal.superOf(t2))
        if (t1.contains(t2) || t2.contains(t1)) => Type.Interval
    } ||| numericWidening,
    partialUntyperOV[nat._2] { t => Type.typecheck(Type.Temporal, t).fold(
      κ(Type.typecheck(Type.Interval, t).fold(
        κ(t match {
          case Type.Int                      => Some(success(Func.Input2(Type.Int    , Type.Int    )))
          case t if Type.Numeric.contains(t) => Some(success(Func.Input2(Type.Numeric, Type.Numeric)))
          case _                             => None
        }),
        κ(Some(success(Func.Input2(Type.Temporal ⨿ Type.Interval, Type.Temporal ⨿ Type.Interval)))))),
      κ(Some(success(Func.Input2(t, Type.Interval)))))})

  /**
   * Divides one value by another, promoting to decimal if either operand is decimal.
   */
  val Divide = BinaryFunc(
    Mapping,
    "Divides one numeric or interval value by another (non-zero) numeric value",
    Type.Dec ⨿ Type.Interval,
    Func.Input2(MathRel, Type.Numeric),
    new Func.Simplifier {
      def apply[T]
        (orig: LP[T])
        (implicit TR: Recursive.Aux[T, LP], TC: Corecursive.Aux[T, LP]) =
        orig match {
          case Invoke(_, Sized(Embed(x), Embed(OneF()))) => x.some
          case _                                         => None
        }
    },
    partialTyperV[nat._2] {
      case Sized(v1, TOne())  => success(v1)

      case Sized(Type.Const(Data.Int(v1)), Type.Const(Data.Int(v2)))
        if v2 != BigInt(0)                                                => success(Type.Const(Data.Dec(BigDecimal(v1) / BigDecimal(v2))))
      case Sized(Type.Const(Data.Number(v1)), Type.Const(Data.Number(v2)))
        if v2 != BigDecimal(0)                                            => success(Type.Const(Data.Dec(v1 / v2)))

      // TODO: handle interval divided by Dec (not provided by threeten). See SD-582.
      case Sized(Type.Const(Data.Interval(v1)), Type.Const(Data.Int(v2))) => success(Type.Const(Data.Interval(DateTimeInterval.divideBy(v1, v2.intValue))))

      case Sized(Type.Interval.superOf(_), Type.Int.superOf(_))           => success(Type.Interval)
      case Sized(Type.Numeric.superOf(_), Type.Numeric.superOf(_))        => success(Type.Dec)
    },
    untyper[nat._2](t => Type.typecheck(Type.Interval, t).fold(
      κ(success(Func.Input2(Type.Numeric, Type.Numeric))),
      κ(success(Func.Input2(Type.Interval, Type.Int))))))

  /**
   * Aka "unary minus".
   */
  val Negate = UnaryFunc(
    Mapping,
    "Reverses the sign of a numeric or interval value",
    MathRel,
    Func.Input1(MathRel),
    noSimplification,
    partialTyperV[nat._1] {
      case Sized(Type.Const(Data.Int(v)))      => success(Type.Const(Data.Int(-v)))
      case Sized(Type.Const(Data.Dec(v)))      => success(Type.Const(Data.Dec(-v)))
      case Sized(Type.Const(Data.Interval(v))) => success(Type.Const(Data.Interval(v.multiply(-1))))

      case Sized(t) if (Type.Numeric ⨿ Type.Interval) contains t => success(t)
    },
    untyper[nat._1] {
      case t             => success(Func.Input1(t))
    })

  val Abs = UnaryFunc(
    Mapping,
    "Returns the absolute value of a numeric or interval value",
    Type.Numeric,
    Func.Input1(Type.Numeric),
    noSimplification,
    partialTyperV[nat._1] {
      case Sized(Type.Const(Data.Int(v)))      => success(Type.Const(Data.Int(v.abs)))
      case Sized(Type.Const(Data.Dec(v)))      => success(Type.Const(Data.Dec(v.abs)))

      case Sized(t) if Type.Numeric contains t => success(t)
    },
    untyper[nat._1] {
      case t             => success(Func.Input1(t))
    })

    val Ceil = UnaryFunc(
      Mapping,
      "Returns the nearest integer greater than or equal to a numeric value",
      Type.Int,
      Func.Input1(Type.Numeric),
      noSimplification,
      partialTyperV[nat._1] {
        case Sized(Type.Const(Data.Int(v))) => success(Type.Const(Data.Int(v)))
        case Sized(Type.Const(Data.Dec(v))) => success(Type.Const(Data.Int(v.setScale(0, RoundingMode.CEILING).toBigInt())))
      },
      basicUntyper)

    val Floor = UnaryFunc(
      Mapping,
      "Returns the nearest integer less than or equal to a numeric value",
      Type.Int,
      Func.Input1(Type.Numeric),
      noSimplification,
      partialTyperV[nat._1] {
        case Sized(Type.Const(Data.Int(v))) => success(Type.Const(Data.Int(v)))
        case Sized(Type.Const(Data.Dec(v))) => success(Type.Const(Data.Int(v.setScale(0, RoundingMode.FLOOR).toBigInt)))
      },
      basicUntyper)

    val Trunc = UnaryFunc(
      Mapping,
      "Truncates a numeric value towards zero",
      Type.Int,
      Func.Input1(Type.Numeric),
      noSimplification,
      partialTyperV[nat._1] {
        case Sized(Type.Const(Data.Int(v)))      => success(Type.Const(Data.Int(v)))
        case Sized(Type.Const(Data.Dec(v)))      => success(Type.Const(Data.Int(v.toBigInt)))
      },
      basicUntyper)

    val Round = UnaryFunc(
      Mapping,
      "Rounds a numeric value to the closest integer, utilizing a half-even strategy",
      Type.Int,
      Func.Input1(Type.Numeric),
      noSimplification,
      partialTyperV[nat._1] {
        case Sized(Type.Const(Data.Int(v))) => success(Type.Const(Data.Int(v)))
        case Sized(Type.Const(Data.Dec(v))) => success(Type.Const(Data.Int(v.setScale(0, RoundingMode.HALF_EVEN).toBigInt)))
      },
      basicUntyper)

    val FloorScale = BinaryFunc(
      Mapping,
      "Returns the nearest number less-than or equal-to a given number, with the specified number of decimal digits",
      Type.Numeric,
      Func.Input2(Type.Numeric, Type.Int),
      noSimplification,
      (partialTyperV[nat._2] {
        case Sized(v @ Type.Const(Data.Int(_)), Type.Const(Data.Int(s))) if s >= 0 => success(v)
        case Sized(Type.Const(Data.Int(v)), Type.Const(Data.Int(s))) => success(Type.Const(Data.Dec(BigDecimal(v).setScale(s.toInt, RoundingMode.FLOOR))))
        case Sized(Type.Const(Data.Dec(v)), Type.Const(Data.Int(s))) => success(Type.Const(Data.Dec(v.setScale(s.toInt, RoundingMode.FLOOR))))

        case Sized(t1, t2) if Type.Numeric.contains(t1) && Type.Numeric.contains(t2) => success(t1)
      }),
      partialUntyper[nat._2] {
        case t => Func.Input2(t, Type.Int)
      })

    val CeilScale = BinaryFunc(
      Mapping,
      "Returns the nearest number greater-than or equal-to a given number, with the specified number of decimal digits",
      Type.Numeric,
      Func.Input2(Type.Numeric, Type.Int),
      noSimplification,
      (partialTyperV[nat._2] {
        case Sized(v @ Type.Const(Data.Int(_)), Type.Const(Data.Int(s))) if s >= 0 => success(v)
        case Sized(Type.Const(Data.Int(v)), Type.Const(Data.Int(s))) => success(Type.Const(Data.Dec(BigDecimal(v).setScale(s.toInt, RoundingMode.CEILING))))
        case Sized(Type.Const(Data.Dec(v)), Type.Const(Data.Int(s))) => success(Type.Const(Data.Dec(v.setScale(s.toInt, RoundingMode.CEILING))))

        case Sized(t1, t2) if Type.Numeric.contains(t1) && Type.Numeric.contains(t2) => success(t1)
      }),
      partialUntyper[nat._2] {
        case t => Func.Input2(t, Type.Int)
      })

    val RoundScale = BinaryFunc(
      Mapping,
      "Returns the nearest number to a given number with the specified number of decimal digits",
      Type.Numeric,
      Func.Input2(Type.Numeric, Type.Int),
      noSimplification,
      (partialTyperV[nat._2] {
        case Sized(v @ Type.Const(Data.Int(_)), Type.Const(Data.Int(s))) if s >= 0 => success(v)
        case Sized(Type.Const(Data.Int(v)), Type.Const(Data.Int(s))) => success(Type.Const(Data.Dec(BigDecimal(v).setScale(s.toInt, RoundingMode.HALF_EVEN))))
        case Sized(Type.Const(Data.Dec(v)), Type.Const(Data.Int(s))) => success(Type.Const(Data.Dec(v.setScale(s.toInt, RoundingMode.HALF_EVEN))))

        case Sized(t1, t2) if Type.Numeric.contains(t1) && Type.Numeric.contains(t2) => success(t1)
      }),
      partialUntyper[nat._2] {
        case t => Func.Input2(t, Type.Int)
      })

  // TODO: Come back to this, Modulo docs need to stop including Interval.
  // Note: there are 2 interpretations of `%` which return different values for negative numbers.
  // Depending on the interpretation `-5.5 % 1` can either be `-0.5` or `0.5`.
  // Generally, the first interpretation seems to be referred to as "remainder" and the 2nd as "modulo".
  // Java/scala and PostgreSQL all use the remainder interpretation, so we use it here too.
  // However, since PostgreSQL uses the function name `mod` as an alias for `%` while using the term
  // remainder in its description we keep the term `Modulo` around.
  val Modulo = BinaryFunc(
    Mapping,
    "Finds the remainder of one number divided by another",
    Type.Numeric,
    Func.Input2(Type.Numeric, Type.Numeric),
    noSimplification,
    (partialTyperV[nat._2] {
      case Sized(v1, TOne()) if Type.Int.contains(v1)                      => success(TZero())
      case Sized(Type.Const(Data.Int(v1)), Type.Const(Data.Int(v2)))
        if v2 != BigInt(0)                                                 => success(Type.Const(Data.Int(v1 % v2)))
      case Sized(Type.Const(Data.Number(v1)), Type.Const(Data.Number(v2)))
        if v2 != BigDecimal(0)                                             => success(Type.Const(Data.Dec(v1 % v2)))
      case Sized(Type.Interval, t) if Type.Numeric.contains(t)             => success(Type.Interval)
      case Sized(t1, t2)
        if Type.Int.contains(t1) && Type.Int.contains(t2)                  => success(Type.Int)
      case Sized(t1, t2)
        if Type.Dec.contains(t1) && Type.Dec.contains(t2)                  => success(Type.Dec)
      case Sized(t1, t2)
        if Type.Numeric.contains(t1) && Type.Numeric.contains(t2)          => success(Type.Numeric)
    }),
    partialUntyper[nat._2] {
      case Type.Int => Func.Input2(Type.Int, Type.Int)
      case t if Type.Numeric.contains(t) => Func.Input2(Type.Numeric, Type.Numeric)
      case Type.Interval => Func.Input2(Type.Interval, Type.Numeric)
    })
}

object MathLib extends MathLib
