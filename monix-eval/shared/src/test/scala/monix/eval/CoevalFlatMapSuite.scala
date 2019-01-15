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

package monix.eval

import cats.laws._
import cats.laws.discipline._

import monix.execution.exceptions.DummyException
import scala.util.Success

object CoevalFlatMapSuite extends BaseTestSuite {
  test("transformWith equivalence with flatMap") { implicit s =>
    check2 { (fa: Coeval[Int], f: Int => Coeval[Int]) =>
      fa.redeemWith(Coeval.raiseError, f) <-> fa.flatMap(f)
    }
  }

  test("transform equivalence with map") { implicit s =>
    check2 { (fa: Coeval[Int], f: Int => Int) =>
      fa.redeem(ex => throw ex, f) <-> fa.map(f)
    }
  }

  test("transformWith can recover") { implicit s =>
    val dummy = new DummyException("dummy")
    val coeval = Coeval.raiseError[Int](dummy).redeemWith(_ => Coeval.now(1), Coeval.now)
    assertEquals(coeval.runTry(), Success(1))
  }

  test("transform can recover") { implicit s =>
    val dummy = new DummyException("dummy")
    val coeval = Coeval.raiseError[Int](dummy).redeem(_ => 1, identity)
    assertEquals(coeval.runTry(), Success(1))
  }
}
