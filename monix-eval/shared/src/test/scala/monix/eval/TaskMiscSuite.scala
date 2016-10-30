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

package monix.eval

import org.reactivestreams.{Subscriber, Subscription}
import scala.concurrent.Promise
import scala.util.{Failure, Success}

object TaskMiscSuite extends BaseTestSuite {
  test("Task.failed should end in error") { implicit s =>
    val result = Task.now(1).failed.runAsync
    assert(result.value.isDefined && result.value.get.isFailure &&
      result.value.get.failed.get.isInstanceOf[NoSuchElementException],
      "Should throw NoSuchElementException")
  }

  test("Task.raiseError.failed should expose error") { implicit s =>
    val ex = DummyException("dummy")
    val result = Task.raiseError[Int](ex).failed.runAsync
    assertEquals(result.value, Some(Success(ex)))
  }

  test("Task.map protects against user code") { implicit s =>
    val ex = DummyException("dummy")
    val result = Task.now(1).map(_ => throw ex).runAsync
    assertEquals(result.value, Some(Failure(ex)))
  }

  test("Task.restartUntil") { implicit s =>
    var effect = 0
    val r = Task { effect += 1; effect }.restartUntil(_ >= 10).runAsync
    s.tick()
    assertEquals(r.value.get.get, 10)
  }

  test("Task.toReactivePublisher should end in success") { implicit s =>
    val publisher = Task(1).toReactivePublisher
    var received: Int = 0
    var wasCompleted = false

    publisher.subscribe(new Subscriber[Int] {
      def onSubscribe(s: Subscription): Unit =
        s.request(10)

      def onNext(t: Int): Unit = received = t
      def onError(t: Throwable): Unit = throw t
      def onComplete(): Unit = wasCompleted = true
    })

    s.tick()
    assert(wasCompleted, "wasCompleted")
    assertEquals(received, 1)
  }

  test("Task.toReactivePublisher should end in error") { implicit s =>
    val expected = DummyException("dummy")
    val publisher = Task.raiseError(expected).toReactivePublisher
    var received: Throwable = null

    publisher.subscribe(new Subscriber[Int] {
      def onSubscribe(s: Subscription): Unit =
        s.request(10)

      def onNext(t: Int): Unit =
        throw new IllegalStateException("onNext")
      def onError(t: Throwable): Unit =
        received = t
      def onComplete(): Unit =
        throw new IllegalStateException("onComplete")
    })

    s.tick()
    assertEquals(received, expected)
  }

  test("Task.toReactivePublisher should be cancelable") { implicit s =>
    import concurrent.duration._
    val publisher = Task.now(1).delayExecution(1.second).toReactivePublisher

    publisher.subscribe(new Subscriber[Int] {
      def onSubscribe(s: Subscription): Unit = {
        s.request(10)
        s.cancel()
      }

      def onNext(t: Int): Unit =
        throw new IllegalStateException("onNext")
      def onError(t: Throwable): Unit =
        throw new IllegalStateException("onError")
      def onComplete(): Unit =
        throw new IllegalStateException("onComplete")
    })

    s.tick()
    assert(s.state.tasks.isEmpty,
      "should not have tasks left to execute")
  }

  test("Task.toReactivePublisher should throw error on invalid request") { implicit s =>
    import concurrent.duration._
    val publisher = Task.now(1).delayExecution(1.second).toReactivePublisher

    publisher.subscribe(new Subscriber[Int] {
      def onSubscribe(s: Subscription): Unit =
        intercept[IllegalArgumentException] {
          s.request(-1)
        }

      def onNext(t: Int): Unit =
        throw new IllegalStateException("onNext")
      def onError(t: Throwable): Unit =
        throw new IllegalStateException("onError")
      def onComplete(): Unit =
        throw new IllegalStateException("onComplete")
    })

    s.tick()
    assert(s.state.tasks.isEmpty,
      "should not have tasks left to execute")
  }

  test("Task.pure is an alias of now") { implicit s =>
    assertEquals(Task.pure(1), Task.now(1))
  }

  test("Task.now.runAsync with Try-based callback") { implicit s =>
    val p = Promise[Int]()
    Task.now(1).runAsync(r => p.complete(r))
    assertEquals(p.future.value, Some(Success(1)))
  }

  test("Task.error.runAsync with Try-based callback") { implicit s =>
    val ex = DummyException("dummy")
    val p = Promise[Int]()
    Task.raiseError[Int](ex).runAsync(r => p.complete(r))
    assertEquals(p.future.value, Some(Failure(ex)))
  }

  test("Task.async.runAsync with Try-based callback for success") { implicit s =>
    val p = Promise[Int]()
    Task.fork(Task.now(1)).runAsync(r => p.complete(r))
    s.tick()
    assertEquals(p.future.value, Some(Success(1)))
  }

  test("Task.async.runAsync with Try-based callback for error") { implicit s =>
    val ex = DummyException("dummy")
    val p = Promise[Int]()
    Task.fork(Task.raiseError[Int](ex)).runAsync(r => p.complete(r))
    s.tick()
    assertEquals(p.future.value, Some(Failure(ex)))
  }
}
