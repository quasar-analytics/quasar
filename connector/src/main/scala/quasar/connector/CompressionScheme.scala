/*
 * Copyright 2020 Precog Data
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

import cats.{Show, Eq}
import argonaut.{Argonaut, CodecJson, DecodeResult}, Argonaut._

sealed trait CompressionScheme extends Product with Serializable

object CompressionScheme {
  case object Gzip extends CompressionScheme
  case object Zip extends CompressionScheme

  implicit val equal: Eq[CompressionScheme] =
    Eq.fromUniversalEquals

  implicit val show: Show[CompressionScheme] =
    Show.fromToString

  implicit val codecCompressionScheme: CodecJson[CompressionScheme] = CodecJson({
    case Gzip => "gzip".asJson
    case Zip => "zip".asJson
  }, (c => c.as[String].flatMap {
    case "gzip" => DecodeResult.ok(Gzip)
    case "zip" => DecodeResult.ok(Zip)
    case other => DecodeResult.fail(s"Unrecognized compression scheme: $other", c.history)
  }))
}
