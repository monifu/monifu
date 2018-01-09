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
package internal

import monix.eval.Task.{Async, Context}
import monix.execution.cancelables.StackedCancelable
import monix.execution.schedulers.TrampolinedRunnable

private[eval] object TaskCancellation {
  /**
    * Implementation for `Task.cancel`.
    */
  def signal[A](fa: Task[A]): Task[Unit] =
    Task.Async { (ctx, cb) =>
      implicit val sc = ctx.scheduler
      // Continues the execution of `fa` using an already cancelled
      // cancelable, which will ensure that all future registrations
      // will be cancelled immediately and that `isCanceled == false`
      val ctx2 = ctx.copy(connection = StackedCancelable.alreadyCanceled)
      // Light async boundary to avoid stack overflows
      ctx.scheduler.execute(new TrampolinedRunnable {
        def run(): Unit = {
          Task.unsafeStartNow(fa, ctx2, Callback.empty)
          // Signaling that cancellation has been triggered; given
          // the synchronous execution of `fa`, what this means is that
          // cancellation succeeded or an asynchronous boundary has
          // been hit in `fa`
          cb.onSuccess(())
        }
      })
    }

  /**
    * Implementation for `Task.uncancelable`.
    */
  def uncancelable[A](fa: Task[A]): Task[A] =
    Async { (ctx, cb) =>
      implicit val sc = ctx.scheduler
      val ctx2 = Context(sc, ctx.options)
      Task.unsafeStartTrampolined(fa, ctx2, Callback.async(cb))
    }
}
