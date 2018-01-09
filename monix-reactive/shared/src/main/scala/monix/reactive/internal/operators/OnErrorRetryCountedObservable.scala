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

package monix.reactive.internal.operators

import monix.execution.Ack.Continue
import monix.execution.cancelables.OrderedCancelable
import monix.execution.{Scheduler, Ack, Cancelable}
import monix.reactive.Observable
import monix.reactive.observers.Subscriber
import scala.concurrent.Future
import scala.util.Success

private[reactive] final
class OnErrorRetryCountedObservable[+A](source: Observable[A], maxRetries: Long)
  extends Observable[A] {

  private def loop(subscriber: Subscriber[A], task: OrderedCancelable, retryIdx: Long): Unit = {
    val cancelable = source.unsafeSubscribeFn(new Subscriber[A] {
      implicit val scheduler: Scheduler = subscriber.scheduler
      private[this] var isDone = false
      private[this] var ack: Future[Ack] = Continue

      def onNext(elem: A) = {
        ack = subscriber.onNext(elem)
        ack
      }

      def onComplete(): Unit =
        if (!isDone) {
          isDone = true
          subscriber.onComplete()
        }

      def onError(ex: Throwable): Unit =
        if (!isDone) {
          isDone = true

          if (maxRetries < 0 || retryIdx < maxRetries) {
            // need asynchronous execution to avoid a synchronous loop
            // blowing out the call stack
            ack.onComplete {
              case Success(Continue) =>
                loop(subscriber, task, retryIdx+1)
              case _ =>
                () // stop
            }
          }
          else {
            subscriber.onError(ex)
          }
        }
    })

    // We need to do an `orderedUpdate`, because `onError` might have
    // already and resubscribed by now.
    task.orderedUpdate(cancelable, retryIdx)
  }

  def unsafeSubscribeFn(subscriber: Subscriber[A]): Cancelable = {
    val task = OrderedCancelable()
    loop(subscriber, task, retryIdx = 0)
    task
  }
}
