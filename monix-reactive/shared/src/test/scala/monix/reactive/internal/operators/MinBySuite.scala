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

import monix.execution.Ack.Continue
import monix.reactive.{Observable, Observer}
import scala.concurrent.duration.Duration.Zero

object MinBySuite extends BaseOperatorSuite {
  def createObservable(sourceCount: Int) = Some {
    val o = Observable.range(sourceCount, 0, -1).minBy(x => sourceCount - x)
    Sample(o, count(sourceCount), sum(sourceCount), Zero, Zero)
  }

  def observableInError(sourceCount: Int, ex: Throwable) = Some {
    val o = Observable.range(0, sourceCount).endWithError(ex).minBy(x => x)
    Sample(o, 0, 0, Zero, Zero)
  }

  def count(sourceCount: Int) = 1
  def sum(sourceCount: Int) = sourceCount

  def brokenUserCodeObservable(sourceCount: Int, ex: Throwable) = Some {
    val o = Observable
      .range(0, sourceCount + 1)
      .minBy(x => if (x == sourceCount - 1) throw ex else x)

    Sample(o, 0, 0, Zero, Zero)
  }

  override def cancelableObservables() = {
    import scala.concurrent.duration._
    val o = Observable.now(1L).delayOnNext(1.second).minBy(x => x)
    Seq(Sample(o, 0, 0, 0.seconds, 0.seconds))
  }

  test("empty observable should be empty") { implicit s =>
    val source: Observable[Long] = Observable.empty
    var received = 0
    var wasCompleted = false

    source
      .minBy(x => 100 - x)
      .unsafeSubscribeFn(new Observer[Long] {
        def onNext(elem: Long) = { received += 1; Continue }
        def onError(ex: Throwable) = ()
        def onComplete() = { wasCompleted = true }
      })

    assertEquals(received, 0)
    assert(wasCompleted)
  }
}
