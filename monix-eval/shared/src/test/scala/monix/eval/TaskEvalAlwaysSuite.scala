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

import scala.util.{Failure, Success}

object TaskEvalAlwaysSuite extends BaseTestSuite {
  test("Task.eval should work synchronously") { implicit s =>
    var wasTriggered = false
    def trigger(): String = { wasTriggered = true; "result" }

    val task = Task.eval(trigger())
    assert(!wasTriggered, "!wasTriggered")

    val f = task.runAsync
    assert(wasTriggered, "wasTriggered")
    assertEquals(f.value, Some(Success("result")))
  }

  test("Task.eval should protect against user code errors") { implicit s =>
    val ex = DummyException("dummy")
    val f = Task.eval[Int](if (1 == 1) throw ex else 1).runAsync

    assertEquals(f.value, Some(Failure(ex)))
    assertEquals(s.state.lastReportedError, null)
  }

  test("Task.eval is equivalent with Task.evalOnce on first run") { implicit s =>
    check1 { a: Int =>
      val t1 = {
        var effect = 100
        Task.eval { effect += 100; effect + a }
      }

      val t2 = {
        var effect = 100
        Task.evalOnce { effect += 100; effect + a }
      }

      t1 === t2
    }
  }

  test("Task.eval is not equivalent with Task.evalOnce on second run") { implicit s =>
    check1 { a: Int =>
      val t1 = {
        var effect = 100
        Task.eval { effect += 100; effect + a }
      }

      val t2 = {
        var effect = 100
        Task.evalOnce { effect += 100; effect + a }
      }

      // Running once to trigger effects
      t1.runAsync(s)
      t2.runAsync(s)
      s.tick()

      t1 !== t2
    }
  }

  test("Task.eval.flatMap should be equivalent with Task.eval") { implicit s =>
    val ex = DummyException("dummy")
    val t = Task.eval[Int](if (1 == 1) throw ex else 1).flatMap(Task.now)
    check(t === Task.raiseError(ex))
  }

  test("Task.eval.flatMap should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val t = Task.eval(1).flatMap[Int](_ => throw ex)
    check(t === Task.raiseError(ex))
  }

  test("Task.eval.flatMap should be tail recursive") { implicit s =>
    def loop(n: Int, idx: Int): Task[Int] =
      Task.eval(idx).flatMap { a =>
        if (idx < n) loop(n, idx + 1).map(_ + 1) else
          Task.eval(idx)
      }

    val iterations = s.executionModel.recommendedBatchSize * 20
    val f = loop(iterations, 0).runAsync
    s.tick()
    assertEquals(f.value, Some(Success(iterations * 2)))
  }

  test("Task.eval should not be cancelable") { implicit s =>
    val t = Task.eval(10)
    val f = t.runAsync
    f.cancel()
    s.tick()
    assertEquals(f.value, Some(Success(10)))
  }

  test("Task.eval.coeval") { implicit s =>
    val result = Task.eval(100).coeval.value
    assertEquals(result, Right(100))
  }
}
