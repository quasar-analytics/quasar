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

import scala._

trait ExternalCredentials[F[_]] extends Product with Serializable

object ExternalCredentials {
  /** Credentials that are expected not to expire. */
  final case class Perpetual[F[_]](credentials: Credentials)
      extends ExternalCredentials[F]

  /** Credentials that may expire, along with a way to renew them. */
  final case class Temporary[F[_]](credentials: F[Credentials], renew: F[Unit])
      extends ExternalCredentials[F]
}
