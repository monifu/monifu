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
import cats.syntax.all._
import monix.eval.Coeval
import monix.execution.exceptions.DummyException
import scala.util.Failure

object IterantCompleteLSuite extends BaseTestSuite {
  test("completeL works") { implicit s =>
    check1 { (iter: Iterant[Coeval, Int]) =>
      var effect = 0
      val trigger = iter.onErrorIgnore ++ Iterant[Coeval].suspend {
        effect += 1
        Iterant[Coeval].empty[Int]
      }

      val fa = trigger.completeL *> Coeval.eval(effect)
      fa <-> Coeval.now(1)
    }
  }

  test("BatchCursor.completeL protects against errors") { implicit s =>
    val dummy = DummyException("dummy")
    val cursor = ThrowExceptionCursor[Int](dummy)

    val fa = Iterant[Coeval].nextCursorS(
      cursor,
      Coeval(Iterant[Coeval].empty[Int]),
      Coeval.unit
    )

    assertEquals(fa.completeL.runTry, Failure(dummy))
  }

  test("Batch.completeL protects against errors") { implicit s =>
    val dummy = DummyException("dummy")
    val batch = ThrowExceptionBatch[Int](dummy)

    val fa = Iterant[Coeval].nextBatchS(
      batch,
      Coeval(Iterant[Coeval].empty[Int]),
      Coeval.unit
    )

    assertEquals(fa.completeL.runTry, Failure(dummy))
  }
}
