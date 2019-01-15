/*
 * Copyright (c) 2014-2019 by The Monix Project Developers.
 * See the project homepage at: https://monix.io
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

package monix.tail

import cats.Monoid
import cats.laws._
import cats.laws.discipline._
import monix.eval.Coeval

object IterantScanMapSuite extends BaseTestSuite {

  test("Iterant.scanMap equivalence to Iterant.scan") { implicit s =>
    check1 { (source: Iterant[Coeval, Int]) =>
      val scanned1 = source.scanMap(x => x)
      val scanned2 = source.scan(0)(_ + _)

      val fa1 = scanned1
        .takeWhile(_ < 10)

      val fa2 = scanned2
        .takeWhile(_ < 10)

      fa1.toListL <-> fa2.toListL
    }
  }

  test("Iterant.scanMap0 starts with empty element") { implicit s =>
    check1 { (source: Iterant[Coeval, Int]) =>
      source.scanMap0(x => x).headOptionL <-> Coeval(Some(Monoid[Int].empty))
    }
  }
}