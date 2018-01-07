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

import monix.eval.Task
import monix.execution.Ack
import monix.execution.misc.NonFatal
import monix.reactive.Observable.Operator
import monix.reactive.observers.Subscriber
import scala.concurrent.Future

private[reactive] final
class EvalOnErrorOperator[A](cb: Throwable => Task[Unit]) extends Operator[A,A] {

  def apply(out: Subscriber[A]): Subscriber[A] =
    new Subscriber[A] {
      implicit val scheduler = out.scheduler

      def onNext(elem: A): Future[Ack] = out.onNext(elem)
      def onComplete(): Unit = out.onComplete()

      def onError(ex: Throwable): Unit = {
        try {
          val task = try cb(ex) catch { case err if NonFatal(err) => Task.raiseError(err) }
          task.attempt.foreach {
            case Right(()) =>
              out.onError(ex)
            case Left(err) =>
              scheduler.reportFailure(err)
              out.onError(ex)
          }
        }
        catch {
          case err if NonFatal(err) =>
            scheduler.reportFailure(err)
        }
      }
    }
}