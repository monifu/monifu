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

package monix.execution

import minitest.TestSuite
import monix.execution.Ack.{Continue, Stop}
import monix.execution.schedulers.TestScheduler

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

object AckSuite extends TestSuite[TestScheduler] {
  def setup() = TestScheduler()

  def tearDown(env: TestScheduler): Unit = {
    assert(env.state.tasks.isEmpty, "should not have tasks left to execute")
  }

  test("syncOnContinue(Continue) should execute synchronously #1") { implicit s =>
    var triggered = false
    Continue.syncOnContinue { triggered = true }
    assert(triggered, "triggered")
  }

  test("syncOnContinue(Continue) should execute synchronously #2") { implicit s =>
    val continue: Future[Ack] = Continue
    var triggered = false
    val trigger: () => Unit = {
      val value = true
      () => triggered = value
    }

    continue.syncOnContinue(trigger())
    assert(triggered, "triggered")
  }

  test("syncOnContinue(Future.successful(Continue)) should execute async") { implicit s =>
    var triggered = false
    val continue: Future[Ack] = Future.successful(Continue)
    continue.syncOnContinue { triggered = true }

    assert(!triggered, "!triggered")
    s.tick()
    assert(triggered, "triggered")
  }

  test("syncOnContinue(Future(Continue)) should execute async") { implicit s =>
    var triggered = false
    val continue: Future[Ack] = Future(Continue)
    continue.syncOnContinue { triggered = true }

    assert(!triggered, "!triggered")
    s.tick()
    assert(triggered, "triggered")
  }

  test("syncOnContinue(Stop) should execute synchronously #1") { implicit s =>
    var triggered = false
    (Stop : Future[Ack]).syncOnContinue { triggered = true }
    assert(!triggered, "!triggered")
    assert(s.state.tasks.isEmpty, "there should be no async task registered")
  }

  test("syncOnContinue(Stop) should execute synchronously #2") { implicit s =>
    val cancel: Future[Ack] = Stop
    var triggered = false
    val trigger: () => Unit = {
      val value = true
      () => triggered = value
    }

    cancel.syncOnContinue(trigger())
    assert(!triggered, "!triggered")
    assert(s.state.tasks.isEmpty, "there should be no async task registered")
  }

  test("syncOnContinue(Future.successful(Stop)) should execute async") { implicit s =>
    var triggered = false
    Future.successful(Stop : Ack).syncOnContinue { triggered = true }
    assert(s.state.tasks.nonEmpty, "async tasks should be registered")
    s.tickOne()
    assert(!triggered, "!triggered")
  }

  test("syncOnContinue(Future(Stop)) should execute async") { implicit s =>
    var triggered = false
    Future(Stop : Ack).syncOnContinue { triggered = true }
    assert(s.state.tasks.nonEmpty, "async tasks should be registered")
    s.tick()
    assert(!triggered, "!triggered")
  }

  test("syncOnContinue(Continue) should protect against user errors") { implicit s =>
    val ex = new RuntimeException("dummy")
    Continue.syncOnContinue { throw ex }
    assertEquals(s.state.lastReportedError, ex)
  }

  test("syncOnContinue(Continue) should not protect against fatal errors") { implicit s =>
    val ex = new InterruptedException()
    var caught: Throwable = null

    try Continue.syncOnContinue { throw ex } catch {
      case err: InterruptedException => caught = err
    }

    assertEquals(s.state.lastReportedError, null)
    assertEquals(caught, ex)
  }

  test("syncOnCancel(Stop) should execute synchronously") { implicit s =>
    var triggered = false
    Stop.syncOnStopOrFailure { triggered = true }
    assert(triggered, "triggered")
    assert(s.state.tasks.isEmpty, "there should be no async tasks registered")
  }

  test("syncOnCancel(Continue) should execute synchronously") { implicit s =>
    var triggered = false
    (Continue : Ack).syncOnStopOrFailure { triggered = true }
    assert(!triggered, "!triggered")
    assert(s.state.tasks.isEmpty, "there should be no async tasks registered")
  }

  test("syncOnCancel(Future.successful(Stop)) should execute asynchronously") { implicit s =>
    var triggered = false
    Future.successful(Stop).syncOnStopOrFailure { triggered = true }
    assert(s.state.tasks.nonEmpty, "there should be async tasks registered")
    s.tick()
    assert(triggered, "triggered")
  }

  test("syncOnCancel(Future.successful(Continue)) should execute asynchronously") { implicit s =>
    var triggered = false
    (Future.successful(Continue) : Future[Ack]).syncOnStopOrFailure { triggered = true }
    assert(s.state.tasks.nonEmpty, "there should be async tasks registered")
    s.tick()
    assert(!triggered, "!triggered")
  }

  test("syncOnCancel(Failure(ex)) should execute") { implicit s =>
    var triggered = false
    val ex = new RuntimeException("dummy")
    val ack: Future[Ack] = Future.failed(ex)
    ack.syncOnStopOrFailure { triggered = true }

    assertEquals(triggered, false)
    assert(s.state.tasks.nonEmpty, "there should be async tasks registered")
    s.tickOne()
    assertEquals(triggered, true)
  }

  test("syncOnCancel(Future(throw ex)) should execute") { implicit s =>
    var triggered = false
    val ex = new RuntimeException("dummy")
    val ack: Future[Ack] = Future { throw ex }
    ack.syncOnStopOrFailure { triggered = true }

    assertEquals(triggered, false)
    assert(s.state.tasks.nonEmpty, "there should be async tasks registered")
    s.tick()
    assertEquals(triggered, true)
  }

  test("syncOnCancel(Stop) should protect against user errors") { implicit s =>
    val ex = new RuntimeException("dummy")
    Stop.syncOnStopOrFailure { throw ex }
    assertEquals(s.state.lastReportedError, ex)
  }

  test("syncOnCancel(Stop) should not protect against fatal errors") { implicit s =>
    val ex = new InterruptedException()
    var caught: Throwable = null

    try Stop.syncOnStopOrFailure { throw ex } catch {
      case err: InterruptedException => caught = err
    }

    assertEquals(s.state.lastReportedError, null)
    assertEquals(caught, ex)
  }

  test("syncMap(Continue) should execute synchronously") { implicit s =>
    var triggered = false
    val source: Future[Ack] = Continue

    val result = source.syncMap {
      case Stop => Stop
      case Continue =>
        triggered = true
        Stop
    }

    assertEquals(result, Stop)
    assertEquals(triggered, true)
    assert(s.state.tasks.isEmpty, "there should be no async tasks registered")
  }

  test("syncMap(Stop) should execute synchronously") { implicit s =>
    var triggered = false
    val source: Future[Ack] = Stop

    val result = source.syncMap {
      case Continue => Continue
      case Stop =>
        triggered = true
        Continue
    }

    assertEquals(result, Continue)
    assertEquals(triggered, true)
    assert(s.state.tasks.isEmpty, "there should be no async tasks registered")
  }


  test("syncMap should protect against exceptions") { implicit s =>
    val dummy = new RuntimeException("dummy")
    val result = (Continue : Future[Ack]).syncMap { x => throw dummy }

    assertEquals(result, Stop)
    assertEquals(s.state.lastReportedError, dummy)
  }

  test("syncMap(Future(Continue)) should execute asynchronously") { implicit s =>
    var triggered = false
    val source: Future[Ack] = Future.successful(Continue)

    val result = source.syncMap {
      case Stop => Stop
      case Continue =>
        triggered = true
        Stop
    }

    assertEquals(triggered, false)
    assert(s.state.tasks.nonEmpty)

    s.tick()
    assert(s.state.tasks.isEmpty)
    assertEquals(result.syncTryFlatten, Stop)
    assertEquals(triggered, true)
  }

  test("syncMap(Future(Stop)) should execute asynchronously") { implicit s =>
    var triggered = false
    val source: Future[Ack] = Future.successful(Stop)

    val result = source.syncMap {
      case Continue => Continue
      case Stop =>
        triggered = true
        Continue
    }

    assertEquals(triggered, false)
    assert(s.state.tasks.nonEmpty)

    s.tick()
    assert(s.state.tasks.isEmpty)
    assertEquals(result.syncTryFlatten, Continue)
    assertEquals(triggered, true)
  }

  test("syncMap(Continue) with impure function") { implicit s =>
    var triggered = false
    val source: Future[Ack] = Continue
    val fn: Ack => Ack = {
      val value = true
      ack: Ack => ack match {
        case Stop => Stop
        case Continue =>
          triggered = value
          Stop
      }
    }

    val result = source.syncMap(fn)
    assertEquals(triggered, true)
    assertEquals(result, Stop)
  }

  test("syncMap(Future(Continue)) with impure function") { implicit s =>
    var triggered = false
    val source: Future[Ack] = Future(Continue)
    val fn: Ack => Ack = {
      val value = true
      ack: Ack => ack match {
        case Stop => Stop
        case Continue =>
          triggered = value
          Stop
      }
    }

    val result = source.syncMap(fn)

    s.tick()
    assertEquals(triggered, true)
    assertEquals(result.syncTryFlatten, Stop)
  }

  test("syncFlatMap(Continue) should execute synchronously") { implicit s =>
    var triggered = false
    val source: Future[Ack] = Continue

    val result = source.syncFlatMap {
      case Stop => Stop
      case Continue =>
        triggered = true
        Stop
    }

    assertEquals(result, Stop)
    assertEquals(triggered, true)
    assert(s.state.tasks.isEmpty, "there should be no async tasks registered")
  }

  test("syncFlatMap(Stop) should execute synchronously") { implicit s =>
    var triggered = false
    val source: Future[Ack] = Stop

    val result = source.syncFlatMap {
      case Continue => Continue
      case Stop =>
        triggered = true
        Continue
    }

    assertEquals(result, Continue)
    assertEquals(triggered, true)
    assert(s.state.tasks.isEmpty, "there should be no async tasks registered")
  }


  test("syncFlatMap should protect against exceptions") { implicit s =>
    val dummy = new RuntimeException("dummy")
    val result = (Continue : Future[Ack]).syncFlatMap { x => throw dummy }

    assertEquals(result, Stop)
    assertEquals(s.state.lastReportedError, dummy)
  }

  test("syncFlatMap(Future(Continue)) should execute asynchronously") { implicit s =>
    var triggered = false
    val source: Future[Ack] = Future.successful(Continue)

    val result = source.syncFlatMap {
      case Stop => Stop
      case Continue =>
        triggered = true
        Stop
    }

    assertEquals(triggered, false)
    assert(s.state.tasks.nonEmpty)

    s.tick()
    assert(s.state.tasks.isEmpty)
    assertEquals(result.syncTryFlatten, Stop)
    assertEquals(triggered, true)
  }

  test("syncFlatMap(Future(Stop)) should execute asynchronously") { implicit s =>
    var triggered = false
    val source: Future[Ack] = Future.successful(Stop)

    val result = source.syncFlatMap {
      case Continue => Continue
      case Stop =>
        triggered = true
        Continue
    }

    assertEquals(triggered, false)
    assert(s.state.tasks.nonEmpty)

    s.tick()
    assert(s.state.tasks.isEmpty)
    assertEquals(result.syncTryFlatten, Continue)
    assertEquals(triggered, true)
  }

  test("syncFlatMap(Continue) with impure function") { implicit s =>
    var triggered = false
    val source: Future[Ack] = Continue
    val fn: Ack => Ack = {
      val value = true
      ack: Ack => ack match {
        case Stop => Stop
        case Continue =>
          triggered = value
          Stop
      }
    }

    val result = source.syncFlatMap(fn)
    assertEquals(triggered, true)
    assertEquals(result, Stop)
  }

  test("syncFlatMap(Future(Continue)) with impure function") { implicit s =>
    var triggered = false
    val source: Future[Ack] = Future(Continue)
    val fn: Ack => Ack = {
      val value = true
      ack: Ack => ack match {
        case Stop => Stop
        case Continue =>
          triggered = value
          Stop
      }
    }

    val result = source.syncFlatMap(fn)

    s.tick()
    assertEquals(triggered, true)
    assertEquals(result.syncTryFlatten, Stop)
  }

  test("syncTryFlatten(Continue)") { implicit s =>
    val f = Continue.syncTryFlatten
    assertEquals(f, Continue)
  }

  test("syncTryFlatten(Stop)") { implicit s =>
    val f = Stop.syncTryFlatten
    assertEquals(f, Stop)
  }

  test("syncTryFlatten(Future(Continue))") { implicit s =>
    val f = Future.successful(Continue).syncTryFlatten
    assertEquals(f, Continue)
  }

  test("syncTryFlatten(Future(Stop))") { implicit s =>
    val f = Stop.syncTryFlatten
    assertEquals(f, Stop)
  }

  test("isSynchronous(Future(Continue)) == false") { implicit s =>
    val f: Future[Ack] = Future.successful(Continue)
    assert(!f.isSynchronous)
  }

  test("isSynchronous(Continue) == true") { implicit s =>
    val f: Future[Ack] = Continue
    assert(f.isSynchronous)
  }

  test("isSynchronous(Future(Stop)) == false") { implicit s =>
    val f: Future[Ack] = Future.successful(Stop)
    assert(!f.isSynchronous)
  }

  test("isSynchronous(Stop) == true") { implicit s =>
    val f: Future[Ack] = Stop
    assert(f.isSynchronous)
  }

  test("isSynchronous(failure) == false") { implicit s =>
    val f: Future[Ack] = Future.failed(new RuntimeException)
    assert(!f.isSynchronous)
  }

  test("isSynchronous(impure Future(Continue)) == false") { implicit s =>
    def f: Future[Ack] = Future.successful(Continue)
    assert(!f.isSynchronous)
  }

  test("isSynchronous(impure Continue) == true") { implicit s =>
    def f: Future[Ack] = Continue
    assert(f.isSynchronous)
  }

  test("isSynchronous(impure Future(Stop)) == false") { implicit s =>
    def f: Future[Ack] = Future.successful(Stop)
    assert(!f.isSynchronous)
  }

  test("isSynchronous(impure Stop) == true") { implicit s =>
    def f: Future[Ack] = Stop
    assert(f.isSynchronous)
  }

  test("isSynchronous(impure failure) == false") { implicit s =>
    def f: Future[Ack] = Future.failed(new RuntimeException)
    assert(!f.isSynchronous)
  }

  test("continue.syncOnComplete(clean)") { implicit s =>
    val ack: Ack = Continue
    var triggered = false

    ack.syncOnComplete {
      case Success(Continue) => triggered = true
      case _ => ()
    }

    assert(triggered, "should have been sync")
  }

  test("stop.syncOnComplete(clean)") { implicit s =>
    val ack: Ack = Stop
    var triggered = false

    ack.syncOnComplete {
      case Success(Stop) => triggered = true
      case _ => ()
    }

    assert(triggered, "should have been sync")
  }

  test("continue.syncOnComplete(unclean)") { implicit s =>
    val ack: Ack = Continue
    var triggered = false

    val fn = (x: Try[Ack]) => x match {
      case Success(Continue) => triggered = true
      case _ => ()
    }

    ack.syncOnComplete(fn)
    assert(triggered, "should have been sync")
  }

  test("stop.syncOnComplete(unclean)") { implicit s =>
    val ack: Ack = Stop
    var triggered = false

    val fn = (x: Try[Ack]) => x match {
      case Success(Stop) => triggered = true
      case _ => ()
    }

    ack.syncOnComplete(fn)
    assert(triggered, "should have been sync")
  }

  test("future(continue).syncOnComplete(clean)") { implicit s =>
    val ack: Future[Ack] = Future(Continue)
    var triggered = false

    ack.syncOnComplete {
      case Success(Continue) => triggered = true
      case _ => ()
    }

    assert(!triggered, "!triggered")
    s.tick()
    assert(triggered, "should have been async")
  }

  test("future(stop).syncOnComplete(clean)") { implicit s =>
    val ack: Future[Ack] = Future(Stop)
    var triggered = false

    ack.syncOnComplete {
      case Success(Stop) => triggered = true
      case _ => ()
    }

    assert(!triggered, "!triggered")
    s.tick()
    assert(triggered, "should have been async")
  }

  test("future(continue).syncOnComplete(unclean)") { implicit s =>
    val ack: Future[Ack] = Future(Continue)
    var triggered = false

    val fn = (x: Try[Ack]) => x match {
      case Success(Continue) => triggered = true
      case _ => ()
    }

    ack.syncOnComplete(fn)

    assert(!triggered, "!triggered")
    s.tick()
    assert(triggered, "should have been async")
  }

  test("future(stop).syncOnComplete(unclean)") { implicit s =>
    val ack: Future[Ack] = Future(Stop)
    var triggered = false

    val fn = (x: Try[Ack]) => x match {
      case Success(Stop) => triggered = true
      case _ => ()
    }

    ack.syncOnComplete(fn)

    assert(!triggered, "!triggered")
    s.tick()
    assert(triggered, "should have been async")
  }

  test("Continue.syncOnContinueFollow") { implicit s =>
    val ack: Future[Ack] = Continue
    val p = Promise[Int]()

    ack.syncOnContinueFollow(p, 1)
    // should be immediate
    assertEquals(p.future.value, Some(Success(1)))
  }

  test("Stop.syncOnContinueFollow") { implicit s =>
    val ack: Future[Ack] = Stop
    val p = Promise[Int]()

    ack.syncOnContinueFollow(p, 1)
    s.tick()
    assertEquals(p.future.value, None)
  }

  test("Future(Continue).syncOnContinueFollow") { implicit s =>
    val ack: Future[Ack] = Future(Continue)
    val p = Promise[Int]()

    ack.syncOnContinueFollow(p, 1)
    assertEquals(p.future.value, None)

    // should be async
    s.tick()
    assertEquals(p.future.value, Some(Success(1)))
  }

  test("Future(Stop).syncOnContinueFollow") { implicit s =>
    val ack: Future[Ack] = Future(Stop)
    val p = Promise[Int]()

    ack.syncOnContinueFollow(p, 1)
    s.tick()
    assertEquals(p.future.value, None)
  }

  test("Continue.syncOnStopFollow") { implicit s =>
    val ack: Future[Ack] = Continue
    val p = Promise[Int]()

    ack.syncOnStopFollow(p, 1)
    s.tick()
    assertEquals(p.future.value, None)
  }

  test("Stop.syncOnStopFollow") { implicit s =>
    val ack: Future[Ack] = Stop
    val p = Promise[Int]()

    ack.syncOnStopFollow(p, 1)
    // should be immediate
    assertEquals(p.future.value, Some(Success(1)))
  }

  test("Future(Continue).syncOnStopFollow") { implicit s =>
    val ack: Future[Ack] = Future(Continue)
    val p = Promise[Int]()

    ack.syncOnStopFollow(p, 1)
    s.tick()
    assertEquals(p.future.value, None)
  }

  test("Future(Stop).syncOnStopFollow") { implicit s =>
    val ack: Future[Ack] = Future(Stop)
    val p = Promise[Int]()

    ack.syncOnStopFollow(p, 1)
    s.tick()
    assertEquals(p.future.value, Some(Success(1)))
  }

  test("syncTryFlatten works for synchronous failure") { implicit s =>
    val dummy = new RuntimeException("dummy")
    val f: Future[Ack] = Future.failed(dummy)

    val sync = f.syncTryFlatten
    assertEquals(sync, Stop)
    assertEquals(s.state.lastReportedError, dummy)
  }

  test("Continue.transform") { implicit s =>
    val f1 = Continue.transform { r => Success(1) }
    assertEquals(f1.value, Some(Success(1)))

    val dummy = new RuntimeException("dummy")
    val f2 = Continue.transform { r => Failure(dummy) }
    assertEquals(f2.value, Some(Failure(dummy)))

    val f3 = Continue.transform { r => throw dummy }
    assertEquals(f3.value, Some(Failure(dummy)))
  }

  test("Continue.transformWith") { implicit s =>
    val f1 = Continue.transformWith { r => Future.successful(1) }
    assertEquals(f1.value, Some(Success(1)))

    val dummy = new RuntimeException("dummy")
    val f2 = Continue.transformWith { r => Future.failed(dummy) }
    assertEquals(f2.value, Some(Failure(dummy)))

    val f3 = Continue.transformWith { r => throw dummy }
    assertEquals(f3.value, Some(Failure(dummy)))
  }

  test("Stop.transform") { implicit s =>
    val f1 = Stop.transform { r => Success(1) }
    assertEquals(f1.value, Some(Success(1)))

    val dummy = new RuntimeException("dummy")
    val f2 = Stop.transform { r => Failure(dummy) }
    assertEquals(f2.value, Some(Failure(dummy)))

    val f3 = Stop.transform { r => throw dummy }
    assertEquals(f3.value, Some(Failure(dummy)))
  }

  test("Stop.transformWith") { implicit s =>
    val f1 = Stop.transformWith { r => Future.successful(1) }
    assertEquals(f1.value, Some(Success(1)))

    val dummy = new RuntimeException("dummy")
    val f2 = Stop.transformWith { r => Future.failed(dummy) }
    assertEquals(f2.value, Some(Failure(dummy)))

    val f3 = Stop.transformWith { r => throw dummy }
    assertEquals(f3.value, Some(Failure(dummy)))
  }
}
