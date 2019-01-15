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

package monix.reactive.internal.operators

import monix.execution.Ack
import monix.execution.Ack.Continue
import monix.reactive.{Observable, Observer}
import scala.concurrent.duration._
import scala.concurrent.duration.Duration.Zero
import scala.concurrent.{Future, Promise}

object TakeLeftSuite extends BaseOperatorSuite {
  def sum(sourceCount: Int): Long = sourceCount.toLong * (sourceCount + 1) / 2
  def count(sourceCount: Int) = sourceCount

  def createObservable(sourceCount: Int) = {
    require(sourceCount > 0, "sourceCount should be strictly positive")
    Some {
      val o = if (sourceCount == 1)
        Observable.range(1, 10).take(1)
      else
        Observable.range(1, sourceCount * 2).take(sourceCount)

      Sample(o, count(sourceCount), sum(sourceCount), Zero, Zero)
    }
  }

  def observableInError(sourceCount: Int, ex: Throwable) = {
    require(sourceCount > 0, "sourceCount should be strictly positive")
    Some {
      val o = if (sourceCount == 1)
        createObservableEndingInError(Observable.range(1, 2), ex)
          .take(2)
      else
        createObservableEndingInError(Observable.range(1, sourceCount), ex)
          .take(sourceCount)

      Sample(o, count(sourceCount-1), sum(sourceCount-1), Zero, Zero)
    }
  }

  def brokenUserCodeObservable(sourceCount: Int, ex: Throwable) =
    None



  test("should not do back-pressure for onComplete, for 1 element") { implicit s =>
    val p = Promise[Continue.type]()
    var wasCompleted = false

    createObservable(1) match {
      case Some(Sample(obs, count, sum, waitForFirst, waitForNext)) =>
        var onNextReceived = false

        obs.unsafeSubscribeFn(new Observer[Long] {
          def onNext(elem: Long): Future[Ack] = { onNextReceived = true; p.future }
          def onError(ex: Throwable): Unit = throw new IllegalStateException()
          def onComplete(): Unit = wasCompleted = true
        })

        assert(wasCompleted)
        s.tick(waitForFirst)
        assert(onNextReceived)
        p.success(Continue)
        s.tick(waitForNext)
    }
  }

  test("take(0) shouldn't subscribe to the source at all") { implicit s =>
    var counter = 0

    def inc() = {
      counter += 1
      1
    }

    val task = Observable.repeatEval(inc()).take(0).toListL
    task.runToFuture

    s.tick()
    assertEquals(counter, 0)
  }

  override def cancelableObservables() = {
    val o = Observable.range(1, 10).delayOnNext(1.second).take(1)
    Seq(Sample(o,0,0,0.seconds,0.seconds))
  }
}
