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

package monix.reactive.observables

import cats.Apply
import monix.execution.internal.Newtype1
import monix.reactive.Observable

/** A `CombineObservable` is an observable that wraps a regular
  * [[Observable]] and provide [[cats.Apply]] instance
  * which uses [[Observable.combineLatest]] to combine elements.
  */

object CombineObservable extends Newtype1 {

  def apply[A](value: Observable[A]): CombineObservable.Type[A] =
    value.asInstanceOf[CombineObservable.Type[A]]

  def unwrap[A](value: CombineObservable.Type[A]): Observable[A] =
    value.asInstanceOf[Observable[A]]

  implicit def combineObservableApplicative: Apply[CombineObservable.Type] = new Apply[CombineObservable.Type] {
    import CombineObservable.{apply => wrap}

    def ap[A, B](ff: CombineObservable.Type[(A) => B])(fa: CombineObservable.Type[A]) =
      wrap(unwrap(ff).combineLatestMap(unwrap(fa))((f, a) => f(a)))

    override def map[A, B](fa: CombineObservable.Type[A])(f: A => B): CombineObservable.Type[B] =
      wrap(unwrap(fa).map(f))

    override def product[A, B](fa: CombineObservable.Type[A], fb: CombineObservable.Type[B]): CombineObservable.Type[(A, B)] =
      wrap(unwrap(fa).combineLatest(unwrap(fb)))
  }
}