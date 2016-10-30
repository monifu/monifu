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

/** This type-class represents monads with a tail-recursive
  * `flatMap` implementation.
  *
  * Based on Phil Freeman's
  * [[http://functorial.com/stack-safety-for-free/index.pdf Stack Safety for Free]].
  *
  * To implement `MonadRec`:
  *
  *  - inherit from [[MonadRec.Type]] in derived type-classes
  *  - inherit from [[MonadRec.Instance]] when implementing instances
  *
  * The purpose of this type-class is to support the data-types in the
  * Monix library and it is considered a shim for a lawful type-class
  * to be supplied by libraries such as Cats or Scalaz or equivalent.
  *
  * CREDITS: The type-class encoding has been inspired by the Scado
  * project and [[https://github.com/scalaz/scalaz/ Scalaz 8]] and
  * the type has been extracted from [[http://typelevel.org/cats/ Cats]].
  */
trait MonadRec[F[_]] extends Serializable with Monad.Type[F] {
  self: MonadRec.Instance[F] =>

  /** Keeps calling `f` until a `scala.util.Right[B]` is returned. */
  def tailRecM[A, B](a: A)(f: A => F[Either[A, B]]): F[B]
}

object MonadRec {
  @inline def apply[F[_]](implicit F: MonadRec[F]): MonadRec[F] = F

  /** The `MonadRec.Type` should be inherited in type-classes that
    * are derived from [[MonadRec]].
    */
  trait Type[F[_]] extends Monad.Type[F] {
    implicit def monadRec: MonadRec[F]
  }

  /** The `MonadRec.Instance` provides the means to combine
    * [[MonadRec]] instances with other type-classes.
    *
    * To be inherited by `MonadRec` instances.
    */
  trait Instance[F[_]] extends MonadRec[F] with Type[F] with Monad.Instance[F] {
    override final def monadRec: MonadRec[F] = this
  }

  /** Laws for [[MonadRec]]. */
  trait Laws[F[_]] extends Monad.Laws[F] with Type[F] {
    private def F = functor
    private def R = monadRec
    private def M = monad
    private def A = applicative

    def tailRecMConsistentFlatMap[A](count: Int, a: A, f: A => F[A]): IsEquiv[F[A]] = {
      def bounce(n: Int) = R.tailRecM[(A, Int), A]((a, n)) { case (a0, i) =>
        if (i > 0) F.map(f(a0))(a1 => Left((a1, i-1)))
        else F.map(f(a0))(Right(_))
      }

      val smallN = (count % 2) + 2 // a number 1 to 3
      bounce(smallN) <-> M.flatMap(bounce(smallN - 1))(f)
    }

    def tailRecMStackSafety(n: Int): IsEquiv[F[Int]] = {
      val res = R.tailRecM(0)(i => A.pure[Either[Int,Int]](if (i < n) Left(i + 1) else Right(i)))
      res <-> A.pure(n)
    }
  }

  /** A reusable implementation for [[MonadRec.tailRecM]] that relies on
    * [[Monad.flatMap]].
    *
    * NOTE: this is UNSAFE to use in case `flatMap` is not
    * tail-recursive.
    */
  final def defaultTailRecM[F[_], A, B](a: A)(f: A => F[Either[A, B]])
    (implicit F: Monad[F]): F[B] =
    F.flatMap(f(a)) {
      case Right(b) =>
        F.applicative.pure(b)
      case Left(nextA) =>
        defaultTailRecM(nextA)(f)
    }
}
