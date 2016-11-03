/*
 * Copyright (c) 2014-2016 by its authors. Some rights reserved.
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

import java.util.concurrent.{CountDownLatch, TimeUnit}

import minitest.TestSuite
import monix.execution.Ack.Continue
import monix.execution.{Ack, Scheduler}
import monix.reactive.OverflowStrategy.Unbounded
import monix.reactive.{Observable, Observer}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.Random

object BufferUnboundedConcurrencySuite extends TestSuite[Scheduler] {
  def tearDown(env: Scheduler) = ()
  def setup() = {
    monix.execution.Scheduler.Implicits.global
  }

  test("merge test should work") { implicit s =>
    val num = 200000L
    val source = Observable.repeat(1L).take(num)
    val o1 = source.map(_ + 2)
    val o2 = source.map(_ + 3)
    val o3 = source.map(_ + 4)

    val f = Observable.fromIterable(Seq(o1, o2, o3))
      .mergeMap(x => x)(Unbounded)
      .sumF
      .runAsyncGetFirst

    val result = Await.result(f, 30.seconds)
    assertEquals(result, Some(num * 3 + num * 4 + num * 5))
  }

  test("should not lose events with sync subscriber, test 1") { implicit s =>
    var number = 0
    val completed = new CountDownLatch(1)

    val underlying = new Observer[Int] {
      def onNext(elem: Int): Future[Ack] = {
        number += 1
        Continue
      }

      def onError(ex: Throwable): Unit = {
        s.reportFailure(ex)
      }

      def onComplete(): Unit = {
        completed.countDown()
      }
    }

    val buffer = BufferedSubscriber[Int](Subscriber(underlying, s), Unbounded)
    for (i <- 0 until 100000) buffer.onNext(i)
    buffer.onComplete()

    assert(completed.await(60, TimeUnit.SECONDS), "completed.await should have succeeded")
    assertEquals(number, 100000)
  }

  test("should not lose events with sync subscriber, test 2") { implicit s =>
    var number = 0
    val completed = new CountDownLatch(1)

    val underlying = new Observer[Int] {
      def onNext(elem: Int): Future[Ack] = {
        number += 1
        Continue
      }

      def onError(ex: Throwable): Unit = {
        s.reportFailure(ex)
      }

      def onComplete(): Unit = {
        completed.countDown()
      }
    }

    val buffer = BufferedSubscriber[Int](Subscriber(underlying, s), Unbounded)

    def loop(n: Int): Unit =
      if (n > 0) s.execute(new Runnable {
        def run() = { buffer.onNext(n); loop(n-1) }
      })
      else buffer.onComplete()

    loop(10000)
    assert(completed.await(60, TimeUnit.SECONDS), "completed.await should have succeeded")
    assertEquals(number, 10000)
  }

  test("should not lose events with async subscriber from one publisher") { implicit s =>
    var sum = 0
    val completed = new CountDownLatch(1)

    val underlying = new Observer[Int] {
      var previous = 0

      def process(elem: Int): Ack = {
        assertEquals(elem, previous + 1)
        sum += elem
        previous = elem
        Continue
      }

      def onNext(elem: Int): Future[Ack] = {
        val shouldBeAsync = Random.nextInt() % 2 == 0
        if (shouldBeAsync)
          Future(process(elem))
        else
          process(elem)
      }

      def onError(ex: Throwable): Unit =
        s.reportFailure(ex)

      def onComplete(): Unit =
        completed.countDown()
    }

    val buffer = BufferedSubscriber[Int](Subscriber(underlying, s), Unbounded)
    for (i <- 1 to 100000) buffer.onNext(i)
    buffer.onComplete()

    assert(completed.await(60, TimeUnit.SECONDS), "completed.await should have succeeded")
    assertEquals(sum, 50000 * (100000 + 1))
  }

  test("should not lose events with async subscriber from multiple publishers") { implicit s =>
    var sum = 0
    val completed = new CountDownLatch(1)

    val underlying = new Observer[Int] {
      def process(elem: Int): Ack = {
        sum += elem
        Continue
      }

      def onNext(elem: Int): Future[Ack] = {
        val shouldBeAsync = Random.nextInt() % 2 == 0
        if (shouldBeAsync)
          Future(process(elem))
        else
          process(elem)
      }

      def onError(ex: Throwable): Unit =
        s.reportFailure(ex)

      def onComplete(): Unit =
        completed.countDown()
    }

    val buffer = BufferedSubscriber[Int](Subscriber(underlying, s), Unbounded)
    // Publisher 1
    val p1 = Future { for (i <- 1 until 25000) buffer.onNext(i) }
    // Publisher 2
    val p2 = Future { for (i <- 25000 until 50000) buffer.onNext(i) }
    // Publisher 3
    val p3 = Future { for (i <- 50000 until 75000) buffer.onNext(i) }
    // Publisher 4
    val p4 = Future { for (i <- 75000 to 100000) buffer.onNext(i) }
    // Final event
    Future.sequence(Seq(p1,p2,p3,p4)).foreach(_ => buffer.onComplete())

    assert(completed.await(60, TimeUnit.SECONDS), "completed.await should have succeeded")
    assertEquals(sum, 50000 * (100000 + 1))
  }

  test("should send onError when empty") { implicit s =>
    val latch = new CountDownLatch(1)
    val underlying = new Observer[Int] {
      def onError(ex: Throwable) = {
        assertEquals(ex.getMessage, "dummy")
        latch.countDown()
      }
      def onNext(elem: Int) = throw new IllegalStateException()
      def onComplete() = throw new IllegalStateException()
    }

    val buffer = BufferedSubscriber[Int](Subscriber(underlying, s), Unbounded)

    buffer.onError(new RuntimeException("dummy"))
    assert(latch.await(60, TimeUnit.SECONDS), "latch.await should have succeeded")
  }

  test("should send onError when in flight") { implicit s =>
    val latch = new CountDownLatch(1)
    val underlying = new Observer[Int] {
      def onError(ex: Throwable) = {
        assertEquals(ex.getMessage, "dummy")
        latch.countDown()
      }
      def onNext(elem: Int) = Continue
      def onComplete() = throw new IllegalStateException()
    }

    val buffer = BufferedSubscriber[Int](Subscriber(underlying, s), Unbounded)

    buffer.onNext(1)
    buffer.onError(new RuntimeException("dummy"))
    assert(latch.await(60, TimeUnit.SECONDS), "latch.await should have succeeded")
  }

  test("should send onComplete when empty") { implicit s =>
    val latch = new CountDownLatch(1)
    val underlying = new Observer[Int] {
      def onError(ex: Throwable) = throw new IllegalStateException()
      def onNext(elem: Int) = throw new IllegalStateException()
      def onComplete() = latch.countDown()
    }

    val buffer = BufferedSubscriber[Int](Subscriber(underlying, s), Unbounded)

    buffer.onComplete()
    assert(latch.await(60, TimeUnit.SECONDS), "latch.await should have succeeded")
  }

  test("should not back-pressure onComplete") { implicit s =>
    val latch = new CountDownLatch(1)
    val promise = Promise[Ack]()
    val underlying = new Observer[Int] {
      def onError(ex: Throwable) = throw new IllegalStateException()
      def onNext(elem: Int) = promise.future
      def onComplete() = latch.countDown()
    }

    val buffer = BufferedSubscriber[Int](Subscriber(underlying, s), Unbounded)

    buffer.onNext(1)
    buffer.onComplete()
    assert(latch.await(60, TimeUnit.SECONDS), "latch.await should have succeeded")
  }

  test("should do onComplete only after all the queue was drained") { implicit s =>
    var sum = 0L
    val complete = new CountDownLatch(1)
    val startConsuming = Promise[Continue]()

    val underlying = new Observer[Long] {
      def onNext(elem: Long) = {
        sum += elem
        startConsuming.future
      }
      def onError(ex: Throwable) = throw ex
      def onComplete() = complete.countDown()
    }

    val buffer = BufferedSubscriber[Long](Subscriber(underlying, s), Unbounded)
    (0 until 9999).foreach(x => buffer.onNext(x))
    buffer.onComplete()
    startConsuming.success(Continue)

    assert(complete.await(60, TimeUnit.SECONDS), "complete.await should have succeeded")
    assert(sum == (0 until 9999).sum)
  }

  test("should do onComplete only after all the queue was drained, test2") { implicit s =>
    var sum = 0L
    val complete = new CountDownLatch(1)
    val underlying = new Observer[Long] {
      def onNext(elem: Long) = {
        sum += elem
        Continue
      }
      def onError(ex: Throwable) = throw ex
      def onComplete() = complete.countDown()
    }

    val buffer = BufferedSubscriber[Long](Subscriber(underlying, s), Unbounded)

    (0 until 9999).foreach(x => buffer.onNext(x))
    buffer.onComplete()

    assert(complete.await(60, TimeUnit.SECONDS), "complete.await should have succeeded")
    assert(sum == (0 until 9999).sum)
  }

  test("should do onError only after the queue was drained") { implicit s =>
    var sum = 0L
    val complete = new CountDownLatch(1)
    val startConsuming = Promise[Continue]()

    val underlying = new Observer[Long] {
      def onNext(elem: Long) = {
        sum += elem
        startConsuming.future
      }
      def onError(ex: Throwable) = complete.countDown()
      def onComplete() = throw new IllegalStateException()
    }

    val buffer = BufferedSubscriber[Long](Subscriber(underlying, s), Unbounded)

    (0 until 9999).foreach(x => buffer.onNext(x))
    buffer.onError(new RuntimeException)
    startConsuming.success(Continue)

    assert(complete.await(60, TimeUnit.SECONDS), "complete.await should have succeeded")
    assertEquals(sum, (0 until 9999).sum)
  }

  test("should do onError only after all the queue was drained, test2") { implicit s =>
    var sum = 0L
    val complete = new CountDownLatch(1)

    val underlying = new Observer[Long] {
      def onNext(elem: Long) = {
        sum += elem
        Continue
      }
      def onError(ex: Throwable) = complete.countDown()
      def onComplete() = throw new IllegalStateException()
    }

    val buffer = BufferedSubscriber[Long](Subscriber(underlying, s), Unbounded)
    (0 until 9999).foreach(x => buffer.onNext(x))
    buffer.onError(new RuntimeException)

    assert(complete.await(60, TimeUnit.SECONDS), "complete.await should have succeeded")
    assertEquals(sum, (0 until 9999).sum)
  }
}
