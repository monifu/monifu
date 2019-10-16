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

package monix.eval.internal

import cats.effect.ExitCase
import cats.effect.ExitCase.{Canceled, Completed, Error}
import monix.eval.Task.{Context, ContextSwitch}
import monix.execution.Callback
import monix.eval.Task
import monix.execution.atomic.Atomic
import monix.execution.internal.Platform
import scala.util.control.NonFatal

private[monix] object TaskBracket {

  // -----------------------------------------------------------------
  // Task.guaranteeCase
  // =-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=

  def guaranteeCase[A](task: Task[A], finalizer: ExitCase[Throwable] => Task[Unit]): Task[A] =
    Task.Async(
      new ReleaseStart(task, finalizer),
      trampolineBefore = true,
      trampolineAfter = true,
      restoreLocals = true
    )

  private final class ReleaseStart[A](source: Task[A], release: ExitCase[Throwable] => Task[Unit])
    extends ((Context, Callback[Throwable, A]) => Unit) {

    def apply(ctx: Context, cb: Callback[Throwable, A]): Unit = {
      implicit val s = ctx.scheduler

      val conn = ctx.connection
      val frame = new EnsureReleaseFrame[A](ctx, release)
      val onNext = source.flatMap(frame)

      // Registering our cancelable token ensures that in case
      // cancellation is detected, `release` gets called
      conn.push(frame.cancel)

      // Race condition check, avoiding starting `source` in case
      // the connection was already cancelled — n.b. we don't need
      // to trigger `release` otherwise, because it already happened
      if (!conn.isCanceled) {
        Task.unsafeStartNow(onNext, ctx, cb)
      }
    }
  }

  private final class EnsureReleaseFrame[A](ctx: Context, releaseFn: ExitCase[Throwable] => Task[Unit])
    extends BaseReleaseFrame[Unit, A](ctx, ()) {

    def releaseOnSuccess(a: Unit, b: A): Task[Unit] =
      releaseFn(ExitCase.Completed)

    def releaseOnError(a: Unit, e: Throwable): Task[Unit] =
      releaseFn(ExitCase.Error(e))

    def releaseOnCancel(a: Unit): Task[Unit] =
      releaseFn(ExitCase.Canceled)
  }

  // -----------------------------------------------------------------
  // Task.bracketE
  // =-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=

  /**
    * [[monix.eval.Task.bracket]] and [[monix.eval.Task.bracketCase]]
    */
  def either[A, B](
    acquire: Task[A],
    use: A => Task[B],
    release: (A, Either[Option[Throwable], B]) => Task[Unit]): Task[B] = {

    Task.Async(
      new StartE(acquire, use, release),
      trampolineBefore = true,
      trampolineAfter = true,
      restoreLocals = true
    )
  }

  private final class StartE[A, B](
    acquire: Task[A],
    use: A => Task[B],
    release: (A, Either[Option[Throwable], B]) => Task[Unit])
    extends BaseStart(acquire, use) {

    def makeReleaseFrame(ctx: Context, value: A) =
      new ReleaseFrameE(ctx, value, release)
  }

  private final class ReleaseFrameE[A, B](ctx: Context, a: A, release: (A, Either[Option[Throwable], B]) => Task[Unit])
    extends BaseReleaseFrame[A, B](ctx, a) {

    def releaseOnSuccess(a: A, b: B): Task[Unit] =
      release(a, Right(b))

    def releaseOnError(a: A, e: Throwable): Task[Unit] =
      release(a, Left(Some(e)))

    def releaseOnCancel(a: A): Task[Unit] =
      release(a, leftNone)
  }

  // -----------------------------------------------------------------
  // Task.bracketCase
  // =-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=

  /**
    * [[monix.eval.Task.bracketE]]
    */
  def exitCase[A, B](acquire: Task[A], use: A => Task[B], release: (A, ExitCase[Throwable]) => Task[Unit]): Task[B] =
    Task.Async(
      new StartCase(acquire, use, release),
      trampolineBefore = true,
      trampolineAfter = true,
      restoreLocals = true
    )

  private final class StartCase[A, B](
    acquire: Task[A],
    use: A => Task[B],
    release: (A, ExitCase[Throwable]) => Task[Unit])
    extends BaseStart(acquire, use) {

    def makeReleaseFrame(ctx: Context, value: A) =
      new ReleaseFrameCase(ctx, value, release)
  }

  private final class ReleaseFrameCase[A, B](ctx: Context, a: A, release: (A, ExitCase[Throwable]) => Task[Unit])
    extends BaseReleaseFrame[A, B](ctx, a) {

    def releaseOnSuccess(a: A, b: B): Task[Unit] =
      release(a, Completed)

    def releaseOnError(a: A, e: Throwable): Task[Unit] =
      release(a, Error(e))

    def releaseOnCancel(a: A): Task[Unit] =
      release(a, Canceled)
  }

  // -----------------------------------------------------------------
  // Base Implementation
  // =-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=

  private abstract class BaseStart[A, B](acquire: Task[A], use: A => Task[B])
    extends ((Context, Callback[Throwable, B]) => Unit) {

    protected def makeReleaseFrame(ctx: Context, value: A): BaseReleaseFrame[A, B]

    final def apply(ctx: Context, cb: Callback[Throwable, B]): Unit = {
      // Async boundary needed, but it is guaranteed via Task.Async below;
      Task.unsafeStartNow(
        acquire,
        ctx.withConnection(TaskConnection.uncancelable),
        new Callback[Throwable, A] {
          def onSuccess(value: A): Unit = {
            implicit val sc = ctx.scheduler
            val conn = ctx.connection

            val releaseFrame = makeReleaseFrame(ctx, value)
            val onNext = {
              val fb = try use(value)
              catch { case NonFatal(e) => Task.raiseError(e) }
              fb.flatMap(releaseFrame)
            }

            conn.push(releaseFrame.cancel)
            Task.unsafeStartNow(onNext, ctx, cb)
          }

          def onError(ex: Throwable): Unit =
            cb.onError(ex)
        }
      )
    }
  }

  private abstract class BaseReleaseFrame[A, B](ctx: Context, a: A) extends StackFrame[B, Task[B]] {
    private[this] val waitsForResult = Atomic(true)
    protected def releaseOnSuccess(a: A, b: B): Task[Unit]
    protected def releaseOnError(a: A, e: Throwable): Task[Unit]
    protected def releaseOnCancel(a: A): Task[Unit]

    final def apply(b: B): Task[B] = {
      // In case auto-cancelable run-loops are enabled, then the function
      // call needs to be suspended, because we might evaluate the effect before
      // the connection is actually made uncancelable
      val task =
        if (ctx.options.autoCancelableRunLoops)
          Task.suspend(unsafeApply(b))
        else
          unsafeApply(b)

      makeUncancelable(task)
    }

    final def recover(e: Throwable): Task[B] = {
      // In case auto-cancelable run-loops are enabled, then the function
      // call needs to be suspended, because we might evaluate the effect before
      // the connection is actually made uncancelable
      val task =
        if (ctx.options.autoCancelableRunLoops)
          Task.suspend(unsafeRecover(e))
        else
          unsafeRecover(e)

      makeUncancelable(task)
    }

    final def cancel: Task[Unit] =
      Task.suspend {
        if (waitsForResult.compareAndSet(expect = true, update = false))
          releaseOnCancel(a)
        else
          Task.unit
      }

    private final def unsafeApply(b: B): Task[B] = {
      if (waitsForResult.compareAndSet(expect = true, update = false)) {
        ctx.connection.pop()
        releaseOnSuccess(a, b).map(_ => b)
      } else {
        Task.never
      }
    }

    private final def unsafeRecover(e: Throwable): Task[B] = {
      if (waitsForResult.compareAndSet(expect = true, update = false)) {
        ctx.connection.pop()
        releaseOnError(a, e).flatMap(new ReleaseRecover(e))
      } else {
        Task.never
      }
    }

    private final def makeUncancelable(task: Task[B]): Task[B] = {
      // NOTE: the "restore" part of this is `null` because we don't need to restore
      // the original connection. The original connection gets restored automatically
      // via how "TaskRestartCallback" works. This is risky!
      ContextSwitch(task, withConnectionUncancelable, null)
    }
  }

  private final class ReleaseRecover(e: Throwable) extends StackFrame[Unit, Task[Nothing]] {
    def apply(a: Unit): Task[Nothing] =
      Task.raiseError(e)

    def recover(e2: Throwable): Task[Nothing] =
      Task.raiseError(Platform.composeErrors(e, e2))
  }

  private val leftNone = Left(None)

  private[this] val withConnectionUncancelable: Context => Context =
    _.withConnection(TaskConnection.uncancelable)
}
