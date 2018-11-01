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

package monix.catnap

import cats.effect.{Async, Concurrent}
import monix.execution.CancelableFuture
import monix.execution.internal.AttemptCallback
import monix.execution.schedulers.TrampolineExecutionContext.immediate
import scala.concurrent.Future

/**
  * A type class for conversions from [[scala.concurrent.Future]].
  *
  * N.B. to use its syntax, you can import [[monix.catnap.syntax]]:
  * {{{
  *   import monix.catnap.syntax._
  *   import scala.concurrent.Future
  *   // Used here only for Future.apply as the ExecutionContext
  *   import monix.execution.Scheduler.Implicits.global
  *   // Can use any data type implementing Async or Concurrent
  *   import cats.effect.IO
  *
  *   val io = IO(Future(1 + 1)).futureLift
  * }}}
  *
  * `IO` provides its own `IO.fromFuture` of course, however
  * `FutureLift` is generic and works with
  * [[monix.execution.CancelableFuture CancelableFuture]] as well.
  *
  * {{{
  *   import monix.execution.{CancelableFuture, Scheduler, FutureUtils}
  *   import scala.concurrent.Promise
  *   import scala.concurrent.duration._
  *   import scala.util.Try
  *
  *   def delayed[A](event: => A)(implicit s: Scheduler): CancelableFuture[A] = {
  *     val p = Promise[A]()
  *     val c = s.scheduleOnce(1.second) { p.complete(Try(event)) }
  *     CancelableFuture(p.future, c)
  *   }
  *
  *   // The result will be cancelable:
  *   val sum: IO[Int] = IO(delayed(1 + 1)).futureLift
  * }}}
  */
trait FutureLift[F[_]] {
  def futureLift[MF[T] <: Future[T], A](fa: F[MF[A]]): F[A]
}

object FutureLift {
  /**
    * Accessor for [[FutureLift]] values that are in scope.
    * {{{
    *   import cats.effect.IO
    *   import scala.concurrent.Future
    *
    *   val ioa = FutureLift[IO].futureLift(IO(Future.successful(1)))
    * }}}
    */
  def apply[F[_]](implicit F: FutureLift[F]): FutureLift[F] = F

  /**
    * Utility for converting [[scala.concurrent.Future Future]] values into
    * data types that implement
    * [[https://typelevel.org/cats-effect/typeclasses/async.html cats.effect.Async]].
    *
    * N.B. the implementation discriminates
    * [[monix.execution.CancelableFuture CancelableFuture]] via sub-typing,
    * and if the given future is cancelable, then the resulting instance
    * is also cancelable.
    */
  def toAsync[F[_], MF[T] <: Future[T], A](fa: F[MF[A]])
    (implicit F: Async[F]): F[A] = {

    F.flatMap(fa) { future =>
      future.value match {
        case Some(value) => F.fromTry(value)
        case _ => startAsync(future)
      }
    }
  }

  /**
    * Utility for converting [[scala.concurrent.Future Future]] values into
    * data types that implement
    * [[https://typelevel.org/cats-effect/typeclasses/concurrent.html cats.effect.Concurrent]].
    *
    * N.B. the implementation discriminates
    * [[monix.execution.CancelableFuture CancelableFuture]] via sub-typing,
    * and if the given future is cancelable, then the resulting instance
    * is also cancelable.
    */
  def toConcurrent[F[_], MF[T] <: Future[T], A](fa: F[MF[A]])
    (implicit F: Concurrent[F]): F[A] = {

    F.flatMap(fa) { future =>
      future.value match {
        case Some(value) => F.fromTry(value)
        case _ =>
          future match {
            case cf: CancelableFuture[A] @unchecked =>
              startCancelable(cf)
            case _ =>
              startAsync(future)
          }
      }
    }
  }

  /**
    * A generic function that subsumes both [[toAsync]] and
    * [[toConcurrent]].
    *
    * N.B. this works with [[monix.execution.CancelableFuture]]
    * if the given `Future` is such an instance.
    */
  def toConcurrentOrAsync[F[_], MF[T] <: Future[T], A](fa: F[MF[A]])
    (implicit F: Concurrent[F] OrElse Async[F]): F[A] = {

    F.unify match {
      case ref: Concurrent[F] @unchecked =>
        toConcurrent[F, MF, A](fa)(ref)
      case ref =>
        toAsync[F, MF, A](fa)(ref)
    }
  }

  /**
    * Implicit instance of [[FutureLift]] for all `Concurrent` or `Async`
    * data types.
    */
  implicit def forConcurrentOrAsync[F[_]](implicit F: Concurrent[F] OrElse Async[F]): FutureLift[F] =
    F.unify match {
      case ref: Concurrent[F] @unchecked =>
        new FutureLift[F] {
          def futureLift[MF[T] <: Future[T], A](fa: F[MF[A]]): F[A] =
            toConcurrent[F, MF, A](fa)(ref)
        }
      case ref =>
        new FutureLift[F] {
          def futureLift[MF[T] <: Future[T], A](fa: F[MF[A]]): F[A] =
            toAsync[F, MF, A](fa)(ref)
        }
    }

  /**
    * Provides extension methods when imported in scope via [[syntax]].
    *
    * {{{
    *   import monix.catnap.syntax._
    * }}}
    */
  trait Syntax[F[_], MF[T] <: Future[T], A] extends Any {
    def source: F[MF[A]]

    def futureLift(implicit F: Concurrent[F] OrElse Async[F]): F[A] =
      FutureLift.toConcurrentOrAsync[F, MF, A](source)(F)
  }

  private def start[A](fa: Future[A], cb: Either[Throwable, A] => Unit): Unit = {
    implicit val ec = immediate
    fa.onComplete(AttemptCallback.toTry(cb))
  }

  private def startAsync[F[_], A](fa: Future[A])(implicit F: Async[F]): F[A] =
    F.async { cb => start(fa, cb) }

  private def startCancelable[F[_], A](fa: CancelableFuture[A])(implicit F: Concurrent[F]): F[A] =
    F.cancelable { cb =>
      start(fa, cb)
      fa.toCancelToken[F]
    }
}
