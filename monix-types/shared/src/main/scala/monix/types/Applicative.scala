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

package monix.types

import monix.types.utils._

/** The `Applicative` type-class is a [[Functor]] that also adds the
  * capability of lifting a value in the context.
  *
  * Described in
  * [[http://www.soi.city.ac.uk/~ross/papers/Applicative.html
  * Applicative Programming with Effects]].
  *
  * To implement `Functor`:
  *
  *  - inherit from [[Functor.Type]] in derived type-classes
  *  - inherit from [[Functor.Instance]] when implementing instances
  *
  * The purpose of this type-class is to support the data-types in the
  * Monix library and it is considered a shim for a lawful type-class
  * to be supplied by libraries such as Cats or Scalaz or equivalent.
  *
  * CREDITS: The type-class encoding has been inspired by the Scato
  * project by Aloïs Cochard and [[https://github.com/scalaz/scalaz/ Scalaz 8]]
  * and the type has been extracted from [[http://typelevel.org/cats/ Cats]].
  */
trait Applicative[F[_]] extends Serializable with Functor.Type[F] {
  self: Applicative.Instance[F] =>

  def pure[A](a: A): F[A]
  def map2[A, B, Z](fa: F[A], fb: F[B])(f: (A, B) => Z): F[Z]
  def ap[A, B](ff: F[A => B])(fa: F[A]): F[B]
  def unit: F[Unit] = pure(())
  def eval[A](a: => A): F[A]

  protected def defaultEval[A](a: => A): F[A] =
    map(unit)(_ => a)
}

object Applicative {
  @inline def apply[F[_]](implicit F: Applicative[F]): Applicative[F] = F

  /** The `Applicative.Type` should be inherited in type-classes that
    * are derived from [[Applicative]].
    */
  trait Type[F[_]] extends Functor.Type[F] {
    implicit def applicative: Applicative[F]
  }

  /** The `Applicative.Instance` provides the means to combine
    * [[Applicative]] instances with other type-classes.
    *
    * To be inherited by `Applicative` instances.
    */
  trait Instance[F[_]] extends Applicative[F] with Type[F]
    with Functor.Instance[F] {

    override final def applicative: Applicative[F] = this
  }

  /** Laws for [[Applicative]]. */
  trait Laws[F[_]] extends Functor.Laws[F] with Type[F] {
    private def A: Applicative[F] = applicative
    private def F: Functor[F] = functor

    def applyComposition[A, B, C](fa: F[A], fab: F[A => B], fbc: F[B => C]): IsEquiv[F[C]] = {
      val compose: (B => C) => (A => B) => (A => C) = _.compose
      A.ap(fbc)(A.ap(fab)(fa)) <-> A.ap(A.ap(F.map(fbc)(compose))(fab))(fa)
    }

    def applicativeIdentity[A](fa: F[A]): IsEquiv[F[A]] =
      A.ap(A.pure((a: A) => a))(fa) <-> fa

    def applicativeHomomorphism[A, B](a: A, f: A => B): IsEquiv[F[B]] =
      A.ap(A.pure(f))(A.pure(a)) <-> A.pure(f(a))

    def applicativeInterchange[A, B](a: A, ff: F[A => B]): IsEquiv[F[B]] =
      A.ap(ff)(A.pure(a)) <-> A.ap(A.pure((f: A => B) => f(a)))(ff)

    def applicativeMap[A, B](fa: F[A], f: A => B): IsEquiv[F[B]] =
      F.map(fa)(f) <-> A.ap(A.pure(f))(fa)

    def applicativeComposition[A, B, C](fa: F[A], fab: F[A => B], fbc: F[B => C]): IsEquiv[F[C]] = {
      val compose: (B => C) => (A => B) => (A => C) = _.compose
      A.ap(A.ap(A.ap(A.pure(compose))(fbc))(fab))(fa) <-> A.ap(fbc)(A.ap(fab)(fa))
    }

    def applicativeEvalEquivalenceWithPure[A](a: A): IsEquiv[F[A]] =
      A.eval(a) <-> A.pure(a)

    def evalEquivalenceWithRaiseError[A](ex: Throwable)
      (implicit M: MonadError[F,Throwable]): IsEquiv[F[A]] =
      A.eval[A](throw ex) <-> M.raiseError[A](ex)
  }
}