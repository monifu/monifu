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

import minitest.TestSuite
import monix.execution.Ack.Continue
import monix.execution.exceptions.DummyException
import monix.execution.schedulers.TestScheduler
import monix.reactive.Observable
import monix.reactive.observers.Subscriber

object DoOnStartSuite extends TestSuite[TestScheduler] {
  def setup(): TestScheduler = TestScheduler()
  def tearDown(s: TestScheduler): Unit = {
    assert(s.state.tasks.isEmpty,
      "TestScheduler should have no pending tasks")
  }

  test("should work for Observable.range") { implicit s =>
    var wasTriggered = 0
    var wasCompleted = 0

    Observable.range(0, 20).doOnStart(_ => wasTriggered += 1)
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onNext(elem: Long) = Continue
        def onError(ex: Throwable): Unit = ()
        def onComplete(): Unit = wasCompleted += 1
      })

    assertEquals(wasTriggered, 1)
    assertEquals(wasCompleted, 1)
  }

  test("should work for Observable.now") { implicit s =>
    var wasTriggered = 0
    var wasCompleted = 0

    Observable.now(1L).doOnStart(_ => wasTriggered += 1)
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onNext(elem: Long) = Continue
        def onError(ex: Throwable): Unit = ()
        def onComplete(): Unit = wasCompleted += 1
      })

    assertEquals(wasTriggered, 1)
    assertEquals(wasCompleted, 1)
  }

  test("should protect against user code for Observable.range") { implicit s =>
    val dummy = DummyException("dummy")
    var wasCompleted = 0L
    var errorThrown: Throwable = null

    Observable.range(0, 20).doOnStart(_ => throw dummy)
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onNext(elem: Long) = Continue
        def onError(ex: Throwable): Unit = errorThrown = ex
        def onComplete(): Unit = wasCompleted += 1
      })

    assertEquals(errorThrown, dummy)
    assertEquals(wasCompleted, 0)
  }

  test("should protect against user code for Observable.now") { implicit s =>
    val dummy = DummyException("dummy")
    var wasCompleted = 0L
    var errorThrown: Throwable = null

    Observable.now(10L).doOnStart(_ => throw dummy)
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onNext(elem: Long) = Continue
        def onError(ex: Throwable): Unit = errorThrown = ex
        def onComplete(): Unit = wasCompleted += 1
      })

    assertEquals(errorThrown, dummy)
    assertEquals(wasCompleted, 0)
  }
}
