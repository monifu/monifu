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

package monix.reactive.internal.operators

import monix.execution.Ack
import monix.execution.Ack.{Continue, Stop}
import monix.reactive.observables.ObservableLike.Operator
import monix.reactive.observers.Subscriber
import scala.util.control.NonFatal

private[reactive] final
class FoldLeftOperator[A,R](initial: R, f: (R,A) => R)
  extends Operator[A,R] {

  def apply(out: Subscriber[R]): Subscriber.Sync[A] =
    new Subscriber.Sync[A] {
      implicit val scheduler = out.scheduler
      private[this] var isDone = false
      private[this] var state = initial

      def onNext(elem: A): Ack = {
        // Protects calls to user code from within the operator,
        // as a matter of contract.
        try {
          state = f(state, elem)
          Continue
        } catch {
          case NonFatal(ex) =>
            onError(ex)
            Stop
        }
      }

      def onComplete(): Unit =
        if (!isDone) {
          isDone = true
          out.onNext(state)
          out.onComplete()
        }

      def onError(ex: Throwable): Unit =
        if (!isDone) {
          isDone = true
          out.onError(ex)
        }
    }
}