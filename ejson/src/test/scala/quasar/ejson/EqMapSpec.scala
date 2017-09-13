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

package quasar.ejson

import slamdata.Predef.{Int => SInt, _}

import org.specs2.scalaz._
import scalaz._, Scalaz._
import scalaz.scalacheck.ScalazProperties._

class EqMapSpec extends Spec with EqMapArbitrary {
  checkAll(equal.laws[EqMap[SInt, String]])

  "operations" >> {
    "insert" >> prop { (m: EqMap[SInt, String], k: SInt, v: String) =>
      m.insert(k, v).lookup(k) ≟ Some(v)
    }

    "delete" >> prop { m: EqMap[SInt, String] =>
      m.toList.headOption.flatMap(kv => m.delete(kv._1).lookup(kv._1)) ≟ None
    }
  }
}
