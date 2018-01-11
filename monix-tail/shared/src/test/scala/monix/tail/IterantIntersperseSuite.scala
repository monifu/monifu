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

package monix.tail

import cats.laws._
import cats.laws.discipline._
import monix.eval.{Coeval, Task}
import monix.execution.exceptions.DummyException

object IterantIntersperseSuite extends BaseTestSuite {
  def intersperseList[A](separator: A)(xs: List[A]): List[A] = {
    xs.flatMap(e => e :: separator :: Nil).dropRight(1)
  }

  test("Iterant.intersperse(x) inserts the separator between elem pairs") { implicit s =>
    check2 { (stream: Iterant[Task, Int], elem: Int) =>
      stream.intersperse(elem).toListL <-> stream.toListL.map(intersperseList(elem))
    }
  }

  test("Iterant.intersperse(x) preserves the source earlyStop") { implicit s =>
    import IterantOfCoeval._
    var effect = 0
    val stop = Coeval(effect += 1)
    val source = suspendS(
      Coeval(of(1, 2, 3)),
      stop
    )
    val interspersed = source.intersperse(0)
    interspersed.earlyStop.value
    assertEquals(effect, 1)
  }

  test("Iterant.intersperse(x) protects against broken batches") { implicit s =>
    import IterantOfCoeval._

    val dummy = DummyException("dummy")
    val stream = of(1, 2, 3) ++ nextBatchS(
      ThrowExceptionBatch(dummy),
      Coeval(empty[Int]),
      Coeval.unit
    )

    assertEquals(
      stream.intersperse(0).toListL.runAttempt,
      Left(dummy)
    )
  }

  test("Iterant.intersperse(x) protects against broken cursors") { implicit s =>
    import IterantOfCoeval._

    val dummy = DummyException("dummy")
    val stream = of(1, 2, 3) ++ nextCursorS(
      ThrowExceptionCursor(dummy),
      Coeval(empty[Int]),
      Coeval.unit
    )

    assertEquals(
      stream.intersperse(0).toListL.runAttempt,
      Left(dummy)
    )
  }

  test("Iterant.intersperse(a, b, c) inserts the separator between elem pairs and adds prefix/suffix") { implicit s =>
    check3 { (stream: Iterant[Coeval, Int], prefix: Int, suffix: Int) =>
      val separator = prefix ^ suffix
      val source = stream.intersperse(prefix, separator, suffix)
      val target = stream.toListL
        .map(intersperseList(separator))
        .map(prefix +: _ :+ suffix)

      source.toListL <-> target
    }
  }
}
