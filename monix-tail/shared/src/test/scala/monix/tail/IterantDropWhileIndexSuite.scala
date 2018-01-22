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
import scala.collection.immutable.Stream.Empty

object IterantDropWhileIndexSuite extends BaseTestSuite {
  override lazy val checkConfig: Parameters = {
    if (Platform.isJVM)
      Test.Parameters.default.withMaxSize(256)
    else
      Test.Parameters.default.withMaxSize(32)
  }

  @tailrec
  def dropWhileWithIndex(list: List[Int], index: Int)(p: (Int, Int) => Boolean): List[Int] = {
    list match {
      case x :: xs =>
        if (p(x, index)) dropWhileWithIndex(xs, index + 1)(p)
        else list
      case Nil => Nil
    }
  }

  @tailrec
  def dropWhileWithIndex(list: Stream[Int], index: Int)(p: (Int, Int) => Boolean): Stream[Int] = {
    list match {
      case Empty => Empty
      case x #:: xs =>
        if (p(x, index)) dropWhileWithIndex(xs, index + 1)(p)
        else list
    }
  }

  test("Iterant.dropWhileWithIndex equivalence with List.dropWhileWithIndex") { implicit s =>
    check3 { (list: List[Int], idx: Int, p: (Int, Int) => Boolean) =>
      val stream = arbitraryListToIterant[Task, Int](list, math.abs(idx) + 1, allowErrors = false)
      stream.dropWhileWithIndex(p).toListL <-> stream.toListL.map(dropWhileWithIndex(_, 0)(p))
    }
  }

  test("Iterant.dropWhileWithIndex protects against broken batches") { implicit s =>
    check1 { (iter: Iterant[Task, Int]) =>
      val dummy = DummyException("dummy")
      val suffix = Iterant[Task].nextBatchS[Int](new ThrowExceptionBatch(dummy), Task.now(Iterant[Task].empty), Task.unit)
      val stream = iter.onErrorIgnore ++ suffix
      val received = stream.dropWhileWithIndex((_, _) => true)
      received <-> Iterant[Task].haltS[Int](Some(dummy))
    }
  }

  test("Iterant.dropWhileWithIndex protects against broken cursors") { implicit s =>
    check1 { (iter: Iterant[Task, Int]) =>
      val dummy = DummyException("dummy")
      val suffix = Iterant[Task].nextCursorS[Int](new ThrowExceptionCursor(dummy), Task.now(Iterant[Task].empty), Task.unit)
      val stream = iter.onErrorIgnore ++ suffix
      val received = stream.dropWhileWithIndex((_, _) => true)
      received <-> Iterant[Task].haltS[Int](Some(dummy))
    }
  }

  test("Iterant.dropWhileWithIndex protects against user code") { implicit s =>
    check1 { (iter: Iterant[Task, Int]) =>
      val dummy = DummyException("dummy")
      val suffix = Iterant[Task].nextCursorS[Int](BatchCursor(1, 2, 3), Task.now(Iterant[Task].empty), Task.unit)
      val stream = iter.onErrorIgnore ++ suffix
      val received = stream.dropWhileWithIndex((_, _) => throw dummy)
      received <-> Iterant[Task].haltS[Int](Some(dummy))
    }
  }

  test("Iterant.dropWhileWithIndex preserves the source earlyStop") { implicit s =>
    var effect = 0
    val stop = Coeval.eval(effect += 1)
    val source = Iterant[Coeval].nextCursorS(BatchCursor(1, 2, 3), Coeval.now(Iterant[Coeval].empty[Int]), stop)
    val stream = source.dropWhileWithIndex((_, _) => true)
    stream.earlyStop.value
    assertEquals(effect, 1)
  }

  test("Iterant.dropWhileWithIndex works for infinite cursors") { implicit s =>
    check3 { (el: Int, p: (Int, Int) => Boolean, _: Int) =>
      val stream = Iterant[Coeval].nextCursorS(BatchCursor.continually(el), Coeval.now(Iterant[Coeval].empty[Int]), Coeval.unit)
      val received = stream.dropWhileWithIndex(p).take(1).toListL
      val expected = Coeval(dropWhileWithIndex(Stream.continually(el), 0)(p).take(1).toList)

      received <-> expected
    }
  }
}