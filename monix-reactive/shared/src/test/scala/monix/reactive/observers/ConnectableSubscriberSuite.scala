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


package monix.reactive.observers

import minitest.TestSuite
import monix.execution.Ack.Continue
import monix.execution.Scheduler
import monix.execution.schedulers.TestScheduler
import monix.execution.exceptions.DummyException
import monix.reactive.Observer
import scala.collection.mutable.ArrayBuffer

object ConnectableSubscriberSuite extends TestSuite[TestScheduler] {
  def setup() = TestScheduler()
  def tearDown(s: TestScheduler) = {
    assert(s.state.tasks.isEmpty)
  }

  test("should block onNext until connect") { implicit s =>
    val received = ArrayBuffer.empty[Int]
    var wasCompleted = false

    val downstream = create(new Observer[Int] {
      def onError(ex: Throwable): Unit = ()
      def onComplete(): Unit = wasCompleted = true

      def onNext(elem: Int) = {
        received.append(elem)
        Continue
      }
    })

    val f = downstream.onNext(10)
    downstream.onComplete()
    s.tick()

    assert(!f.isCompleted, "f should not be completed")
    for (i <- 1 until 10) downstream.pushFirst(i)

    s.tick()
    assert(!f.isCompleted, "f should not be completed")
    assertEquals(received.length, 0)

    downstream.connect()
    s.tick()
    assert(f.isCompleted, "f should be completed")
    assertEquals(received.length, 10)

    assertEquals(received.toSeq, 1 to 10)
    assert(wasCompleted, "downstream should be completed")
  }

  test("should emit pushed items immediately after connect") { implicit s =>
    val received = ArrayBuffer.empty[Int]
    var wasCompleted = false

    val downstream = create(new Observer[Int] {
      def onError(ex: Throwable): Unit = ()
      def onComplete(): Unit = wasCompleted = true

      def onNext(elem: Int) = {
        received.append(elem)
        Continue
      }
    })

    downstream.pushFirst(1)
    downstream.pushFirst(2)
    downstream.connect()
    s.tick()

    assertEquals(received.length, 2)
    assertEquals(received.toSeq, Seq(1, 2))
    assertEquals(wasCompleted, false)
  }

  test("should not allow pushFirst after connect") { implicit s =>

    val downstream = create(new Observer[Int] {
      def onError(ex: Throwable): Unit = ()
      def onComplete(): Unit = ()
      def onNext(elem: Int) = Continue
    })

    downstream.connect()
    s.tick()

    intercept[IllegalStateException]{
      downstream.pushFirst(1)
    }
  }

  test("should not allow pushFirstAll after connect") { implicit s =>

    val downstream = create(new Observer[Int] {
      def onError(ex: Throwable): Unit = ()
      def onComplete(): Unit = ()
      def onNext(elem: Int) = Continue
    })

    downstream.connect()
    s.tick()

    intercept[IllegalStateException]{
      downstream.pushFirstAll(Seq(1, 2, 3))
    }
  }

  test("should schedule pushComplete") { implicit s =>
    val received = ArrayBuffer.empty[Int]
    var wasCompleted = false

    val downstream = create(new Observer[Int] {
      def onError(ex: Throwable): Unit = ()
      def onComplete(): Unit = wasCompleted = true

      def onNext(elem: Int) = {
        received.append(elem)
        Continue
      }
    })

    downstream.onNext(10)
    downstream.pushFirst(1)
    downstream.pushFirst(2)
    downstream.pushComplete()
    downstream.connect()
    s.tick()

    assertEquals(received.length, 2)
    assertEquals(received.toSeq, Seq(1, 2))
    assertEquals(wasCompleted, true)
  }

  test("should not allow pushComplete after connect") { implicit s =>

    val downstream = create(new Observer[Int] {
      def onError(ex: Throwable): Unit = ()
      def onComplete(): Unit = ()
      def onNext(elem: Int) = Continue
    })

    downstream.connect()
    s.tick()

    intercept[IllegalStateException]{
      downstream.pushComplete()
    }
  }

  test("should schedule pushError") { implicit s =>
    val received = ArrayBuffer.empty[Int]
    var errorThrown: Throwable = null

    val downstream = create(new Observer[Int] {
      def onError(ex: Throwable): Unit = errorThrown = ex
      def onComplete(): Unit = ()

      def onNext(elem: Int) = {
        received.append(elem)
        Continue
      }
    })

    downstream.onNext(10)
    downstream.pushFirst(1)
    downstream.pushFirst(2)
    downstream.pushError(DummyException("dummy"))
    downstream.connect()
    s.tick()

    assertEquals(received.length, 2)
    assertEquals(received.toSeq, Seq(1, 2))
    assertEquals(errorThrown, DummyException("dummy"))
  }

  test("should not allow pushError after connect") { implicit s =>

    val downstream = create(new Observer[Int] {
      def onError(ex: Throwable): Unit = ()
      def onComplete(): Unit = ()
      def onNext(elem: Int) = Continue
    })

    downstream.connect()
    s.tick()

    intercept[IllegalStateException] {
      downstream.pushError(DummyException("dummy"))
    }
  }

  def create[A](o: Observer[A])(implicit s: Scheduler) =
    ConnectableSubscriber(Subscriber(o, s))
}