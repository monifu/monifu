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
import monix.execution.internal.Platform
import monix.tail.batches.BatchCursor
import org.scalacheck.Test
import org.scalacheck.Test.Parameters
import scala.annotation.tailrec

object IterantDropWhileSuite extends BaseTestSuite {
  override lazy val checkConfig: Parameters = {
    if (Platform.isJVM)
      Test.Parameters.default.withMaxSize(256)
    else
      Test.Parameters.default.withMaxSize(32)
  }

  @tailrec
  def dropFromList(p: Int => Boolean)(list: List[Int]): List[Int] =
    list match {
      case x :: xs =>
        if (p(x)) dropFromList(p)(xs)
        else list
      case Nil =>
        Nil
    }

  test("Iterant.dropWhile equivalence with List.dropWhile") { implicit s =>
    check3 { (list: List[Int], idx: Int, p: Int => Boolean) =>
      val iter = arbitraryListToIterant[Coeval, Int](list, math.abs(idx) + 1, allowErrors = false)
      val stream = iter ++ Iterant[Coeval].of(1, 2, 3)
      val received = stream.dropWhile(p).toListL.runTry()
      val expected = stream.toListL.map(dropFromList(p)).runTry()

      if (received != expected) {
        println(s"$received != $expected")
      }

      received <-> expected
    }
  }

  test("Iterant.dropWhile protects against broken batches") { implicit s =>
    check1 { (iter: Iterant[Task, Int]) =>
      val dummy = DummyException("dummy")
      val suffix = Iterant[Task].nextBatchS[Int](new ThrowExceptionBatch(dummy), Task.now(Iterant[Task].empty))
      val stream = iter.onErrorIgnore ++ suffix
      val received = stream.dropWhile(_ => true)
      received <-> Iterant[Task].haltS[Int](Some(dummy))
    }
  }

  test("Iterant.dropWhile protects against broken cursors") { implicit s =>
    check1 { (iter: Iterant[Task, Int]) =>
      val dummy = DummyException("dummy")
      val suffix = Iterant[Task].nextCursorS[Int](new ThrowExceptionCursor(dummy), Task.now(Iterant[Task].empty))
      val stream = iter.onErrorIgnore ++ suffix
      val received = stream.dropWhile(_ => true)
      received <-> Iterant[Task].haltS[Int](Some(dummy))
    }
  }

  test("Iterant.dropWhile protects against user code") { implicit s =>
    check1 { (iter: Iterant[Task, Int]) =>
      val dummy = DummyException("dummy")
      val suffix = Iterant[Task].nextCursorS[Int](BatchCursor(1,2,3), Task.now(Iterant[Task].empty))
      val stream = iter.onErrorIgnore ++ suffix
      val received = stream.dropWhile(_ => throw dummy)
      received <-> Iterant[Task].haltS[Int](Some(dummy))
    }
  }

  test("Iterant.dropWhile preserves the source earlyStop") { implicit s =>
    var effect = 0
    val stop = Coeval.eval(effect += 1)
    val source = Iterant[Coeval].nextCursorS(BatchCursor(1,2,3), Coeval.now(Iterant[Coeval].empty[Int])).guarantee(stop)
    val stream = source.dropWhile(_ => true)
    stream.completedL.value()
    assertEquals(effect, 1)
  }
}