/*
 * Copyright (c) 2014-2017 by its authors. Some rights reserved.
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

import monix.execution.internal.Platform
import scala.util.{Failure, Success}

object TaskAttemptSuite extends BaseTestSuite {
  test("materialize flatMap loop") { implicit s =>
    val count = if (Platform.isJVM) 10000 else 1000

    def loop[A](source: Task[A], n: Int): Task[A] =
      source.materialize.flatMap {
        case Success(a) =>
          if (n <= 0) Task.now(a)
          else loop(source, n - 1)
        case Failure(ex) =>
          Task.raiseError(ex)
      }

    val f = loop(Task.eval("value"), count).runAsync

    s.tick()
    assertEquals(f.value, Some(Success("value")))
  }

  test("materialize foldLeft sequence") { implicit s =>
    val count = if (Platform.isJVM) 10000 else 1000

    val loop = (0 until count).foldLeft(Task.eval(0)) { (acc, _) =>
      acc.materialize.flatMap {
        case Success(x) =>
          Task.now(x + 1)
        case Failure(ex) =>
          Task.raiseError(ex)
      }
    }

    val f = loop.runAsync

    s.tick()
    f.value.get.get
    assertEquals(f.value, Some(Success(count)))
  }
}
