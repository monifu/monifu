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

package monix.eval

import cats.Contravariant
import monix.execution.cancelables.StackedCancelable
import monix.execution.misc.NonFatal
import monix.execution.schedulers.TrampolinedRunnable
import monix.execution.{Listener, Scheduler, UncaughtExceptionReporter}

import scala.concurrent.Promise
import scala.util.{Failure, Success, Try}

/** Represents a callback that should be called asynchronously
  * with the result of a computation. Used by [[monix.eval.Task Task]] to signal
  * the completion of asynchronous computations on `runAsync`.
  *
  * The `onSuccess` method should be called only once, with the successful
  * result, whereas `onError` should be called if the result is an error.
  *
  * Obviously `Callback` describes unsafe side-effects, a fact that is
  * highlighted by the usage of `Unit` as the return type. Obviously
  * callbacks are unsafe to use in pure code, but are necessary for
  * describing asynchronous processes, like in
  * [[Task.cancelableS[A](register* Task.cancelable]].
  */
abstract class Callback[-A] extends Listener[A] with (Either[Throwable, A] => Unit) {
  def onSuccess(value: A): Unit

  def onError(ex: Throwable): Unit

  final def onValue(value: A): Unit =
    onSuccess(value)

  final def apply(result: Try[A]): Unit =
    result match {
      case Success(a) => onSuccess(a)
      case Failure(e) => onError(e)
    }

  final def apply(result: Coeval[A]): Unit =
    result.run() match {
      case Coeval.Now(a) => onSuccess(a)
      case Coeval.Error(e) => onError(e)
    }

  final def apply(result: Either[Throwable, A]): Unit =
    result match {
      case Right(a) => onSuccess(a)
      case Left(e) => onError(e)
    }

  /** Return a new callback that will apply the supplied function
    * before passing the result into this callback.
    */
  final def contramap[B](f: B => A): Callback[B] =
    new Callback.ContramapCallback(this, f)
}

object Callback {
  /** Wraps any [[Callback]] into a safer implementation that
    * protects against grammar violations (e.g. `onSuccess` or `onError`
    * must be called at most once). For usage in `runAsync`.
    */
  def safe[A](cb: Callback[A])
    (implicit r: UncaughtExceptionReporter): Callback[A] =
    cb match {
      case _: SafeCallback[_] => cb
      case _ => new SafeCallback[A](cb)
    }

  /** Creates an empty [[Callback]], a callback that doesn't do
    * anything in `onNext` and that logs errors in `onError` with
    * the provided [[monix.execution.UncaughtExceptionReporter]].
    */
  def empty[A](implicit r: UncaughtExceptionReporter): Callback[A] =
    new EmptyCallback(r)

  /** Returns a [[Callback]] instance that will complete the given
    * promise.
    */
  def fromPromise[A](p: Promise[A]): Callback[A] =
    new Callback[A] {
      def onError(ex: Throwable): Unit = p.failure(ex)
      def onSuccess(value: A): Unit = p.success(value)
    }

  /** Given a [[Callback]] wraps it into an implementation that
    * calls `onSuccess` and `onError` asynchronously, using the
    * given [[monix.execution.Scheduler Scheduler]].
    *
    * The async boundary created is "light", in the sense that a
    * [[monix.execution.schedulers.TrampolinedRunnable TrampolinedRunnable]]
    * is used and supporting schedulers can execute these using an internal
    * trampoline, thus execution being faster and immediate, but still avoiding
    * growing the call-stack and thus avoiding stack overflows.
    *
    * @see [[Callback.trampolined[A](cb* trampolined]]
    */
  def forked[A](cb: Callback[A])(implicit s: Scheduler): Callback[A] =
    new AsyncForkCallback[A](cb)

  /** Given a [[Callback]] wraps it into an implementation that
    * calls `onSuccess` and `onError` asynchronously, using the
    * given [[monix.execution.Scheduler Scheduler]].
    *
    * The async boundary created is "light", in the sense that a
    * [[monix.execution.schedulers.TrampolinedRunnable TrampolinedRunnable]]
    * is used and supporting schedulers can execute these using an internal
    * trampoline, thus execution being faster and immediate, but still avoiding
    * growing the call-stack and thus avoiding stack overflows.
    *
    * @see [[forked]]
    */
  def trampolined[A](cb: Callback[A])(implicit s: Scheduler): Callback[A] =
    new TrampolinedCallback[A](cb)

  /** A variant of [[trampolined[A](cb* Callback.trampolined]] that also pops a
    * [[monix.execution.cancelables.StackedCancelable StackedCancelable]]
    * just before calling the underlying callback.
    */
  def trampolined[A](conn: StackedCancelable, cb: Callback[A])
    (implicit s: Scheduler): Callback[A] =
    new TrampolinedWithConn[A](conn, cb)

  /** DEPRECATED — renamed to [[Callback.trampolined[A](cb* trampolined]]. */
  @deprecated("Renamed to Callback.trampolined", since = "3.0.0-RC2")
  def async[A](cb: Callback[A])(implicit s: Scheduler): Callback[A] = {
    // $COVERAGE-OFF$
    trampolined(cb)(s)
    // $COVERAGE-ON$
  }

  /** Turns `Either[Throwable, A] => Unit` callbacks into Monix
    * callbacks.
    *
    * These are common within Cats' implementation, used for
    * example in `cats.effect.IO`.
    */
  def fromAttempt[A](cb: Either[Throwable, A] => Unit): Callback[A] =
    new Callback[A] {
      def onSuccess(value: A): Unit = cb(Right(value))
      def onError(ex: Throwable): Unit = cb(Left(ex))
    }

  implicit final class Extensions[-A](val source: Callback[A]) extends AnyVal {
    @deprecated("Switch to Callback.trampolined", since="3.0.0-RC2")
    def asyncOnSuccess(value: A)(implicit s: Scheduler): Unit = {
      // $COVERAGE-OFF$
      /*_*/s.executeTrampolined(() => source.onSuccess(value))/*_*/
      // $COVERAGE-ON$
    }

    @deprecated("Switch to Callback.trampolined", since="3.0.0-RC2")
    def asyncOnError(ex: Throwable)(implicit s: Scheduler): Unit = {
      // $COVERAGE-OFF$
      /*_*/s.executeTrampolined(() => source.onError(ex))/*_*/
      // $COVERAGE-ON$
    }

    @deprecated("Switch to Callback.trampolined", since="3.0.0-RC2")
    def asyncApply(value: Coeval[A])(implicit s: Scheduler): Unit = {
      // $COVERAGE-OFF$
      /*_*/s.executeTrampolined(() => source(value))/*_*/
      // $COVERAGE-ON$
    }

    @deprecated("Switch to Callback.trampolined", since="3.0.0-RC2")
    def asyncApply(value: Try[A])(implicit s: Scheduler): Unit = {
      // $COVERAGE-OFF$
      /*_*/s.executeTrampolined(() => source(value))/*_*/
      // $COVERAGE-ON$
    }

    @deprecated("Switch to Callback.trampolined", since="3.0.0-RC2")
    def asyncApply(value: Either[Throwable, A])(implicit s: Scheduler): Unit = {
      // $COVERAGE-OFF$
      /*_*/s.executeTrampolined(() => source(value))/*_*/
      // $COVERAGE-ON$
    }
  }

  private final class AsyncForkCallback[A](cb: Callback[A])
    (implicit s: Scheduler)
    extends BaseCallback[A](cb)(s)

  private final class TrampolinedCallback[A](cb: Callback[A])
    (implicit s: Scheduler)
    extends BaseCallback[A](cb)(s) with TrampolinedRunnable

  private final class TrampolinedWithConn[A](conn: StackedCancelable, cb: Callback[A])
    (implicit s: Scheduler)
    extends BaseCallback[A](cb)(s) with TrampolinedRunnable {

    override def run(): Unit = {
      conn.pop()
      super.run()
    }
  }

  /** Base implementation for `trampolined` and `forked`. */
  private class BaseCallback[A](cb: Callback[A])
    (implicit s: Scheduler)
    extends Callback[A] with Runnable {

    private[this] var canCall = true
    private[this] var value: A = _
    private[this] var error: Throwable = _

    final def onSuccess(value: A): Unit = {
      if (canCall) {
        canCall = false
        this.value = value
        s.execute(this)
      }
    }
    final def onError(e: Throwable): Unit = {
      if (canCall) {
        this.error = e
        s.execute(this)
      } else {
        s.reportFailure(e)
      }
    }
    def run() = {
      val e = error
      if (e ne null) {
        cb.onError(e)
        error = null
      } else {
        cb.onSuccess(value)
        value = null.asInstanceOf[A]
      }
    }
  }

  /** An "empty" callback instance doesn't do anything `onSuccess` and
    * only logs exceptions `onError`.
    */
  private final class EmptyCallback(r: UncaughtExceptionReporter)
    extends Callback[Any] {

    def onSuccess(value: Any): Unit = ()
    def onError(ex: Throwable): Unit =
      r.reportFailure(ex)
  }

  /** A `SafeCallback` is a callback that ensures it can only be called
    * once, with a simple check.
    */
  private final class SafeCallback[-A](underlying: Callback[A])
    (implicit r: UncaughtExceptionReporter)
    extends Callback[A] {

    private[this] var isActive = true

    /** To be called only once, on successful completion of a [[monix.eval.Task Task]] */
    def onSuccess(value: A): Unit =
      if (isActive) {
        isActive = false
        try underlying.onSuccess(value) catch {
          case ex if NonFatal(ex) =>
            r.reportFailure(ex)
        }
      }

    /** To be called only once, on failure of a [[monix.eval.Task Task]] */
    def onError(ex: Throwable): Unit =
      if (isActive) {
        isActive = false
        try underlying.onError(ex) catch {
          case err if NonFatal(err) =>
            r.reportFailure(ex)
            r.reportFailure(err)
        }
      }
  }

  private final class ContramapCallback[-A, -B](underlying: Callback[A], f: B => A)
    extends Callback[B] {

    def onSuccess(value: B): Unit =
      try underlying.onSuccess(f(value)) catch {
        case err if NonFatal(err) =>
          underlying.onError(err)
      }

    def onError(ex: Throwable): Unit =
      underlying.onError(ex)
  }

  /** Contravariant type class instance of [[Callback]] for Cats. */
  implicit val contravariantCallback: Contravariant[Callback] = new Contravariant[Callback] {
    override def contramap[A, B](cb: Callback[A])(f: B => A): Callback[B] =
      cb.contramap(f)
  }
}
