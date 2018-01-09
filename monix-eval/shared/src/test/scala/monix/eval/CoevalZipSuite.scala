/*
 * Copyright (c) 2014-2018 by The Monix Project Developers.
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

import scala.util.Success

object CoevalZipSuite extends BaseTestSuite{
  test("Coeval#zip works") { implicit s =>
    def n(n: Int) = Coeval.now(n)
    val t = n(1).zip(n(2))
    assertEquals(t.runTry, Success((1,2)))
  }

  test("Coeval#zipMap works") { implicit s =>
    def n(n: Int) = Coeval.now(n)
    val t = n(1).zipMap(n(2))((_,_))
    assertEquals(t.runTry, Success((1,2)))
  }

  test("Coeval#zip2 works") { implicit s =>
    def n(n: Int) = Coeval.now(n)
    val t = Coeval.zip2(n(1),n(2))
    assertEquals(t.runTry, Success((1,2)))
  }

  test("Coeval#zipWith2 works") { implicit s =>
    def n(n: Int) = Coeval.now(n)
    val t = Coeval.map2(n(1),n(2))((_,_))
    assertEquals(t.runTry, Success((1,2)))
  }

  test("Coeval#zip3 works") { implicit s =>
    def n(n: Int) = Coeval.now(n)
    val t = Coeval.zip3(n(1),n(2),n(3))
    assertEquals(t.runTry, Success((1,2,3)))
  }

  test("Coeval#zipMap3 works") { implicit s =>
    def n(n: Int) = Coeval.now(n)
    val t = Coeval.map3(n(1),n(2),n(3))((_,_,_))
    assertEquals(t.runTry, Success((1,2,3)))
  }

  test("Coeval#zip4 works") { implicit s =>
    def n(n: Int) = Coeval.now(n)
    val t = Coeval.zip4(n(1),n(2),n(3),n(4))
    assertEquals(t.runTry, Success((1,2,3,4)))
  }

  test("Coeval#zipMap4 works") { implicit s =>
    def n(n: Int) = Coeval.now(n)
    val t = Coeval.map4(n(1),n(2),n(3),n(4))((_,_,_,_))
    assertEquals(t.runTry, Success((1,2,3,4)))
  }

  test("Coeval#zip5 works") { implicit s =>
    def n(n: Int) = Coeval.now(n)
    val t = Coeval.zip5(n(1),n(2),n(3),n(4),n(5))
    assertEquals(t.runTry, Success((1,2,3,4,5)))
  }

  test("Coeval#zipMap5 works") { implicit s =>
    def n(n: Int) = Coeval.now(n)
    val t = Coeval.map5(n(1),n(2),n(3),n(4),n(5))((_,_,_,_,_))
    assertEquals(t.runTry, Success((1,2,3,4,5)))
  }

  test("Coeval#zip6 works") { implicit s =>
    def n(n: Int) = Coeval.now(n)
    val t = Coeval.zip6(n(1),n(2),n(3),n(4),n(5),n(6))
    assertEquals(t.runTry, Success((1,2,3,4,5,6)))
  }

  test("Coeval#zipMap6 works") { implicit s =>
    def n(n: Int) = Coeval.now(n)
    val t = Coeval.map6(n(1),n(2),n(3),n(4),n(5),n(6))((_,_,_,_,_,_))
    assertEquals(t.runTry, Success((1,2,3,4,5,6)))
  }
}
