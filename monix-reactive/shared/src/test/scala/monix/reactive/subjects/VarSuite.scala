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

package monix.reactive.subjects

import minitest.TestSuite
import monix.execution.schedulers.TestScheduler

object VarSubjectSuite extends TestSuite[TestScheduler] {

  def setup() = TestScheduler()
  def tearDown(s: TestScheduler) = {
    assert(s.state.tasks.isEmpty,
      "TestScheduler should have no pending tasks")
  }

  test("initial value is emitted on subscribe") { implicit s =>
    val var0 = Var(0)
    var emitted: Option[Int] = None

    var0.foreach { x => emitted = Some(x) }
    s.tick()

    emitted match {
      case Some(x) => assertEquals(x, var0())
      case None => fail("Initial value was never emitted!")
    }
  }

  test("new value is emitted on update") { implicit s =>
    val var0 = Var(0)
    var emitted: Option[Int] = None

    var0.foreach { x => emitted = Some(x) }

    s.tick()
    var0 := 123
    s.tick()

    emitted match {
      case Some(x) => assertEquals(x, var0())
      case None => fail("Initial value was never emitted!")
    }
  }
}
