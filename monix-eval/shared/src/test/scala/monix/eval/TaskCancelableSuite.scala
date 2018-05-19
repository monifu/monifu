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

import monix.execution.Cancelable
import monix.execution.cancelables.BooleanCancelable
import monix.execution.exceptions.DummyException

import scala.util.{Failure, Success, Try}

object TaskCancelableSuite extends BaseTestSuite {
  test("Task.cancelableS should be stack safe on repeated, right-associated binds") { implicit s =>
    def signal[A](a: A): Task[A] = Task.cancelableS[A] { (_, cb) =>
      cb.onSuccess(a)
      Cancelable.empty
    }

    val task = (0 until 10000).foldLeft(Task.now(0))((acc, _) => acc.flatMap(x => signal(x + 1)))
    val f = task.runAsync
    s.tick()

    assertEquals(f.value, Some(Success(10000)))
  }

  test("Task.cancelableS should be stack safe on repeated, left-associated binds") { implicit s =>
    def signal[A](a: A): Task[A] = Task.cancelableS[A] { (_, cb) =>
      cb.onSuccess(a)
      Cancelable.empty
    }

    val task = (0 until 10000).foldLeft(Task.now(0))((acc, _) => signal(1).flatMap(x => acc.map(y => x + y)))
    val f = task.runAsync
    s.tick()

    assertEquals(f.value, Some(Success(10000)))
  }
  
  test("Task.cancelableS should work onSuccess") { implicit s =>
    val t = Task.cancelableS[Int] { (_,cb) => cb.onSuccess(10); Cancelable.empty }
    val f = t.runAsync
    s.tick()
    assertEquals(f.value, Some(Success(10)))
  }

  test("Task.cancelableS should work onError") { implicit s =>
    val dummy = DummyException("dummy")
    val t = Task.cancelableS[Int] { (_,cb) => cb.onError(dummy); Cancelable.empty }
    val f = t.runAsync
    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("Task.cancelableS should execute immediately when executed as future") { implicit s =>
    val t = Task.cancelableS[Int] { (_,cb) => cb.onSuccess(100); Cancelable.empty }
    val result = t.runAsync
    assertEquals(result.value, Some(Success(100)))
  }

  test("Task.cancelableS should execute immediately when executed with callback") { implicit s =>
    var result = Option.empty[Try[Int]]
    val t = Task.cancelableS[Int] { (_,cb) => cb.onSuccess(100); Cancelable.empty }
    t.runOnComplete { r => result = Some(r) }
    assertEquals(result, Some(Success(100)))
  }

  test("Task.cancelable works for immediate successful value") { implicit sc =>
    val task = Task.cancelable[Int] { cb => cb.onSuccess(1); Cancelable.empty }
    assertEquals(task.runAsync.value, Some(Success(1)))
  }

  test("Task.cancelable works for immediate error") { implicit sc =>
    val e = DummyException("dummy")
    val task = Task.cancelable[Int] { cb => cb.onError(e); Cancelable.empty }
    assertEquals(task.runAsync.value, Some(Failure(e)))
  }

  test("Task.cancelable is memory safe in flatMap loops") { implicit sc =>
    def signal(n: Int): Task[Int] =
      Task.cancelable { cb => cb.onSuccess(n); Cancelable.empty }

    def loop(n: Int, acc: Int): Task[Int] =
      signal(n).flatMap { n =>
        if (n > 0) loop(n - 1, acc + 1)
        else Task.now(acc)
      }

    val f = loop(10000, 0).runAsync; sc.tick()
    assertEquals(f.value, Some(Success(10000)))
  }

  test("Task.cancelable is cancelable") { implicit sc =>
    val c = BooleanCancelable()
    val f = Task.cancelable[Int](_ => c).runAsync

    assertEquals(f.value, None)
    f.cancel()
    assertEquals(f.value, None)
    assert(c.isCanceled)
    assert(sc.state.tasks.isEmpty, "tasks.isEmpty")
  }
}
