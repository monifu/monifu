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

import cats.laws._
import cats.laws.discipline._

import concurrent.duration._
import scala.util.Success

object TaskStartSuite extends BaseTestSuite {
  test("task.start.flatMap(id) <-> task") { implicit sc =>
    check1 { (task: Task[Int]) =>
      task.start.flatMap(x => x) <-> task
    }
  }

  test("task.start.flatMap(id) is cancelable") { implicit sc =>
    val task = Task.eval(1).delayExecution(1.second).start.flatMap(x => x)
    val f = task.runAsync

    assert(sc.state.tasks.nonEmpty, "tasks.nonEmpty")
    f.cancel()
    assert(sc.state.tasks.isEmpty, "tasks.isEmpty")

    sc.tick(1.second)
    assertEquals(f.value, None)
  }

  test("task.start is stack safe") { implicit sc =>
    var task: Task[Any] = Task(1)
    for (_ <- 0 until 5000) task = task.start
    for (_ <- 0 until 5000) task = task.flatMap(x => x.asInstanceOf[Task[Any]])

    val f = task.runAsync
    sc.tick()
    assertEquals(f.value, Some(Success(1)))
  }
}
