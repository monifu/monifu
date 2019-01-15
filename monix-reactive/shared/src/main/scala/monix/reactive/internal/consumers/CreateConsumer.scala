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

package monix.reactive.internal.consumers

import monix.execution.Callback
import monix.execution.{Cancelable, Scheduler}
import monix.execution.cancelables.{AssignableCancelable, SingleAssignCancelable}
import monix.reactive.{Consumer, Observer}
import monix.reactive.observers.Subscriber
import scala.util.{Failure, Success, Try}

/** Implementation for [[monix.reactive.Consumer.create]]. */
private[reactive]
final class CreateConsumer[-In,+Out]
  (f: (Scheduler, Cancelable, Callback[Throwable, Out]) => Observer[In])
  extends Consumer[In,Out] {

  def createSubscriber(cb: Callback[Throwable, Out], s: Scheduler): (Subscriber[In], AssignableCancelable) = {
    val conn = SingleAssignCancelable()

    Try(f(s, conn, cb)) match {
      case Failure(ex) =>
        Consumer.raiseError(ex).createSubscriber(cb,s)

      case Success(out) =>
        val sub = Subscriber(out, s)
        (sub, conn)
    }
  }
}
