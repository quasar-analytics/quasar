/*
 * Copyright 2014–2019 SlamData Inc.
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

package quasar.impl.push

import slamdata.Predef._

import quasar.api.QueryEvaluator
import quasar.api.destination.DestinationType
import quasar.api.destination.ResultType
import quasar.api.push.{ResultPush, ResultPushError}
import quasar.api.resource.ResourcePath
import quasar.api.resource.{ResourcePath, ResourceName}
import quasar.api.table.{TableColumn, TableName, TableRef}
import quasar.connector.{Destination, ResultSink}
import quasar.{ConditionMatchers, EffectfulQSpec}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.concurrent.Ref
import eu.timepit.refined.auto._
import fs2.concurrent.SignallingRef
import fs2.job.JobManager
import fs2.{Stream, text}
import scalaz.std.set._
import scalaz.std.string._
import scalaz.syntax.bind._
import scalaz.{Equal, NonEmptyList}
import shims._

object DefaultResultPushSpec extends EffectfulQSpec[IO] with ConditionMatchers {
  implicit val tmr = IO.timer(global)

  val TableId = 42
  val DestinationId = 42
  val RefDestinationType = DestinationType("ref", 1L)

  // convert a bytestream into stream of space separeted words
  def bytesToString(s: Stream[IO, Byte]): Stream[IO, String] =
    s.split(_ == 0x20).flatMap(Stream.chunk).through(text.utf8Decode)

  type Filesystem = Map[ResourcePath, String]

  // a sink that writes to a Ref
  final class RefCsvSink(ref: Ref[IO, Filesystem]) extends ResultSink[IO] {
    type RT = ResultType.Csv[IO]

    val resultType = ResultType.Csv()

    def apply(dst: ResourcePath, result: (List[TableColumn], Stream[IO, Byte])): IO[Unit] = {
      val (columns, data) = result

      // write stream one word at a time to ref
      bytesToString(data).evalMap(str =>
        ref.update(currentFs =>
          currentFs + (dst -> currentFs.get(dst).fold(str)(_ ++ str)))).compile.drain
    }
  }

  final class RefDestination(ref: Ref[IO, Filesystem]) extends Destination[IO] {
    def destinationType: DestinationType = RefDestinationType
    def sinks = NonEmptyList(new RefCsvSink(ref))
  }

  def convert(st: Stream[IO, String]): Stream[IO, Byte] =
    st.through(text.utf8Encode)

  def mkEvaluator(fn: String => Stream[IO, String]): QueryEvaluator[IO, String, Stream[IO, String]] =
    new QueryEvaluator[IO, String, Stream[IO, String]] {
      def evaluate(query: String): IO[Stream[IO, String]] =
        IO(fn(query))
    }

  def mkResultPush(
    tables: Map[Int, TableRef[String]],
    destinations: Map[Int, Destination[IO]],
    manager: JobManager[IO, Int, Nothing],
    evaluator: QueryEvaluator[IO, String, Stream[IO, String]])
      : ResultPush[IO, Int, Int] = {

    val lookupTable: Int => IO[Option[TableRef[String]]] =
      tableId => IO(tables.get(tableId))

    val lookupDestination: Int => IO[Option[Destination[IO]]] =
      destinationId => IO(destinations.get(destinationId))

    DefaultResultPush[IO, Int, Int, String, Stream[IO, String]](
      lookupTable,
      evaluator,
      lookupDestination,
      manager,
      convert)
  }

  def mockEvaluate(q: String): String =
    s"evaluated($q)"

  def latchGet(s: SignallingRef[IO, String], expected: String): IO[Unit] =
    s.discrete.filter(Equal[String].equal(_, expected)).take(1).compile.drain

  val WorkTime = Duration(100, MILLISECONDS)
  val await = IO.sleep(WorkTime)
  val awaitS = Stream.sleep_(WorkTime)

  "result push" >> {
    "push a table to a destination" >>* {
      val pushPath = ResourcePath.root() / ResourceName("foo") / ResourceName("bar")
      val query = "query"
      val testTable = TableRef(TableName("foo"), query, List())

      for {
        filesystem <- Ref.of[IO, Filesystem](Map.empty)
        (jm, _) <- JobManager[IO, Int, Nothing]().compile.resource.lastOrError.allocated
        destination = new RefDestination(filesystem)
        ref <- SignallingRef[IO, String]("Not started")
        evaluator = mkEvaluator(q => Stream.emit(mockEvaluate(q)) ++ Stream.eval_(ref.set("Finished")))

        push = mkResultPush(Map(TableId -> testTable), Map(DestinationId -> destination), jm, evaluator)
        startRes <- push.start(TableId, DestinationId, pushPath, ResultType.Csv[IO](), None)
        _ <- latchGet(ref, "Finished")
        filesystemAfterPush <- filesystem.get
      } yield {
        filesystemAfterPush.keySet must equal(Set(pushPath))
        filesystemAfterPush(pushPath) must equal(mockEvaluate(query))
        startRes must beNormal
      }
    }

    "cancel a table push" >>* {
      val pushPath = ResourcePath.root() / ResourceName("foo") / ResourceName("bar")
      val query = "query"
      val testTable = TableRef(TableName("foo"), query, List())

      def testStream(ref: SignallingRef[IO, String]): Stream[IO, String] =
        Stream.eval_(ref.set("Started")) ++
          Stream("foo") ++
          Stream(" ") ++ // chunk delimiter
          Stream.sleep_(WorkTime) ++
          Stream("bar") ++
          Stream.eval_(ref.set("Finished"))

      for {
        filesystem <- Ref.of[IO, Filesystem](Map.empty)
        (jm, _) <- JobManager[IO, Int, Nothing]().compile.resource.lastOrError.allocated
        destination = new RefDestination(filesystem)
        ref <- SignallingRef[IO, String]("Not started")
        push = mkResultPush(Map(TableId -> testTable), Map(DestinationId -> destination), jm, mkEvaluator(_ => testStream(ref)))
        startRes <- push.start(TableId, DestinationId, pushPath, ResultType.Csv[IO](), None)
        _ <- latchGet(ref, "Started")
        cancelRes <- push.cancel(TableId)
        filesystemAfterPush <- filesystem.get
        // fail the test if push evaluation was not cancelled
        evaluationFinished <- (latchGet(ref, "Finished") >> IO(ko("Push not cancelled"))).timeoutTo(WorkTime * 2, IO(ok))
      } yield {
        filesystemAfterPush.keySet must equal(Set(pushPath))
        // check if a *partial* result was pushed
        filesystemAfterPush(pushPath) must equal("foo")
        startRes must beNormal
        cancelRes must beNormal
        evaluationFinished
      }
    }

    // "retrieves the status of a running push" >>* {}
    // "retrieves the status of a cancelled push" >>* {}
    // "retrieves the status of a completed push" >>* {}
    // "rejects an already running push" >>* {}

    "cancel all pushes" >>* {
      val path1 = ResourcePath.root() / ResourceName("foo")
      val testTable1 = TableRef(TableName("foo"), "queryFoo", List())

      val path2 = ResourcePath.root() / ResourceName("bar")
      val testTable2 = TableRef(TableName("bar"), "queryBar", List())

      for {
        filesystem <- Ref.of[IO, Filesystem](Map.empty)
        refFoo <- SignallingRef[IO, String]("Not started")
        refBar <- SignallingRef[IO, String]("Not started")
        (jm, _) <- JobManager[IO, Int, Nothing]().compile.resource.lastOrError.allocated
        destination = new RefDestination(filesystem)
        push = mkResultPush(Map(1 -> testTable1, 2 -> testTable2), Map(DestinationId -> destination), jm, mkEvaluator {
          case "queryFoo" =>
            Stream.eval_(refFoo.set("Started")) ++ Stream("resultFoo") ++ awaitS ++ Stream.eval_(refFoo.set("Finished"))
          case "queryBar" =>
            Stream.eval_(refBar.set("Started")) ++ Stream("resultBar") ++ awaitS ++ Stream.eval_(refBar.set("Finished"))
        })
        _ <- push.start(1, DestinationId, path1, ResultType.Csv[IO](), None)
        _ <- push.start(2, DestinationId, path2, ResultType.Csv[IO](), None)
        _ <- latchGet(refFoo, "Started")
        _ <- latchGet(refBar, "Started")
        _ <- push.cancelAll
        barCanceled <- (latchGet(refBar, "Finished") >> IO(ko("queryBar not cancelled"))).timeoutTo(WorkTime * 2, IO(ok))
        fooCanceled <- (latchGet(refFoo, "Finished") >> IO(ko("queryFoo not cancelled"))).timeoutTo(WorkTime * 2, IO(ok))
      } yield barCanceled and fooCanceled
    }

    "fails with ResultPush.DestinationNotFound with an unknown destination id" >>* {
      val testTable = TableRef(TableName("foo"), "query", List())
      val UnknownDestinationId = 99

      for {
        filesystem <- Ref.of[IO, Filesystem](Map.empty)
        (jm, _) <- JobManager[IO, Int, Nothing]().compile.resource.lastOrError.allocated
        destination = new RefDestination(filesystem)
        push = mkResultPush(Map(TableId -> testTable), Map(), jm, mkEvaluator(_ => Stream.empty))
        pushRes <- push.start(TableId, UnknownDestinationId, ResourcePath.root(), ResultType.Csv[IO](), None)
      } yield {
        pushRes must beAbnormal(ResultPushError.DestinationNotFound(UnknownDestinationId))
      }
    }

    "fails with ResultPush.TableNotFound with an unknown table id" >>* {
      val testTable = TableRef(TableName("foo"), "query", List())
      val UnknownTableId = 99

      for {
        filesystem <- Ref.of[IO, Filesystem](Map.empty)
        (jm, cleanup) <- JobManager[IO, Int, Nothing]().compile.resource.lastOrError.allocated
        destination = new RefDestination(filesystem)
        push = mkResultPush(Map(), Map(DestinationId -> destination), jm, mkEvaluator(_ => Stream.empty))
        pushRes <- push.start(UnknownTableId, DestinationId, ResourcePath.root(), ResultType.Csv[IO](), None)
      } yield {
        pushRes must beAbnormal(ResultPushError.TableNotFound(UnknownTableId))
      }
    }
  }
}
