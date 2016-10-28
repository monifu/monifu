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

package monix.execution.schedulers

import java.util.concurrent.{CountDownLatch, TimeUnit, TimeoutException}
import minitest.SimpleTestSuite
import monix.execution.cancelables.SingleAssignmentCancelable
import monix.execution.schedulers.ExecutionModel.AlwaysAsyncExecution
import monix.execution.{Cancelable, Scheduler}
import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}

object AsyncSchedulerSuite extends SimpleTestSuite {
  val s: Scheduler = monix.execution.Scheduler.global

  def scheduleOnce(s: Scheduler, delay: FiniteDuration)(action: => Unit): Cancelable = {
    s.scheduleOnce(delay.length, delay.unit, runnableAction(action))
  }

  test("scheduleOnce with delay") {
    val p = Promise[Long]()
    val startedAt = System.nanoTime()
    scheduleOnce(s, 100.millis)(p.success(System.nanoTime()))

    val timeTaken = Await.result(p.future, 3.second)
    assert((timeTaken - startedAt).nanos.toMillis >= 100)
  }

  test("scheduleOnce with delay lower than 1.milli") {
    val p = Promise[Int]()
    scheduleOnce(s, 20.nanos)(p.success(1))
    assert(Await.result(p.future, 3.seconds) == 1)
  }

  test("scheduleOnce with delay and cancel") {
    val p = Promise[Int]()
    val task = scheduleOnce(s, 100.millis)(p.success(1))
    task.cancel()

    intercept[TimeoutException] {
      Await.result(p.future, 150.millis)
    }
  }

  test("schedule with fixed delay") {
    val sub = SingleAssignmentCancelable()
    val p = Promise[Int]()
    var value = 0

    sub := s.scheduleWithFixedDelay(10, 50, TimeUnit.MILLISECONDS, runnableAction {
      if (value + 1 == 4) {
        value += 1
        sub.cancel()
        p.success(value)
      }
      else if (value < 4) {
        value += 1
      }
    })

    assert(Await.result(p.future, 5.second) == 4)
  }

  test("schedule at fixed rate") {
    val sub = SingleAssignmentCancelable()
    val p = Promise[Int]()
    var value = 0

    sub := s.scheduleAtFixedRate(10, 50, TimeUnit.MILLISECONDS, runnableAction {
      if (value + 1 == 4) {
        value += 1
        sub.cancel()
        p.success(value)
      }
      else if (value < 4) {
        value += 1
      }
    })

    assert(Await.result(p.future, 5.second) == 4)
  }

  test("builder for ExecutionModel works") {
    import monix.execution.schedulers.ExecutionModel.AlwaysAsyncExecution
    import monix.execution.Scheduler

    val s: Scheduler = Scheduler(AlwaysAsyncExecution)
    assertEquals(s.executionModel, AlwaysAsyncExecution)

    val latch = new CountDownLatch(1)
    s.execute(new Runnable {
      def run(): Unit = latch.countDown()
    })

    assert(latch.await(2, TimeUnit.MINUTES), "latch.await")
  }

  test("execute local") {
    var result = 0
    def loop(n: Int): Unit =
      s.executeTrampolined { () =>
        result += 1
        if (n-1 > 0) loop(n-1)
      }

    val count = 100000
    loop(count)
    assertEquals(result, count)
  }

  test("change execution model") {
    val s: Scheduler = monix.execution.Scheduler.global
    assertEquals(s.executionModel, ExecutionModel.Default)
    val s2 = s.withExecutionModel(AlwaysAsyncExecution)
    assertEquals(s.executionModel, ExecutionModel.Default)
    assertEquals(s2.executionModel, AlwaysAsyncExecution)
  }

  def runnableAction(f: => Unit): Runnable =
    new Runnable { def run() = f }
}
