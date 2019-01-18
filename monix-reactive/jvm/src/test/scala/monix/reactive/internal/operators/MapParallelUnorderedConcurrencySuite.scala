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

import cats.laws._
import cats.laws.discipline._

import monix.eval.Task
import monix.reactive.{BaseConcurrencySuite, Observable}

object MapParallelUnorderedConcurrencySuite extends BaseConcurrencySuite {
  test("mapParallelUnordered works concurrently") { implicit s =>
    check2 { (list: List[Int], rnd: Int) =>
      val parallelism = {
        val abs = math.abs(rnd)
        if (abs <= 0) 1 else (abs % 20) + 1
      }

      val task1 = Observable.fromIterable(list).mapParallelUnordered(parallelism)(x => Task.evalAsync(x)).sumL
      val task2 = Task.eval(list.sum)
      task1 <-> task2
    }
  }
}
