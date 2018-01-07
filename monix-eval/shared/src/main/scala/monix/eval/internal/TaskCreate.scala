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

import monix.eval.{Callback, Task}
import monix.execution.cancelables.{SingleAssignCancelable, StackedCancelable}
import monix.execution.misc.NonFatal
import monix.execution.{Cancelable, Scheduler}

private[eval] object TaskCreate {
  /**
    * Implementation for `Task.create`
    */
  def apply[A](register: (Scheduler, Callback[A]) => Cancelable): Task[A] = {
    // Wraps a callback into an implementation that pops the stack
    // before calling onSuccess/onError
    final class CreateCallback(conn: StackedCancelable, cb: Callback[A])
      (implicit s: Scheduler)
      extends Callback[A] {

      def onSuccess(value: A): Unit = {
        conn.pop()
        cb.asyncOnSuccess(value)
      }

      def onError(ex: Throwable): Unit = {
        conn.pop()
        cb.asyncOnError(ex)
      }
    }

    Task.unsafeCreate { (context, cb) =>
      val s = context.scheduler
      val conn = context.connection
      val cancelable = SingleAssignCancelable()
      conn push cancelable

      // Forcing a real asynchronous boundary,
      // otherwise stack-overflows can happen
      s.executeTrampolined(() =>
        try {
          val ref = register(s, new CreateCallback(conn, cb)(s))
          // Optimization to skip the assignment, as it's expensive
          if (!ref.isInstanceOf[Cancelable.IsDummy])
            cancelable := ref
        }
        catch {
          case ex if NonFatal(ex) =>
            // We cannot stream the error, because the callback might have
            // been called already and we'd be violating its contract,
            // hence the only thing possible is to log the error.
            s.reportFailure(ex)
        })
    }
  }
}
