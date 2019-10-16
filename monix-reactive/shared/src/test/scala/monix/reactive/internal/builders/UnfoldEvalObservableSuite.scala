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

package monix.reactive.internal.builders

import cats.effect.IO
import cats.laws._
import cats.laws.discipline._
import monix.eval.Task
import monix.execution.Ack.Continue
import monix.execution.exceptions.DummyException
import monix.execution.internal.Platform.recommendedBatchSize
import monix.reactive.observers.Subscriber
import monix.reactive.{BaseTestSuite, Observable}

import scala.concurrent.duration.MILLISECONDS

object UnfoldEvalObservableSuite extends BaseTestSuite {

  test("unfoldEval should be exception-proof") { implicit s =>
    val dummy = DummyException("dummy")
    var received = 0

    Observable.unfoldEval(0)(i => if (i < 20) Task.now(Some((i, i + 1))) else throw dummy).subscribe { _: Int =>
      received += 1
      Continue
    }

    assertEquals((0 until received).toList, (0 to 19).toList)
    assertEquals(s.state.lastReportedError, dummy)
  }

  test("unfoldEval and fromAsyncStateAction results should be equal given generated inputs") { implicit s =>
    check2 { (s: Int, i: Int) =>
      val seed = s % (recommendedBatchSize * 2)
      val n = i    % (recommendedBatchSize * 2)

      val f: Int => Task[Option[(Int, Int)]] = i => if (i < n) Task.delay(Some((i, i + 1))) else Task.now(None)
      val f2: Int => Task[(Int, Int)] = i => Task.delay((i, i + 1))

      Observable.unfoldEval(seed)(f).toListL <-> Observable.fromAsyncStateAction(f2)(seed).takeWhile(_ < n).toListL
    }
  }

  test("unfoldEval should be cancelable") { implicit s =>
    var wasCompleted = false
    var sum = 0

    val cancelable = Observable
      .unfoldEval(s.clockMonotonic(MILLISECONDS))(intNowOption)
      .unsafeSubscribeFn(new Subscriber[Int] {
        implicit val scheduler = s

        def onNext(elem: Int) = {
          sum += 1
          Continue
        }

        def onComplete() = wasCompleted = true

        def onError(ex: Throwable) = wasCompleted = true
      })

    cancelable.cancel()

    s.tick()

    assertEquals(sum, s.executionModel.recommendedBatchSize / 2)
    assert(!wasCompleted)
  }

  test("unfoldEvalF should be exception-proof") { implicit s =>
    val dummy = DummyException("dummy")
    var received = 0

    Observable.unfoldEvalF(0)(i => if (i < 20) IO(Option((i, i + 1))) else throw dummy).subscribe { _: Int =>
      received += 1
      Continue
    }

    assertEquals((0 until received).toList, (0 to 19).toList)
    assertEquals(s.state.lastReportedError, dummy)
  }

  test("unfoldEvalF and fromAsyncStateActionF results should be equal given generated inputs") { implicit s =>
    check2 { (s: Int, i: Int) =>
      val seed = s % (recommendedBatchSize * 2)
      val n = i    % (recommendedBatchSize * 2)

      val f: Int => IO[Option[(Int, Int)]] = i => if (i < n) IO.delay(Some((i, i + 1))) else IO.pure(None)
      val f2: Int => IO[(Int, Int)] = i => IO.delay((i, i + 1))

      Observable.unfoldEvalF(seed)(f).toListL <-> Observable.fromAsyncStateActionF(f2)(seed).takeWhile(_ < n).toListL
    }
  }

  test("unfoldEvalF should be cancelable") { implicit s =>
    var wasCompleted = false
    var sum = 0

    val cancelable = Observable
      .unfoldEvalF(s.clockMonotonic(MILLISECONDS))(intOptionIO)
      .unsafeSubscribeFn(new Subscriber[Int] {
        implicit val scheduler = s

        def onNext(elem: Int) = {
          sum += 1
          Continue
        }

        def onComplete() = wasCompleted = true

        def onError(ex: Throwable) = wasCompleted = true
      })

    cancelable.cancel()

    s.tick()

    assertEquals(sum, s.executionModel.recommendedBatchSize / 2)
    assert(!wasCompleted)
  }

  def intNowOption(seed: Long): Task[Option[(Int, Long)]] = Task.now(Option(int(seed)))
  def intOptionIO(seed: Long): IO[Option[(Int, Long)]] = IO.delay(Option(int(seed)))
  def intOption(seed: Long): Option[(Int, Long)] = Option(int(seed))

  def int(seed: Long): (Int, Long) = {
    // `&` is bitwise AND. We use the current seed to generate a new seed.
    val newSeed = (seed * 0X5DEECE66DL + 0XBL) & 0XFFFFFFFFFFFFL
    // The next state, which is an `RNG` instance created from the new seed.
    val nextRNG = newSeed
    // `>>>` is right binary shift with zero fill. The value `n` is our new pseudo-random integer.
    val n = (newSeed >>> 16).toInt
    // The return value is a tuple containing both a pseudo-random integer and the next `RNG` state.
    (n, nextRNG)
  }
}
