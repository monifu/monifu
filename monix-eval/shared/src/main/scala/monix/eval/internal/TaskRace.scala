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
import monix.execution.atomic.Atomic
import monix.execution.cancelables._

private[eval] object TaskRace {
  /**
    * Implementation for `Task.race`.
    */
  def apply[A,B](fa: Task[A], fb: Task[B]): Task[Either[A, B]] =
    Task.unsafeCreate { (context, cb) =>
      implicit val sc = context.scheduler
      val conn = context.connection

      val isActive = Atomic(true)
      val connA = StackedCancelable()
      val connB = StackedCancelable()
      conn push CompositeCancelable(connA, connB)

      val contextA = context.copy(connection = connA)
      val contextB = context.copy(connection = connB)

      // First task: A
      Task.unsafeStartAsync(fa, contextA, new Callback[A] {
        def onSuccess(valueA: A): Unit =
          if (isActive.getAndSet(false)) {
            connB.cancel()
            conn.pop()
            cb.asyncOnSuccess(Left(valueA))
          }

        def onError(ex: Throwable): Unit =
          if (isActive.getAndSet(false)) {
            conn.pop()
            connB.cancel()
            cb.asyncOnError(ex)
          } else {
            sc.reportFailure(ex)
          }
      })

      // Second task: B
      Task.unsafeStartAsync(fb, contextB, new Callback[B] {
        def onSuccess(valueB: B): Unit =
          if (isActive.getAndSet(false)) {
            connA.cancel()
            conn.pop()
            cb.asyncOnSuccess(Right(valueB))
          }

        def onError(ex: Throwable): Unit =
          if (isActive.getAndSet(false)) {
            conn.pop()
            connA.cancel()
            cb.asyncOnError(ex)
          } else {
            sc.reportFailure(ex)
          }
      })
    }
}
