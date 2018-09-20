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

package monix.eval.internal

import cats.effect.{CancelToken, IO}
import monix.eval.Task.Async
import monix.eval.{Callback, Coeval, Task}
import monix.execution.cancelables.SingleAssignCancelable
import scala.util.control.NonFatal
import monix.execution.{Cancelable, Scheduler}

private[eval] object TaskCreate {
  /**
    * Implementation for `Task.cancelable`
    */
  def cancelable0[A](fn: (Scheduler, Callback[A]) => Cancelable): Task[A] = {
    val start = (ctx: TaskContext, cb: Callback[A]) => {
      implicit val s = ctx.scheduler
      val conn = ctx.connection
      val cancelable = SingleAssignCancelable()
      conn push cancelable

      try {
        val ref = fn(s, Callback.trampolined(conn, cb))
        // Optimization to skip the assignment, as it's expensive
        if (!ref.isInstanceOf[Cancelable.IsDummy])
          cancelable := ref
      } catch {
        case ex if NonFatal(ex) =>
          // We cannot stream the error, because the callback might have
          // been called already and we'd be violating its contract,
          // hence the only thing possible is to log the error.
          s.reportFailure(ex)
      }
    } : Unit

    Async(start, trampolineBefore = false, trampolineAfter = false)
  }

  /** Implementation for `cats.effect.Concurrent#cancelable`. */
  def cancelableEffect[A](k: (Either[Throwable, A] => Unit) => CancelToken[Task]): Task[A] =
    cancelable0 { (sc, cb) =>
      val task = k(cb)
      Cancelable(() => task.runAsync(Callback.empty(sc))(sc))
    }

  /**
    * Implementation for `Task.create`, used via `TaskBuilder`.
    */
  def cancelableIO[A](start: (Scheduler, Callback[A]) => CancelToken[IO]): Task[A] =
    cancelable0 { (sc, cb) => Cancelable.fromIO(start(sc, cb))(sc) }

  /**
    * Implementation for `Task.create`, used via `TaskBuilder`.
    */
  def cancelableTask[A](start: (Scheduler, Callback[A]) => Task[Unit]): Task[A] =
    cancelable0 { (sc, cb) =>
      val task = start(sc, cb)
      Cancelable(() => task.runAsync(Callback.empty(sc))(sc))
    }

  /**
    * Implementation for `Task.create`, used via `TaskBuilder`.
    */
  def cancelableCoeval[A](start: (Scheduler, Callback[A]) => Coeval[Unit]): Task[A] =
    cancelable0 { (sc, cb) =>
      val task = start(sc, cb)
      Cancelable(() => task.value())
    }

  /**
    * Implementation for `Task.async`
    */
  def async0[A](fn: (Scheduler, Callback[A]) => Any): Task[A] = {
    val start = (ctx: TaskContext, cb: Callback[A]) => {
      implicit val s = ctx.scheduler
      try {
        fn(s, Callback.trampolined(cb))
        ()
      } catch {
        case ex if NonFatal(ex) =>
          // We cannot stream the error, because the callback might have
          // been called already and we'd be violating its contract,
          // hence the only thing possible is to log the error.
          s.reportFailure(ex)
      }
    }
    Async(start, trampolineBefore = false, trampolineAfter = false)
  }

  /**
    * Implementation for `cats.effect.Async#async`.
    *
    * It duplicates the implementation of `Task.simple` with the purpose
    * of avoiding extraneous callback allocations.
    */
  def async[A](k: Callback[A] => Unit): Task[A] = {
    val start = (ctx: TaskContext, cb: Callback[A]) => {
      implicit val s = ctx.scheduler
      try {
        k(Callback.trampolined(cb))
      } catch {
        case ex if NonFatal(ex) =>
          // We cannot stream the error, because the callback might have
          // been called already and we'd be violating its contract,
          // hence the only thing possible is to log the error.
          s.reportFailure(ex)
      }
    }
    Async(start, trampolineBefore = false, trampolineAfter = false)
  }

  /**
    * Implementation for `Task.asyncF`.
    */
  def asyncF[A](k: Callback[A] => Task[Unit]): Task[A] = {
    val start = (ctx: TaskContext, cb: Callback[A]) => {
      implicit val s = ctx.scheduler
      try {
        // Creating new connection, because we can have a race condition
        // between the bind continuation and executing the generated task
        val ctx2 = TaskContext(ctx.scheduler, ctx.options)
        val conn = ctx.connection
        conn.push(ctx2.connection)
        // Provided callback takes care of `conn.pop()`
        val task = k(Callback.trampolined(conn, cb))
        Task.unsafeStartNow(task, ctx2, Callback.empty)
      } catch {
        case ex if NonFatal(ex) =>
          // We cannot stream the error, because the callback might have
          // been called already and we'd be violating its contract,
          // hence the only thing possible is to log the error.
          s.reportFailure(ex)
      }
    }
    Async(start, trampolineBefore = false, trampolineAfter = false)
  }
}
