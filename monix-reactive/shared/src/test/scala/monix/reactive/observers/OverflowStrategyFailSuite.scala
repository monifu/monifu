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

package monix.reactive.observers

import minitest.TestSuite
import monix.execution.Ack
import monix.execution.Ack.{Continue, Stop}
import monix.execution.exceptions.BufferOverflowException
import monix.execution.internal.Platform
import monix.execution.schedulers.TestScheduler
import monix.reactive.Observer
import monix.reactive.OverflowStrategy.Fail
import monix.execution.exceptions.DummyException

import scala.concurrent.{Future, Promise}

object OverflowStrategyFailSuite extends TestSuite[TestScheduler] {
  def setup() = TestScheduler()
  def tearDown(s: TestScheduler) = {
    assert(s.state.tasks.isEmpty,
      "TestScheduler should have no pending tasks")
  }

  test("should not lose events, test 1") { implicit s =>
    var number = 0
    var wasCompleted = false

    val underlying = new Observer[Int] {
      def onNext(elem: Int): Future[Ack] = {
        number += 1
        Continue
      }

      def onError(ex: Throwable): Unit = {
        s.reportFailure(ex)
      }

      def onComplete(): Unit = {
        wasCompleted = true
      }
    }

    val buffer = BufferedSubscriber[Int](Subscriber(underlying, s), Fail(1000))
    for (i <- 0 until 1000) buffer.onNext(i)
    buffer.onComplete()

    assert(!wasCompleted)
    s.tick()
    assert(number == 1000)
    assert(wasCompleted)
  }

  test("should not lose events, test 2") { implicit s =>
    var number = 0
    var completed = false

    val underlying = new Observer[Int] {
      def onNext(elem: Int): Future[Ack] = {
        number += 1
        Continue
      }

      def onError(ex: Throwable): Unit = {
        s.reportFailure(ex)
      }

      def onComplete(): Unit = {
        completed = true
      }
    }

    val buffer = BufferedSubscriber[Int](Subscriber(underlying, s), Fail(1000))

    def loop(n: Int): Unit =
      if (n > 0)
        s.executeAsync { () => buffer.onNext(n); loop(n-1) }
      else
        buffer.onComplete()

    loop(10000)
    assert(!completed)
    assertEquals(number, 0)

    s.tick()
    assert(completed)
    assertEquals(number, 10000)
  }

  test("should trigger overflow when over capacity") { implicit s =>
    var errorCaught: Throwable = null
    var received = 0
    val promise = Promise[Ack]()

    val underlying = new Observer[Int] {
      def onNext(elem: Int) = {
        received += 1
        promise.future
      }

      def onError(ex: Throwable) = {
        assert(ex.isInstanceOf[BufferOverflowException],
          s"Exception $ex is not a buffer overflow error")
        errorCaught = ex
      }

      def onComplete() = {
        throw new IllegalStateException("Should not onComplete")
      }
    }

    val buffer = BufferedSubscriber[Int](Subscriber(underlying, s), Fail(5))
    for (i <- 0 to 8) buffer.onNext(i)

    s.tick()
    assertEquals(received, 1)
    assert(errorCaught == null, "errorCaught == null")

    buffer.onNext(8)
    promise.success(Continue)
    s.tick()

    assertEquals(received, 8)
    assert(errorCaught != null && errorCaught.isInstanceOf[BufferOverflowException])
  }

  test("should send onError when empty") { implicit s =>
    var errorThrown: Throwable = null
    val buffer = BufferedSubscriber[Int](
      new Subscriber[Int] {
        def onError(ex: Throwable) = {
          errorThrown = ex
        }

        def onNext(elem: Int) = throw new IllegalStateException()
        def onComplete() = throw new IllegalStateException()
        val scheduler = s
      }, Fail(5))

    buffer.onError(DummyException("dummy"))
    s.tickOne()

    assertEquals(errorThrown, DummyException("dummy"))
    val r = buffer.onNext(1)
    assertEquals(r, Stop)
  }

  test("should send onError when in flight") { implicit s =>
    var errorThrown: Throwable = null
    val buffer = BufferedSubscriber[Int](
      new Subscriber[Int] {
        def onError(ex: Throwable) = {
          errorThrown = ex
        }
        def onNext(elem: Int) = Continue
        def onComplete() = throw new IllegalStateException()
        val scheduler = s
      }, Fail(5))

    buffer.onNext(1)
    buffer.onError(DummyException("dummy"))
    s.tickOne()

    assertEquals(errorThrown, DummyException("dummy"))
  }

  test("should send onError when at capacity") { implicit s =>
    var errorThrown: Throwable = null
    val promise = Promise[Ack]()

    val buffer = BufferedSubscriber[Int](
      new Subscriber[Int] {
        def onError(ex: Throwable) = {
          errorThrown = ex
        }
        def onNext(elem: Int) = promise.future
        def onComplete() = throw new IllegalStateException()
        val scheduler = s
      }, Fail(5))

    buffer.onNext(1)
    buffer.onNext(2)
    buffer.onNext(3)
    buffer.onNext(4)
    buffer.onNext(5)
    buffer.onError(DummyException("dummy"))

    promise.success(Continue)
    s.tick()

    assertEquals(errorThrown, DummyException("dummy"))
  }

  test("should send onComplete when empty") { implicit s =>
    var wasCompleted = false
      val buffer = BufferedSubscriber[Int](new Subscriber[Int] {
        def onError(ex: Throwable) = throw new IllegalStateException()
        def onNext(elem: Int) = throw new IllegalStateException()
        def onComplete() = wasCompleted = true
        val scheduler = s
      }, Fail(5))

    buffer.onComplete()
    s.tickOne()
    assert(wasCompleted)
  }

  test("should not back-pressure onComplete") { implicit s =>
    var wasCompleted = 0
    val promise = Promise[Ack]()
    val buffer = BufferedSubscriber[Int](
      new Subscriber[Int] {
        def onError(ex: Throwable) = throw new IllegalStateException()
        def onNext(elem: Int) = promise.future
        def onComplete() = wasCompleted += 1
        val scheduler = s
      }, Fail(5))

    buffer.onNext(1)
    buffer.onComplete()
    s.tick()
    assertEquals(wasCompleted, 1)

    promise.success(Continue)
    s.tick()
    assertEquals(wasCompleted, 1)
  }

  test("should send onComplete when at capacity") { implicit s =>
    var wasCompleted = false
    val promise = Promise[Ack]()
    val buffer = BufferedSubscriber[Int](
      new Subscriber[Int] {
        def onError(ex: Throwable) = throw new IllegalStateException()
        def onNext(elem: Int) = promise.future
        def onComplete() = wasCompleted = true
        val scheduler = s
      }, Fail(5))

    buffer.onNext(1)
    buffer.onNext(2)
    buffer.onNext(3)
    buffer.onNext(4)
    buffer.onComplete()

    s.tick()
    assert(!wasCompleted)

    promise.success(Continue)
    s.tick()
    assert(wasCompleted)
  }

  test("should do onComplete only after all the queue was drained") { implicit s =>
    var sum = 0L
    var wasCompleted = false
    val startConsuming = Promise[Continue.type]()

    val buffer = BufferedSubscriber[Long](
      new Subscriber[Long] {
        def onNext(elem: Long) = {
          sum += elem
          startConsuming.future
        }
        def onError(ex: Throwable) = throw ex
        def onComplete() = wasCompleted = true
        val scheduler = s
      }, Fail(10000))

    (0 until 9999).foreach(x => buffer.onNext(x))
    buffer.onComplete()
    startConsuming.success(Continue)

    s.tick()
    assert(wasCompleted)
    assert(sum == (0 until 9999).sum)
  }

  test("should do onComplete only after all the queue was drained, test2") { implicit s =>
    var sum = 0L
    var wasCompleted = false

    val buffer = BufferedSubscriber[Long](
      new Subscriber[Long] {
        def onNext(elem: Long) = {
          sum += elem
          Continue
        }
        def onError(ex: Throwable) = throw ex
        def onComplete() = wasCompleted = true
        val scheduler = s
      }, Fail(10000))

    (0 until 9999).foreach(x => buffer.onNext(x))
    buffer.onComplete()
    s.tick()

    assert(wasCompleted)
    assert(sum == (0 until 9999).sum)
  }

  test("should do onError only after the queue was drained") { implicit s =>
    var sum = 0L
    var errorThrown: Throwable = null
    val startConsuming = Promise[Continue.type]()

    val buffer = BufferedSubscriber[Long](
      new Subscriber[Long] {
        def onNext(elem: Long) = {
          sum += elem
          startConsuming.future
        }

        def onError(ex: Throwable) = errorThrown = ex
        def onComplete() = throw new IllegalStateException()
        val scheduler = s
      }, Fail(10000))

    (0 until 9999).foreach(x => buffer.onNext(x))
    buffer.onError(DummyException("dummy"))
    startConsuming.success(Continue)

    s.tick()
    assertEquals(errorThrown, DummyException("dummy"))
    assertEquals(sum, (0 until 9999).sum)
  }

  test("should do onError only after all the queue was drained, test2") { implicit s =>
    var sum = 0L
    var errorThrown: Throwable = null

    val buffer = BufferedSubscriber[Long](
      new Subscriber[Long] {
        def onNext(elem: Long) = {
          sum += elem
          Continue
        }
        def onError(ex: Throwable) = errorThrown = ex
        def onComplete() = throw new IllegalStateException()
        val scheduler = s
      }, Fail(10000))

    (0 until 9999).foreach(x => buffer.onNext(x))
    buffer.onError(DummyException("dummy"))

    s.tick()
    assertEquals(errorThrown, DummyException("dummy"))
    assertEquals(sum, (0 until 9999).sum)
  }

  test("should do synchronous execution in batches") { implicit s =>
    var received = 0L
    var wasCompleted = false

    val buffer = BufferedSubscriber[Long](
      new Subscriber[Long] {
        def onNext(elem: Long) = {
          received += 1
          Continue
        }
        def onError(ex: Throwable) = ()
        def onComplete() = wasCompleted = true
        val scheduler = s
      }, Fail(Platform.recommendedBatchSize * 3))

    for (i <- 0 until (Platform.recommendedBatchSize * 2)) buffer.onNext(i)
    buffer.onComplete()
    assertEquals(received, 0)

    s.tickOne()
    assertEquals(received, Platform.recommendedBatchSize)
    s.tickOne()
    assertEquals(received, Platform.recommendedBatchSize * 2)
    s.tickOne()
    assertEquals(wasCompleted, true)
  }

  test("underlying subscriber should be able to stop precisely, sync, test #1") { implicit s =>
    var wasCompleted = false
    var sum = 0L

    val buffer = BufferedSubscriber[Long](
      new Subscriber[Long] {
        def onNext(elem: Long) = {
          sum += elem
          if (elem < 10) Continue
          else Stop
        }

        def onError(ex: Throwable) = ()
        def onComplete() = wasCompleted = true
        val scheduler = s
      }, Fail(2000)
    )

    for (i <- 0 until 1000; ack = buffer.onNext(i); if ack == Continue) {}
    s.tick()

    // Should not onComplete because of Stop
    assert(!wasCompleted, "!wasCompleted")
    assertEquals(sum, 55)
  }

  test("underlying subscriber should be able to stop precisely, sync, test #2") { implicit s =>
    var wasCompleted = false
    var sum = 0L

    val buffer = BufferedSubscriber[Long](
      new Subscriber[Long] {
        def onNext(elem: Long) = {
          sum += elem
          if (elem < 10) Continue
          else Stop
        }

        def onError(ex: Throwable) = ()
        def onComplete() = wasCompleted = true
        val scheduler = s
      }, Fail(2000)
    )

    for (i <- 0 until 1000; ack = buffer.onNext(i); if ack == Continue) s.tick()

    // Should not onComplete because of Stop
    assert(!wasCompleted, "!wasCompleted")
    assertEquals(sum, 55)
  }

  test("underlying subscriber should be able to stop precisely, async, test #1") { implicit s =>
    var wasCompleted = false
    var sum = 0L

    val buffer = BufferedSubscriber[Long](
      new Subscriber[Long] {
        def onNext(elem: Long) = {
          sum += elem
          if (elem < 10) Future(Continue)
          else Future(Stop)
        }

        def onError(ex: Throwable) = ()
        def onComplete() = wasCompleted = true
        val scheduler = s
      }, Fail(2000)
    )

    for (i <- 0 until 1000; ack = buffer.onNext(i); if ack == Continue) {}
    s.tick()

    // Should not onComplete because of Stop
    assert(!wasCompleted, "!wasCompleted")
    assertEquals(sum, 55)
  }

  test("underlying subscriber should be able to stop precisely, async, test #2") { implicit s =>
    var wasCompleted = false
    var sum = 0L

    val buffer = BufferedSubscriber[Long](
      new Subscriber[Long] {
        def onNext(elem: Long) = {
          sum += elem
          if (elem < 10) Future(Continue)
          else Future(Stop)
        }

        def onError(ex: Throwable) = ()
        def onComplete() = wasCompleted = true
        val scheduler = s
      }, Fail(2000)
    )

    for (i <- 0 until 1000; ack = buffer.onNext(i); if ack == Continue) s.tick()

    // Should not onComplete because of Stop
    assert(!wasCompleted, "!wasCompleted")
    assertEquals(sum, 55)
  }


  test("subscriber STOP after a synchronous onNext") { implicit s =>
    var received = 0
    var wasCompleted = false
    val underlying = new Subscriber[Int] {
      val scheduler = s

      def onNext(elem: Int): Future[Ack] = {
        received += elem
        Stop
      }

      def onError(ex: Throwable): Unit =
        throw ex
      def onComplete(): Unit =
        wasCompleted = true
    }

    val buffer = BufferedSubscriber[Int](underlying, Fail(16))
    assertEquals(buffer.onNext(1), Continue)

    s.tick()
    assertEquals(buffer.onNext(2), Stop)

    buffer.onComplete(); s.tick()
    assert(!wasCompleted, "!wasCompleted")
    assertEquals(received, 1)
  }

  test("subscriber STOP after an asynchronous onNext") { implicit s =>
    var received = 0
    var wasCompleted = false
    val underlying = new Subscriber[Int] {
      val scheduler = s

      def onNext(elem: Int): Future[Ack] = Future {
        received += elem
        Stop
      }

      def onError(ex: Throwable): Unit =
        throw ex
      def onComplete(): Unit =
        wasCompleted = true
    }

    val buffer = BufferedSubscriber[Int](underlying, Fail(16))
    assertEquals(buffer.onNext(1), Continue)

    s.tick()
    assertEquals(received, 1)

    buffer.onNext(2); s.tick() // uncertain
    assertEquals(buffer.onNext(3), Stop)

    buffer.onComplete(); s.tick()
    assert(!wasCompleted, "!wasCompleted")
    assertEquals(received, 1)
  }

  test("stop after a synchronous Failure(ex)") { implicit s =>
    var received = 0
    var wasCompleted = false
    var errorThrown: Throwable = null
    val dummy = new RuntimeException("dummy")

    val underlying = new Subscriber[Int] {
      val scheduler = s

      def onNext(elem: Int): Future[Ack] = {
        received += elem
        Future.failed(dummy)
      }

      def onError(ex: Throwable): Unit =
        errorThrown = ex
      def onComplete(): Unit =
        wasCompleted = true
    }

    val buffer = BufferedSubscriber[Int](underlying, Fail(16))
    assertEquals(buffer.onNext(1), Continue)

    s.tick()
    assertEquals(buffer.onNext(2), Stop)

    buffer.onComplete(); s.tick()
    assert(!wasCompleted, "!wasCompleted")
    assertEquals(received, 1)
    assertEquals(errorThrown, dummy)
  }

  test("stop after an asynchronous Failure(ex)") { implicit s =>
    var received = 0
    var wasCompleted = false
    var errorThrown: Throwable = null
    val dummy = new RuntimeException("dummy")

    val underlying = new Subscriber[Int] {
      val scheduler = s

      def onNext(elem: Int): Future[Ack] = Future {
        received += elem
        throw dummy
      }

      def onError(ex: Throwable): Unit =
        errorThrown = ex
      def onComplete(): Unit =
        wasCompleted = true
    }

    val buffer = BufferedSubscriber[Int](underlying, Fail(16))
    assertEquals(buffer.onNext(1), Continue)
    s.tick(); buffer.onNext(2) // uncertain

    s.tick()
    assertEquals(buffer.onNext(3), Stop)

    buffer.onComplete(); s.tick()
    assert(!wasCompleted, "!wasCompleted")
    assertEquals(received, 1)
    assertEquals(errorThrown, dummy)
  }

  test("should protect against user-code in onNext") { implicit s =>
    var received = 0
    var wasCompleted = false
    var errorThrown: Throwable = null
    val dummy = new RuntimeException("dummy")

    val underlying = new Subscriber[Int] {
      val scheduler = s

      def onNext(elem: Int): Future[Ack] = {
        received += elem
        throw dummy
      }

      def onError(ex: Throwable): Unit =
        errorThrown = ex
      def onComplete(): Unit =
        wasCompleted = true
    }

    val buffer = BufferedSubscriber[Int](underlying, Fail(16))
    assertEquals(buffer.onNext(1), Continue)

    s.tick()
    assertEquals(buffer.onNext(2), Stop)

    buffer.onComplete(); s.tick()
    assert(!wasCompleted, "!wasCompleted")
    assertEquals(received, 1)
    assertEquals(errorThrown, dummy)
  }

  test("should protect against user-code in onComplete") { implicit s =>
    var received = 0
    var errorThrown: Throwable = null
    val dummy = new RuntimeException("dummy")

    val underlying = new Subscriber[Int] {
      val scheduler = s

      def onNext(elem: Int): Future[Ack] = {
        received += elem
        Continue
      }

      def onError(ex: Throwable): Unit =
        errorThrown = ex
      def onComplete(): Unit =
        throw dummy
    }

    val buffer = BufferedSubscriber[Int](underlying, Fail(16))

    buffer.onNext(1)
    buffer.onComplete()

    s.tick()
    assertEquals(received, 1)
    assertEquals(errorThrown, null)
    assertEquals(s.state.lastReportedError, dummy)
  }

  test("should protect against user-code in onError") { implicit s =>
    var received = 0
    var errorThrown: Throwable = null

    val dummy1 = new RuntimeException("dummy1")
    val dummy2 = new RuntimeException("dummy2")

    val underlying = new Subscriber[Int] {
      val scheduler = s

      def onNext(elem: Int): Future[Ack] = {
        received += elem
        Future.failed(dummy1)
      }

      def onError(ex: Throwable): Unit = {
        errorThrown = ex
        throw dummy2
      }

      def onComplete(): Unit =
        throw new IllegalStateException("onComplete")
    }

    val buffer = BufferedSubscriber[Int](underlying, Fail(16))
    buffer.onNext(1)

    s.tick()
    assertEquals(received, 1)
    assertEquals(errorThrown, dummy1)
    assertEquals(s.state.lastReportedError, dummy2)
  }

  test("streaming null is not allowed") { implicit s =>
    var errorThrown: Throwable = null

    val underlying = new Subscriber[String] {
      val scheduler = s
      def onNext(elem: String) =
        Continue
      def onError(ex: Throwable): Unit =
        errorThrown = ex
      def onComplete(): Unit =
        throw new IllegalStateException("onComplete")
    }

    val buffer = BufferedSubscriber[String](underlying, Fail(16))
    buffer.onNext(null)

    s.tick()
    assert(errorThrown != null, "errorThrown != null")
    assert(errorThrown.isInstanceOf[NullPointerException],
      "errorThrown.isInstanceOf[NullPointerException]")
  }

  test("buffer size is required to be greater than 1") { implicit s =>
    intercept[IllegalArgumentException] {
      BufferedSubscriber[Int](Subscriber.empty[Int], Fail(1))
    }
  }
}
