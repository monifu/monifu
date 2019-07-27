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

import cats.effect.{ExitCase, IO}
import minitest.TestSuite
import monix.eval.Task
import monix.execution.Ack
import monix.execution.Ack.{Continue, Stop}
import monix.execution.schedulers.TestScheduler
import monix.reactive.Observable
import monix.execution.exceptions.{CompositeException, DummyException}
import monix.execution.internal.Platform
import monix.reactive.observers.Subscriber

import scala.concurrent.Future

object GuaranteeCaseSuite extends TestSuite[TestScheduler] {
  def setup(): TestScheduler = TestScheduler()
  def tearDown(s: TestScheduler): Unit = {
    assert(s.state.tasks.isEmpty, "TestScheduler should have no pending tasks")
  }

  test("should work for cats.effect.IO") { implicit s =>
    var wasCalled = 0
    var wasCompleted = 0

    Observable
      .now(1)
      .guaranteeF(IO { wasCalled += 1 })
      .unsafeSubscribeFn(new Subscriber[Int] {
        val scheduler = s
        def onNext(elem: Int) = Continue
        def onError(ex: Throwable): Unit = ()
        def onComplete(): Unit = wasCompleted += 1
      })

    assertEquals(wasCalled, 1)
    assertEquals(wasCompleted, 1)
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("should execute callback onComplete") { implicit s =>
    var wasCalled = 0
    var wasCompleted = 0

    Observable
      .now(1)
      .guaranteeF(() => wasCalled += 1)
      .unsafeSubscribeFn(new Subscriber[Int] {
        val scheduler = s
        def onNext(elem: Int) = Continue
        def onError(ex: Throwable): Unit = ()
        def onComplete(): Unit = wasCompleted += 1
      })

    assertEquals(wasCalled, 1)
    assertEquals(wasCompleted, 1)
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("should protect against user code onComplete (direct)") { implicit s =>
    val ex = DummyException("dummy")
    var wasThrown: Throwable = null

    Observable
      .now(1)
      .guaranteeCase(_ => throw ex)
      .unsafeSubscribeFn(new Subscriber[Int] {
        val scheduler = s
        def onNext(elem: Int) = Continue
        def onComplete(): Unit = ()
        def onError(ex: Throwable): Unit =
          wasThrown = ex
      })

    assertEquals(wasThrown, ex)
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("should protect against user code onComplete (indirect)") { implicit s =>
    val ex = DummyException("dummy")
    var wasThrown: Throwable = null

    Observable
      .now(1)
      .guarantee(Task.raiseError(ex))
      .unsafeSubscribeFn(new Subscriber[Int] {
        val scheduler = s
        def onNext(elem: Int) = Continue
        def onComplete(): Unit = ()
        def onError(ex: Throwable): Unit =
          wasThrown = ex
      })

    assertEquals(wasThrown, ex)
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("should execute callback onError") { implicit s =>
    val ex = DummyException("dummy")
    var wasCalled = 0
    var wasThrown: Throwable = null

    Observable
      .now(1)
      .endWithError(ex)
      .guaranteeF(IO { wasCalled += 1 })
      .unsafeSubscribeFn(new Subscriber[Int] {
        val scheduler = s
        def onNext(elem: Int) = Continue
        def onComplete(): Unit = ()
        def onError(ex: Throwable): Unit =
          wasThrown = ex
      })

    assertEquals(wasCalled, 1)
    assertEquals(wasThrown, ex)
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("should protect against user-code onError (direct)") { implicit s =>
    val ex1 = DummyException("dummy1")
    val ex2 = DummyException("dummy2")
    var wasThrown: Throwable = null

    Observable
      .now(1)
      .endWithError(ex1)
      .guaranteeCaseF[IO](_ => throw ex2)
      .unsafeSubscribeFn(new Subscriber[Int] {
        val scheduler = s
        def onNext(elem: Int) = Continue
        def onComplete(): Unit = ()
        def onError(ex: Throwable): Unit =
          wasThrown = ex
      })

    s.tick()
    if (Platform.isJVM) {
      assertEquals(wasThrown, ex1)
      assertEquals(wasThrown.getSuppressed.toList, List(ex2))
    } else {
      wasThrown match {
        case CompositeException(list) =>
          assertEquals(list, List(ex1, ex2))
      }
    }
    assertEquals(s.state.lastReportedError, null)
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("should protect against user-code onError (indirect)") { implicit s =>
    val ex1 = DummyException("dummy1")
    val ex2 = DummyException("dummy2")
    var wasThrown: Throwable = null

    Observable
      .now(1)
      .endWithError(ex1)
      .guarantee(Task.raiseError(ex2))
      .unsafeSubscribeFn(new Subscriber[Int] {
        val scheduler = s
        def onNext(elem: Int) = Continue
        def onComplete(): Unit = ()
        def onError(ex: Throwable): Unit =
          wasThrown = ex
      })

    if (Platform.isJVM) {
      assertEquals(wasThrown, ex1)
      assertEquals(wasThrown.getSuppressed.toList, List(ex2))
    } else {
      wasThrown match {
        case CompositeException(list) =>
          assertEquals(list, List(ex1, ex2))
      }
    }
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("should call on synchronous downstream Stop") { implicit s =>
    var wasCalled = 0
    var wasCompleted = 0

    Observable
      .range(0, 100)
      .guarantee(Task.eval { wasCalled += 1 })
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onNext(elem: Long) = Stop
        def onError(ex: Throwable): Unit = ()
        def onComplete(): Unit = wasCompleted = 1
      })

    s.tick()
    assertEquals(wasCalled, 1)
    assertEquals(wasCompleted, 0)
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("should call on asynchronous downstream Stop") { implicit s =>
    var wasCalled = 0
    var wasCompleted = 0

    Observable
      .range(0, 100)
      .guarantee(Task.eval { wasCalled += 1 })
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onNext(elem: Long) = Future(Stop)
        def onError(ex: Throwable): Unit = ()
        def onComplete(): Unit = wasCompleted = 1
      })

    s.tick()
    assertEquals(wasCalled, 1)
    assertEquals(wasCompleted, 0)
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("should protect against user code on synchronous downstream Stop (direct)") { implicit s =>
    val ex = DummyException("dummy")

    Observable
      .range(0, 100)
      .guaranteeCase(_ => throw ex)
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onNext(elem: Long) = Stop
        def onError(ex: Throwable): Unit =
          throw new IllegalStateException("onError")
        def onComplete(): Unit =
          throw new IllegalStateException("onComplete")
      })

    s.tick()
    assertEquals(s.state.lastReportedError, ex)
  }

  test("should protect against user code on synchronous downstream Stop (indirect)") { implicit s =>
    val ex = DummyException("dummy")

    Observable
      .range(0, 100)
      .guarantee(Task.raiseError(ex))
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onNext(elem: Long) = Stop
        def onError(ex: Throwable): Unit =
          throw new IllegalStateException("onError")
        def onComplete(): Unit =
          throw new IllegalStateException("onComplete")
      })

    s.tick()
    assertEquals(s.state.lastReportedError, ex)
  }

  test("should protect against user code on asynchronous downstream Stop (direct)") { implicit s =>
    val ex = DummyException("dummy")

    Observable
      .range(0, 100)
      .guaranteeCase(_ => throw ex)
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onNext(elem: Long) = Future(Stop)
        def onError(ex: Throwable): Unit =
          throw new IllegalStateException("onError")
        def onComplete(): Unit =
          throw new IllegalStateException("onComplete")
      })

    s.tick()
    assertEquals(s.state.lastReportedError, ex)
  }

  test("should protect against user code on asynchronous downstream Stop (indirect)") { implicit s =>
    val ex = DummyException("dummy")

    Observable
      .range(0, 100)
      .guarantee(Task.raiseError(ex))
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onNext(elem: Long) = Future(Stop)
        def onError(ex: Throwable): Unit =
          throw new IllegalStateException("onError")
        def onComplete(): Unit =
          throw new IllegalStateException("onComplete")
      })

    s.tick()
    assertEquals(s.state.lastReportedError, ex)
  }

  test("should receive error if onNext generates error asynchronously") { implicit s =>
    val ex = DummyException("dummy")
    var errorThrown = Option.empty[ExitCase[Throwable]]

    Observable
      .range(0, 100)
      .guaranteeCase { ex =>
        Task.eval { errorThrown = Some(ex) }
      }
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onNext(elem: Long) =
          Future { (throw ex): Ack }
        def onError(ex: Throwable): Unit =
          throw new IllegalStateException("onError")
        def onComplete(): Unit =
          throw new IllegalStateException("onComplete")
      })

    s.tick()
    assertEquals(errorThrown, Some(ExitCase.Error(ex)))
  }

  test("should receive error if onNext returns error synchronously") { implicit s =>
    val ex = DummyException("dummy")
    var errorThrown = Option.empty[ExitCase[Throwable]]

    Observable
      .range(0, 100)
      .guaranteeCase { ex =>
        Task.eval { errorThrown = Some(ex) }
      }
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onNext(elem: Long) =
          Future.failed(ex)
        def onError(ex: Throwable): Unit =
          throw new IllegalStateException("onError")
        def onComplete(): Unit =
          throw new IllegalStateException("onComplete")
      })

    s.tick()
    assertEquals(errorThrown, Some(ExitCase.Error(ex)))
  }

  // Tests converted and imported from the old DoOnTerminateSuite

  test("should protect against user code onComplete") { implicit s =>
    val ex = DummyException("dummy")
    var wasThrown: Throwable = null

    Observable
      .now(1)
      .guaranteeF(IO.raiseError[Unit](ex))
      .unsafeSubscribeFn(new Subscriber[Int] {
        val scheduler = s
        def onNext(elem: Int) = Continue
        def onComplete(): Unit = ()
        def onError(ex: Throwable): Unit =
          wasThrown = ex
      })

    assertEquals(wasThrown, ex)
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("should execute callback onError") { implicit s =>
    val ex = DummyException("dummy")
    var wasCalled = 0
    var wasThrown: Throwable = null

    Observable
      .now(1)
      .endWithError(ex)
      .guaranteeF(() => wasCalled += 1)
      .unsafeSubscribeFn(new Subscriber[Int] {
        val scheduler = s
        def onNext(elem: Int) = Continue
        def onComplete(): Unit = ()
        def onError(ex: Throwable): Unit =
          wasThrown = ex
      })

    assertEquals(wasCalled, 1)
    assertEquals(wasThrown, ex)
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("should protect against user-code onError") { implicit s =>
    val ex1 = DummyException("dummy1")
    val ex2 = DummyException("dummy2")
    var wasThrown: Throwable = null

    Observable
      .now(1)
      .endWithError(ex1)
      .guaranteeF(IO.raiseError[Unit](ex2))
      .unsafeSubscribeFn(new Subscriber[Int] {
        val scheduler = s
        def onNext(elem: Int) = Continue
        def onComplete(): Unit = ()
        def onError(ex: Throwable): Unit =
          wasThrown = ex
      })

    if (Platform.isJVM) {
      assertEquals(wasThrown, ex1)
      assertEquals(wasThrown.getSuppressed.toList, List(ex2))
    } else {
      wasThrown match {
        case CompositeException(list) =>
          assertEquals(list, List(ex1, ex2))
      }
    }
    assertEquals(s.state.lastReportedError, null)
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("should call on synchronous downstream Stop (IO)") { implicit s =>
    var wasCalled = 0
    var wasCompleted = 0

    Observable
      .range(0, 100)
      .guaranteeF(IO(wasCalled += 1))
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onNext(elem: Long) = Stop
        def onError(ex: Throwable): Unit = ()
        def onComplete(): Unit = wasCompleted = 1
      })

    s.tick()
    assertEquals(wasCalled, 1)
    assertEquals(wasCompleted, 0)
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("should call on asynchronous downstream Stop") { implicit s =>
    var wasCalled = 0
    var wasCompleted = 0

    Observable
      .range(0, 100)
      .guaranteeF(() => wasCalled += 1)
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onNext(elem: Long) = Future(Stop)
        def onError(ex: Throwable): Unit = ()
        def onComplete(): Unit = wasCompleted = 1
      })

    s.tick()
    assertEquals(wasCalled, 1)
    assertEquals(wasCompleted, 0)
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("should protect against user code on synchronous downstream Stop") { implicit s =>
    val ex = DummyException("dummy")
    var errorThrown: Throwable = null

    Observable
      .range(0, 100)
      .guaranteeF(IO.raiseError[Unit](ex))
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onNext(elem: Long) = Stop
        def onError(ex: Throwable): Unit =
          throw new IllegalStateException("onError")
        def onComplete(): Unit =
          throw new IllegalStateException("onComplete")
      })

    s.tick()
    assertEquals(s.state.lastReportedError, ex)
  }

  test("should protect against user code on asynchronous downstream Stop") { implicit s =>
    val ex = DummyException("dummy")

    Observable
      .range(0, 100)
      .guaranteeF(IO.raiseError[Unit](ex))
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onNext(elem: Long) = Future(Stop)
        def onError(ex: Throwable): Unit =
          throw new IllegalStateException("onError")
        def onComplete(): Unit =
          throw new IllegalStateException("onComplete")
      })

    s.tick()
    assertEquals(s.state.lastReportedError, ex)
  }

  test("should receive error if onNext generates error asynchronously") { implicit s =>
    val ex = DummyException("dummy")
    var errorThrown = Option.empty[Throwable]

    Observable
      .range(0, 100)
      .guaranteeCaseF[IO] {
        case ExitCase.Error(e) =>
          IO { errorThrown = Some(e) }
        case _ =>
          IO.unit
      }
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onNext(elem: Long) =
          Future { (throw ex): Ack }
        def onError(ex: Throwable): Unit =
          throw new IllegalStateException("onError")
        def onComplete(): Unit =
          throw new IllegalStateException("onComplete")
      })

    s.tick()
    assertEquals(errorThrown, Some(ex))
  }

  test("should receive error if onNext returns error synchronously") { implicit s =>
    val ex = DummyException("dummy")
    var errorThrown = Option.empty[Throwable]

    Observable
      .range(0, 100)
      .guaranteeCaseF {
        case ExitCase.Error(e) =>
          IO { errorThrown = Some(e) }
        case _ =>
          IO.unit
      }
      .unsafeSubscribeFn(new Subscriber[Long] {
        val scheduler = s
        def onNext(elem: Long) =
          Future.failed(ex)
        def onError(ex: Throwable): Unit =
          throw new IllegalStateException("onError")
        def onComplete(): Unit =
          throw new IllegalStateException("onComplete")
      })

    s.tick()
    assertEquals(errorThrown, Some(ex))
  }
}
