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

package monix.reactive.internal.builders

import monix.execution.cancelables.CompositeCancelable
import monix.execution.{Cancelable, Ack}
import monix.execution.Ack.{Stop, Continue}
import monix.eval.Coeval
import monix.reactive.Observable
import monix.reactive.observers.Subscriber
import scala.concurrent.{Promise, Future}
import scala.util.control.NonFatal

private[reactive] final
class Pad2Observable[A1,A2,+R]
(obsA1: Observable[A1], obsA2: Observable[A2], paddingA1: Coeval[A1], paddingA2: Coeval[A2])
(f: (A1,A2) ⇒ R)
  extends Observable[R] { self ⇒

  def unsafeSubscribeFn(out: Subscriber[R]): Cancelable = {
    import out.scheduler

    // MUST BE synchronized by `self`
    var isDone = false
    // MUST BE synchronized by `self`
    var lastAck = Continue : Future[Ack]
    // MUST BE synchronized by `self`
    var elemA1 = null.asInstanceOf[A1]
    // MUST BE synchronized by `self`
    var hasElemA1 = false
    // MUST BE synchronized by `self`
    var elemA2 = null.asInstanceOf[A2]
    // MUST BE synchronized by `self`
    var hasElemA2 = false
    // MUST BE synchronized by `self`
    var continueP = Promise[Ack]()
    // MUST BE synchronized by `self`
    var completedCount = 0

    // MUST BE synchronized by `self`
    def rawOnNext(a1: Coeval[A1], a2: Coeval[A2]): Future[Ack] =
      if (isDone) Stop else {
        var streamError = true
        try {
          val c = f(a1.value,a2.value)
          streamError = false
          out.onNext(c)
        } catch {
          case NonFatal(ex) if streamError ⇒
            isDone = true
            out.onError(ex)
            Stop
        } finally {
          hasElemA1 = false
          hasElemA2 = false
        }
      }

    // MUST BE synchronized by `self`
    def signalOnNext(a1: Coeval[A1], a2: Coeval[A2]): Future[Ack] = {
      lastAck = lastAck match {
        case Continue ⇒ rawOnNext(a1,a2)
        case Stop ⇒ Stop
        case async ⇒
          async.flatMap {
            // async execution, we have to re-sync
            case Continue ⇒ self.synchronized(rawOnNext(a1,a2))
            case Stop ⇒ Stop
          }
      }

      continueP.tryCompleteWith(lastAck)
      continueP = Promise[Ack]()
      lastAck
    }

    def signalOnError(ex: Throwable): Unit = self.synchronized {
      if (!isDone) {
        isDone = true
        out.onError(ex)
        lastAck = Stop
      }
    }

    def signalOnComplete(hasElem: Boolean): Unit = {
      @inline def rawOnComplete(): Unit =
        if (!isDone) {
          isDone = true
          out.onComplete()
        }

      self.synchronized  {
        val shouldComplete = !isDone && ({
            completedCount += 1
            completedCount >= 2
          })
        if (shouldComplete)
          lastAck.syncOnContinue(rawOnComplete())
      }
    }

    val composite = CompositeCancelable()

    composite += obsA1.unsafeSubscribeFn(new Subscriber[A1] {
      implicit val scheduler = out.scheduler

      def onNext(elem: A1): Future[Ack] = self.synchronized {
        if (isDone) Stop else {
          elemA1 = elem
          if (!hasElemA1) hasElemA1 = true

          if (hasElemA2)
            signalOnNext(Coeval.pure(elemA1), Coeval.pure(elemA2))
          else if (completedCount == 1)
            signalOnNext(Coeval.pure(elemA1), paddingA2)
          else
            continueP.future
        }
      }

      def onError(ex: Throwable): Unit =
        signalOnError(ex)
      def onComplete(): Unit = self.synchronized {
        signalOnComplete(hasElemA1)
        if (hasElemA2)
          signalOnNext(paddingA1, Coeval.pure(elemA2))
      }
    })

    composite += obsA2.unsafeSubscribeFn(new Subscriber[A2] {
      implicit val scheduler = out.scheduler

      def onNext(elem: A2): Future[Ack] = self.synchronized {
        if (isDone) Stop else {
          elemA2 = elem
          if (!hasElemA2) hasElemA2 = true

          if (hasElemA1)
            signalOnNext(Coeval.pure(elemA1), Coeval.pure(elemA2))
          else if (completedCount == 1)
            signalOnNext(paddingA1, Coeval.pure(elemA2))
          else
            continueP.future
        }
      }

      def onError(ex: Throwable): Unit =
        signalOnError(ex)
      def onComplete(): Unit = self.synchronized {
        signalOnComplete(hasElemA2)
        if (hasElemA1)
          signalOnNext(Coeval.pure(elemA1), paddingA2)
      }
    })

    composite
  }
}
