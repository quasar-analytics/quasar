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

package quasar.connector

import slamdata.Predef._

import quasar.api.destination.ResultType
import quasar.api.resource.ResourcePath

trait ResultSink[F[_]] {
  type RT <: ResultType[F]
  val resultType: RT

  def apply(dst: ResourcePath, result: resultType.T): F[Unit]
}

object ResultSink {
  type Aux[F[_], RT0 <: ResultType[F]] = ResultSink[F] {
    type RT = RT0
  }

  object Csv {
    // TODO: make @unchecked unnecessary
    def unapply[F[_]](sink: ResultSink[F]): Option[ResultSink.Aux[F, ResultType.Csv[F]]] =
      (sink, sink.resultType) match {
        case (rs: ResultSink.Aux[F, ResultType.Csv[F]] @unchecked, ResultType.Csv()) =>
          Some(rs)
        case _ =>
          None
      }
  }
}
