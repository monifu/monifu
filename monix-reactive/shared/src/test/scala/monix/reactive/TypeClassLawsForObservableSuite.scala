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

package monix.reactive

import cats.laws.discipline.{AlternativeTests, ApplyTests, CoflatMapTests, MonadErrorTests, MonoidKTests, NonEmptyParallelTests}
import monix.reactive.observables.CombineObservable

object TypeClassLawsForObservableSuite extends BaseLawsTestSuite {
  checkAllAsync("MonadError[Observable, Throwable]") { implicit ec =>
    MonadErrorTests[Observable, Throwable].monadError[Int, Int, Int]
  }

  checkAllAsync("CoflatMap[Observable]") { implicit ec =>
    CoflatMapTests[Observable].coflatMap[Int, Int, Int]
  }

  checkAllAsync("Alternative[Observable]") { implicit ec =>
    AlternativeTests[Observable].alternative[Int, Int, Int]
  }

  checkAllAsync("MonoidK[Observable]") { implicit ec =>
    MonoidKTests[Observable].monoidK[Int]
  }

  checkAllAsync("Apply[CombineObservable.Type]") { implicit ec =>
    ApplyTests[CombineObservable.Type].apply[Int, Int, Int]
  }

  checkAllAsync("NonEmptyParallel[Observable, CombineObservable.Type]") { implicit ec =>
    NonEmptyParallelTests[Observable, CombineObservable.Type].nonEmptyParallel[Int, Int]
  }
}
