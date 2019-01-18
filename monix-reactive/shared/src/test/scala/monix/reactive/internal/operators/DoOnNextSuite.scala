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

import cats.effect.IO
import minitest.TestSuite
import monix.eval.Task
import monix.execution.Ack.Continue
import monix.execution.schedulers.TestScheduler
import monix.reactive.Observable
import monix.execution.exceptions.DummyException
import monix.reactive.observers.Subscriber

object DoOnNextSuite extends TestSuite[TestScheduler] {
  def setup(): TestScheduler = TestScheduler()
  def tearDown(s: TestScheduler): Unit = {
    assert(s.state.tasks.isEmpty,
      "TestScheduler should have no pending tasks")
  }

  test("should work for cats.effect.IO") { implicit s =>
    var sum = 0L
    var wasCompleted = 0

    Observable.range(0, 20).doOnNextF(x => IO(sum += x))
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onError(ex: Throwable): Unit = ()
        def onNext(elem: Long) = Continue
        def onComplete(): Unit = wasCompleted += 1
      })

    s.tick()
    assertEquals(sum, 190)
    assertEquals(wasCompleted, 1)
  }

  test("should work for Observable.range and async task") { implicit s =>
    var sum = 0L
    var wasCompleted = 0

    Observable.range(0, 20).doOnNext(x => Task.evalAsync(sum += x))
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onError(ex: Throwable): Unit = ()
        def onNext(elem: Long) = Continue
        def onComplete(): Unit = wasCompleted += 1
      })

    s.tick()
    assertEquals(sum, 190)
    assertEquals(wasCompleted, 1)
  }

  test("should work for Observable.range and sync task") { implicit s =>
    var sum = 0L
    var wasCompleted = 0

    Observable.range(0, 20).doOnNext(x => Task.eval(sum += x))
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onError(ex: Throwable): Unit = ()
        def onNext(elem: Long) = Continue
        def onComplete(): Unit = wasCompleted += 1
      })

    assertEquals(sum, 190)
    assertEquals(wasCompleted, 1)
  }

  test("should work for Observable.now and async task") { implicit s =>
    var sum = 0L
    var wasCompleted = 0

    Observable.now(10L).doOnNext(x => Task.evalAsync(sum += x))
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onError(ex: Throwable): Unit = ()
        def onNext(elem: Long) = Continue
        def onComplete(): Unit = wasCompleted += 1
      })

    s.tick()
    assertEquals(sum, 10L)
    assertEquals(wasCompleted, 1)
  }

  test("should work for Observable.now and sync task") { implicit s =>
    var sum = 0L
    var wasCompleted = 0

    Observable.now(10L).doOnNext(x => Task.eval(sum += x))
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onError(ex: Throwable): Unit = ()
        def onNext(elem: Long) = Continue
        def onComplete(): Unit = wasCompleted += 1
      })

    assertEquals(sum, 10L)
    assertEquals(wasCompleted, 1)
  }

  test("should protect against user code for direct exceptions") { implicit s =>
    val dummy = DummyException("dummy")
    var received = 0L
    var wasCompleted = 0L
    var errorThrown: Throwable = null

    Observable.range(1,10).doOnNext(x => throw dummy)
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onError(ex: Throwable): Unit = errorThrown = ex
        def onNext(elem: Long) = { received += elem; Continue }
        def onComplete(): Unit = wasCompleted += 1
      })

    assertEquals(received, 0)
    assertEquals(errorThrown, dummy)
    assertEquals(wasCompleted, 0)
  }

  test("should protect against user code for indirect exceptions") { implicit s =>
    val dummy = DummyException("dummy")
    var received = 0L
    var wasCompleted = 0L
    var errorThrown: Throwable = null

    Observable.range(1,10).doOnNext(x => Task.evalAsync(throw dummy))
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onError(ex: Throwable): Unit = errorThrown = ex
        def onNext(elem: Long) = { received += elem; Continue }
        def onComplete(): Unit = wasCompleted += 1
      })

    s.tick()
    assertEquals(received, 0)
    assertEquals(errorThrown, dummy)
    assertEquals(wasCompleted, 0)
  }
}
