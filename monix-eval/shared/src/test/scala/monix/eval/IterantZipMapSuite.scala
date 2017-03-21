/*
 * Copyright (c) 2014-2017 by its authors. Some rights reserved.
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

import monix.execution.cancelables.BooleanCancelable
import monix.execution.exceptions.DummyException
import org.scalacheck.Test
import org.scalacheck.Test.Parameters

object IterantZipMapSuite extends BaseTestSuite {
  override lazy val checkConfig: Parameters =
    Test.Parameters.default.withMaxSize(256)

  test("Iterant.zipMap equivalence with List.zip") { implicit s =>
    check3 { (stream1: Iterant[Int], stream2: Iterant[Int], f: (Int, Int) => Long) =>
      val received = stream1.zipMap(stream2)(f).toListL
      val expected = Task.zipMap2(stream1.toListL, stream2.toListL)((l1, l2) => l1.zip(l2).map { case (a,b) => f(a,b) })
      received === expected
    }
  }

  test("Iterant.zipMap protects against user error") { implicit s =>
    check2{ (s1: Iterant[Int], s2: Iterant[Int]) =>
      val dummy = DummyException("dummy")
      val f = (x: Int, y: Int) => (throw dummy) : Long
      val suffix = Iterant.now(1)
      val stream1 = s1 ++ suffix
      val stream2 = s2 ++ suffix
      val received = stream1.zipMap(stream2)(f).toListL
      received === Task.raiseError(dummy)
    }
  }

  test("Iterant.zipMap triggers early stop on user error") { implicit s =>
    check3 { (s1: Iterant[Int], s2: Iterant[Int], idx: Int) =>
      val dummy = DummyException("dummy")
      val f = (x: Int, y: Int) => (throw dummy) : Long

      val suffix = math.abs(idx % 3) match {
        case 0 => Iterant.fromIterable(List(1,2,3))
        case 1 => Iterant.fromIterator(List(1,2,3).iterator)
        case 2 => Iterant.nextS(1, Task.now(Iterant.now(2)), Task.unit)
      }

      val c1 = BooleanCancelable()
      val stream1 = (s1 ++ suffix).doOnEarlyStop(Task.eval(c1.cancel()))
      val c2 = BooleanCancelable()
      val stream2 = (s2 ++ suffix).doOnEarlyStop(Task.eval(c2.cancel()))

      stream1.zipMap(stream2)(f).toListL.runAsync
      s.tick()

      c1.isCanceled && c2.isCanceled
    }
  }
}
