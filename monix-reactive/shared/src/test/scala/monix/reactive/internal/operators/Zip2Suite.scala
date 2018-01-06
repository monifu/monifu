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

package monix.reactive.internal.operators

import monix.execution.Ack.Continue
import monix.execution.FutureUtils.extensions._
import monix.execution.exceptions.DummyException
import monix.reactive.subjects.PublishSubject
import monix.reactive.{Observable, Observer}
import scala.concurrent.Future
import scala.concurrent.duration.Duration.Zero
import scala.concurrent.duration._

object Zip2Suite extends BaseOperatorSuite {
  def createObservable(sourceCount: Int) = Some {
    val o1 = Observable.range(0, sourceCount)
    val o2 = Observable.range(0, sourceCount)

    val o = Observable.zipMap2(o1, o2) { (x1, x2) => x1 + x2 }
    Sample(o, count(sourceCount), sum(sourceCount), Zero, Zero)
  }

  def count(sourceCount: Int) = sourceCount
  def sum(sourceCount: Int) = sourceCount * (sourceCount - 1)

  def observableInError(sourceCount: Int, ex: Throwable) = Some {
    val o1 = createObservableEndingInError(Observable.range(0, sourceCount), ex)
    val o2 = createObservableEndingInError(Observable.range(0, sourceCount), ex)

    val o = Observable.zipMap2(o1, o2) { (x1, x2) => x1 + x2 }
    Sample(o, count(sourceCount-1), sum(sourceCount-1), Zero, Zero)
  }

  def brokenUserCodeObservable(sourceCount: Int, ex: Throwable) = Some {
    val o1 = Observable.range(0, sourceCount)
    val o2 = Observable.range(0, sourceCount+100)

    val o = Observable.zipMap2(o1, o2) { (x1, x2) =>
      if (x2 < sourceCount-1) x1 + x2 else throw ex
    }

    Sample(o, count(sourceCount-1), sum(sourceCount-1), Zero, Zero)
  }

  override def cancelableObservables(): Seq[Sample] = {
    val sample1 = {
      val o1 = Observable.range(0, 10).delayOnNext(1.second)
      val o2 = Observable.range(0, 10).delayOnNext(1.second)
      Observable.zipMap2(o1, o2)(_+_)
    }

    Seq(Sample(sample1, 0, 0, 0.seconds, 0.seconds))
  }

  test("self starts before other and finishes before other") { implicit s =>
    val obs1 = PublishSubject[Int]()
    val obs2 = PublishSubject[Int]()

    var received = (0, 0)
    var wasCompleted = false

    obs1.zip(obs2).unsafeSubscribeFn(new Observer[(Int, Int)] {
      def onNext(elem: (Int, Int)) = {
        received = elem
        Continue
      }

      def onError(ex: Throwable) = ()
      def onComplete() = wasCompleted = true
    })

    obs1.onNext(1); s.tick()
    assertEquals(received, (0,0))
    obs2.onNext(2); s.tick()
    assertEquals(received, (1,2))

    obs2.onNext(4); s.tick()
    assertEquals(received, (1,2))
    obs1.onNext(3); s.tick()
    assertEquals(received, (3,4))

    obs1.onComplete()
    s.tick()
    assert(wasCompleted)
  }

  test("self signals error and interrupts the stream before it starts") { implicit s =>
    val obs1 = PublishSubject[Int]()
    val obs2 = PublishSubject[Int]()

    var wasThrown: Throwable = null
    var wasCanceled = false
    var received = (0,0)

    obs1.zip(obs2.doOnEarlyStop { () => wasCanceled = true })
      .unsafeSubscribeFn(new Observer[(Int, Int)] {
        def onNext(elem: (Int, Int)) = { received = elem; Continue }
        def onError(ex: Throwable) = wasThrown = ex
        def onComplete() = ()
      })

    obs1.onError(DummyException("dummy"))
    assertEquals(wasThrown, DummyException("dummy"))

    obs2.onNext(2); s.tickOne()
    assertEquals(received, (0,0))
    assert(wasCanceled)
  }

  test("other signals error and interrupts the stream before it starts") { implicit s =>
    val obs1 = PublishSubject[Int]()
    val obs2 = PublishSubject[Int]()

    var wasThrown: Throwable = null
    var wasCanceled = false
    var received = (0,0)

    obs2.doOnEarlyStop { () => wasCanceled = true }.zip(obs1)
      .unsafeSubscribeFn(new Observer[(Int, Int)] {
      def onNext(elem: (Int, Int)) = { received = elem; Continue }
      def onError(ex: Throwable) = wasThrown = ex
      def onComplete() = ()
    })

    obs1.onError(DummyException("dummy"))
    assertEquals(wasThrown, DummyException("dummy"))

    obs2.onNext(2); s.tickOne()
    assertEquals(received, (0,0))
    assert(wasCanceled)
  }

  test("should not back-pressure self.onError") { implicit s =>
    val obs1 = PublishSubject[Int]()
    val obs2 = PublishSubject[Int]()

    var wasThrown: Throwable = null

    obs1.zip(obs2).unsafeSubscribeFn(new Observer[(Int, Int)] {
      def onNext(elem: (Int, Int)) =
        Future.delayedResult(1.second)(Continue)
      def onComplete() = ()
      def onError(ex: Throwable) =
        wasThrown = ex
    })

    obs1.onNext(1)
    obs2.onNext(2)
    obs1.onError(DummyException("dummy"))

    s.tick()
    assertEquals(wasThrown, DummyException("dummy"))
    s.tick(1.second)
  }

  test("should not back-pressure other.onError") { implicit s =>
    val obs1 = PublishSubject[Int]()
    val obs2 = PublishSubject[Int]()

    var wasThrown: Throwable = null

    obs1.zip(obs2).unsafeSubscribeFn(new Observer[(Int, Int)] {
      def onNext(elem: (Int, Int)) =
        Future.delayedResult(1.second)(Continue)
      def onComplete() = ()
      def onError(ex: Throwable) =
        wasThrown = ex
    })

    obs1.onNext(1)
    obs2.onNext(2)
    obs2.onError(DummyException("dummy"))

    s.tick()
    assertEquals(wasThrown, DummyException("dummy"))
    s.tick(1.second)
  }
}