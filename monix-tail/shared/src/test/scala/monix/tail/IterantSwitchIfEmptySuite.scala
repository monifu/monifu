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
import monix.eval.Coeval
import monix.execution.cancelables.BooleanCancelable
import monix.execution.exceptions.DummyException
import monix.tail.batches.{EmptyBatch, EmptyCursor}


object IterantSwitchIfEmptySuite extends BaseTestSuite {
  val backupStream: Iterant[Coeval, Int] = Iterant[Coeval].of(42)
  val emptyInts: Iterant[Coeval, Int] = Iterant[Coeval].empty[Int]

  def assertChoosesArg(source: Iterant[Coeval, Int]): Unit = {
    val target = source.switchIfEmpty(backupStream)
    assert(target.toListL.value == source.toListL.value)
  }

  def assertChoosesFallback(source: Iterant[Coeval, Int]): Unit = {
    val target = source.switchIfEmpty(backupStream)
    assert(target.toListL.value == backupStream.toListL.value)
  }

  test("Iterant.switchIfEmpty returns left stream on nonempty streams") { implicit s =>
    assertChoosesArg(Iterant[Coeval].of(1, 2, 3))
    assertChoosesArg(Iterant[Coeval].defer(Iterant[Coeval].of(1)))
  }

  test("Iterant.switchIfEmpty propagates error from left stream") { implicit s =>
    val ex = DummyException("dummy")
    val source = Iterant[Coeval].raiseError[Int](ex).switchIfEmpty(backupStream)
    intercept[DummyException] {
      source.toListL.value()
    }
  }

  test("Iterant.switchIfEmpty still executes left's earlyStop when switching") { implicit s =>
    val cancelable = BooleanCancelable()
    val left = emptyInts.doOnFinish(_ => Coeval { cancelable.cancel() })

    left.switchIfEmpty(backupStream).toListL.value()

    assert(cancelable.isCanceled)
  }

  test("Iterant.switchIfEmpty does not evaluate other stream effects when not switching") { implicit s =>
    val cancelable = BooleanCancelable()
    val right = emptyInts.doOnFinish(_ => Coeval { cancelable.cancel() })

    backupStream.switchIfEmpty(right).toListL.value()

    assert(!cancelable.isCanceled)
  }

  test("Iterant.switchIfEmpty chooses fallback for Halt with no errors") { implicit s =>
    assertChoosesFallback(Iterant[Coeval].haltS(None))
  }

  test("Iterant.switchIfEmpty chooses fallback for empty cursors") { implicit s =>
    assertChoosesFallback(Iterant[Coeval].nextCursorS(
      EmptyCursor, Coeval(emptyInts), Coeval.unit
    ))
  }

  test("Iterant.switchIfEmpty chooses fallback for empty batches") { implicit s =>
    assertChoosesFallback(Iterant[Coeval].nextBatchS(
      EmptyBatch, Coeval(emptyInts), Coeval.unit
    ))
  }

  test("Iterant.switchIfEmpty consistent with toListL.isEmpty") { implicit s =>
    check2 { (left: Iterant[Coeval, Int], right: Iterant[Coeval, Int]) =>
      val target = left.toListL.flatMap { list =>
        if (list.nonEmpty) left.toListL else right.toListL
      }
      left.switchIfEmpty(right).toListL <-> target
    }
  }

  test("Iterant.switchIfEmpty can handle broken batches") { implicit s =>
    val dummy = DummyException("dummy")
    val iterant = Iterant[Coeval].nextBatchS(
      ThrowExceptionBatch[Int](dummy), Coeval(emptyInts), Coeval.unit
    )
    assertEquals(
      iterant.switchIfEmpty(backupStream).toListL.runAttempt(),
      Left(dummy)
    )
  }
}
