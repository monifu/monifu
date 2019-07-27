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

import cats.Monoid
import cats.laws._
import cats.laws.discipline._
import monix.eval.Task
import monix.reactive.{BaseTestSuite, Observable}

object ScanMapSuite extends BaseTestSuite {

  test("Observable.scanMap equivalence to Observable.scan") { implicit s =>
    val obs1 = Observable(1, 2, 3, 4).scanMap(x => x)
    val obs2 = Observable(1, 2, 3, 4).scan(0)(_ + _)
    val f1 = obs1.runAsyncGetLast
    val f2 = obs2.runAsyncGetLast
    s.tick()
    assertEquals(f1.value, f2.value)
  }

  test("Observable.scanMap0.drop(1) <-> Observable.scanMap") { implicit s =>
    check1 { (obs: Observable[Int]) =>
      obs.scanMap0(x => x).drop(1) <-> obs.scanMap(x => x)
    }
  }

  test("Observable.scanMap0 emits empty element as head") { implicit s =>
    check1 { (obs: Observable[Int]) =>
      obs.scanMap0(x => x).headL <-> Task.pure(Monoid[Int].empty)
    }
  }
}
