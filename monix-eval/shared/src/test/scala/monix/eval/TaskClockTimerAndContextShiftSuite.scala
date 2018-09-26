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

package monix.eval

import java.util.concurrent.TimeUnit

import cats.effect.{Clock, ContextShift, Timer}
import monix.execution.schedulers.TestScheduler

import scala.concurrent.duration._
import scala.util.Success

object TaskClockTimerAndContextShiftSuite extends BaseTestSuite {
  test("Task.clock is implicit") { _ =>
    assertEquals(Task.clock, implicitly[Clock[Task]])
  }

  test("Task.clock.monotonic") { implicit s =>
    s.tick(1.seconds)

    val f = Task.clock.monotonic(TimeUnit.SECONDS).runAsync
    s.tick()

    assertEquals(f.value, Some(Success(1)))
  }

  test("Task.clock.realTime") { implicit s =>
    s.tick(1.seconds)

    val f = Task.clock.realTime(TimeUnit.SECONDS).runAsync
    s.tick()

    assertEquals(f.value, Some(Success(1)))
  }

  test("Task.clock(s2).monotonic") { implicit s =>
    val s2 = TestScheduler()
    s2.tick(1.seconds)

    val f = Task.clock(s2).monotonic(TimeUnit.SECONDS).runAsync
    s.tick()

    assertEquals(f.value, Some(Success(1)))
  }

  test("Task.clock(s).realTime") { implicit s =>
    val s2 = TestScheduler()
    s2.tick(1.seconds)

    val f = Task.clock(s2).realTime(TimeUnit.SECONDS).runAsync
    s.tick()

    assertEquals(f.value, Some(Success(1)))
  }

  test("Task.timer is implicit") { implicit s =>
    assertEquals(Task.timer, implicitly[Timer[Task]])
    assertEquals(Task.timer.clock, implicitly[Clock[Task]])
  }

  test("Task.timer") { implicit s =>
    val f = Task.timer.sleep(1.second).runAsync
    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(())))
  }

  test("Task.timer(s)") { implicit s =>
    val s2 = TestScheduler()
    val f = Task.timer(s2).sleep(1.second).runAsync

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, None)
    s2.tick(1.second)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(())))
  }

  test("Task.timer(s).clock") { implicit s =>
    val s2 = TestScheduler()
    s2.tick(1.second)

    val f = Task.timer(s2).clock.monotonic(TimeUnit.SECONDS).runAsync
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("Task.contextShift is implicit") { implicit s =>
    assertEquals(Task.contextShift, implicitly[ContextShift[Task]])
  }

  test("Task.contextShift.shift") { implicit s =>
    val f = Task.contextShift.shift.runAsync
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(())))
  }

  test("Task.contextShift.evalOn(s2)") { implicit s =>
    val s2 = TestScheduler()
    val f = Task.contextShift.evalOn(s2)(Task(1)).runAsync

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("Task.contextShift(s).shift") { implicit s =>
    val f = Task.contextShift(s).shift.runAsync
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(())))
  }

  test("Task.contextShift(s).evalOn(s2)") { implicit s =>
    val s2 = TestScheduler()
    val f = Task.contextShift(s).evalOn(s2)(Task(1)).runAsync

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }
}
