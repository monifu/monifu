/*
 * Copyright (c) 2014-2017 by The Monix Project Developers.
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

package monix.cats.tests

import cats.{CoflatMap, Group, Monad, MonadError, Monoid, Semigroup}
import cats.implicits._
import minitest.SimpleTestSuite
import monix.cats.MonixToCatsConversions
import monix.eval.Task
import monix.execution.schedulers.TestScheduler

object TaskCatsSanitySuite extends SimpleTestSuite with MonixToCatsConversions {
  test("Task is Monad") {
    val ref = implicitly[Monad[Task]]
    assert(ref != null)
  }

  test("Task has Monad syntax") {
    implicit val s = TestScheduler()
    val task = Task.now(1)
    val product = task.product(Task.now(2))
    assertEquals(product.coeval.value, Right((1,2)))
  }

  test("Task is MonadError") {
    val ref = implicitly[MonadError[Task, Throwable]]
    assert(ref != null)
  }

  test("Task is CoflatMap") {
    val ref = implicitly[CoflatMap[Task]]
    assert(ref != null)
  }

  test("Task is Semigroup") {
    val ref = implicitly[Semigroup[Task[Int]]]
    assert(ref != null)
  }

  test("Task is Monoid") {
    val ref = implicitly[Monoid[Task[Int]]]
    assert(ref != null)
  }

  test("Task is Group") {
    val ref = implicitly[Group[Task[Int]]]
    assert(ref != null)
  }
}