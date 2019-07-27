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

package monix.reactive.internal.builders

import monix.execution.{Ack, Cancelable}
import monix.execution.Ack.{Continue, Stop}
import monix.execution.cancelables.CompositeCancelable
import scala.util.control.NonFatal
import monix.reactive.Observable
import monix.reactive.observers.Subscriber

import scala.concurrent.Future
import scala.util.Success

private[reactive] final class CombineLatest5Observable[A1, A2, A3, A4, A5, +R](
  obsA1: Observable[A1],
  obsA2: Observable[A2],
  obsA3: Observable[A3],
  obsA4: Observable[A4],
  obsA5: Observable[A5])(f: (A1, A2, A3, A4, A5) => R)
  extends Observable[R] {

  def unsafeSubscribeFn(out: Subscriber[R]): Cancelable = {
    import out.scheduler

    val lock = new AnyRef
    var isDone = false
    // MUST BE synchronized by `lock`
    var lastAck = Continue: Future[Ack]
    // MUST BE synchronized by `lock`
    var elemA1: A1 = null.asInstanceOf[A1]
    // MUST BE synchronized by `lock`
    var hasElemA1 = false
    // MUST BE synchronized by `lock`
    var elemA2: A2 = null.asInstanceOf[A2]
    // MUST BE synchronized by `lock`
    var hasElemA2 = false
    // MUST BE synchronized by `lock`
    var elemA3: A3 = null.asInstanceOf[A3]
    // MUST BE synchronized by `lock`
    var hasElemA3 = false
    // MUST BE synchronized by `lock`
    var elemA4: A4 = null.asInstanceOf[A4]
    // MUST BE synchronized by `lock`
    var hasElemA4 = false
    // MUST BE synchronized by `lock`
    var elemA5: A5 = null.asInstanceOf[A5]
    // MUST BE synchronized by `lock`
    var hasElemA5 = false
    // MUST BE synchronized by `lock`
    var completedCount = 0

    // MUST BE synchronized by `lock`
    def rawOnNext(a1: A1, a2: A2, a3: A3, a4: A4, a5: A5): Future[Ack] =
      if (isDone) Stop
      else {
        var streamError = true
        try {
          val c = f(a1, a2, a3, a4, a5)
          streamError = false
          out.onNext(c)
        } catch {
          case NonFatal(ex) if streamError =>
            isDone = true
            out.onError(ex)
            Stop
        }
      }

    // MUST BE synchronized by `lock`
    def signalOnNext(a1: A1, a2: A2, a3: A3, a4: A4, a5: A5): Future[Ack] = {
      lastAck = lastAck match {
        case Continue => rawOnNext(a1, a2, a3, a4, a5)
        case Stop => Stop
        case async =>
          async.flatMap {
            // async execution, we have to re-sync
            case Continue => lock.synchronized(rawOnNext(a1, a2, a3, a4, a5))
            case Stop => Stop
          }
      }

      lastAck
    }

    def signalOnError(ex: Throwable): Unit = lock.synchronized {
      if (!isDone) {
        isDone = true
        out.onError(ex)
        lastAck = Stop
      }
    }

    def signalOnComplete(): Unit = lock.synchronized {
      completedCount += 1

      if (completedCount == 5 && !isDone) {
        lastAck match {
          case Continue =>
            isDone = true
            out.onComplete()
          case Stop =>
            () // do nothing
          case async =>
            async.onComplete {
              case Success(Continue) =>
                lock.synchronized {
                  if (!isDone) {
                    isDone = true
                    out.onComplete()
                  }
                }
              case _ =>
                () // do nothing
            }
        }

        lastAck = Stop
      }
    }

    val composite = CompositeCancelable()

    composite += obsA1.unsafeSubscribeFn(new Subscriber[A1] {
      implicit val scheduler = out.scheduler

      def onNext(elem: A1): Future[Ack] = lock.synchronized {
        if (isDone) Stop
        else {
          elemA1 = elem
          if (!hasElemA1) hasElemA1 = true

          if (hasElemA2 && hasElemA3 && hasElemA4 && hasElemA5)
            signalOnNext(elemA1, elemA2, elemA3, elemA4, elemA5)
          else
            Continue
        }
      }

      def onError(ex: Throwable): Unit =
        signalOnError(ex)
      def onComplete(): Unit =
        signalOnComplete()
    })

    composite += obsA2.unsafeSubscribeFn(new Subscriber[A2] {
      implicit val scheduler = out.scheduler

      def onNext(elem: A2): Future[Ack] = lock.synchronized {
        if (isDone) Stop
        else {
          elemA2 = elem
          if (!hasElemA2) hasElemA2 = true

          if (hasElemA1 && hasElemA3 && hasElemA4 && hasElemA5)
            signalOnNext(elemA1, elemA2, elemA3, elemA4, elemA5)
          else
            Continue
        }
      }

      def onError(ex: Throwable): Unit =
        signalOnError(ex)
      def onComplete(): Unit =
        signalOnComplete()
    })

    composite += obsA3.unsafeSubscribeFn(new Subscriber[A3] {
      implicit val scheduler = out.scheduler

      def onNext(elem: A3): Future[Ack] = lock.synchronized {
        if (isDone) Stop
        else {
          elemA3 = elem
          if (!hasElemA3) hasElemA3 = true

          if (hasElemA1 && hasElemA2 && hasElemA4 && hasElemA5)
            signalOnNext(elemA1, elemA2, elemA3, elemA4, elemA5)
          else
            Continue
        }
      }

      def onError(ex: Throwable): Unit =
        signalOnError(ex)
      def onComplete(): Unit =
        signalOnComplete()
    })

    composite += obsA4.unsafeSubscribeFn(new Subscriber[A4] {
      implicit val scheduler = out.scheduler

      def onNext(elem: A4): Future[Ack] = lock.synchronized {
        if (isDone) Stop
        else {
          elemA4 = elem
          if (!hasElemA4) hasElemA4 = true

          if (hasElemA1 && hasElemA2 && hasElemA3 && hasElemA5)
            signalOnNext(elemA1, elemA2, elemA3, elemA4, elemA5)
          else
            Continue
        }
      }

      def onError(ex: Throwable): Unit =
        signalOnError(ex)
      def onComplete(): Unit =
        signalOnComplete()
    })

    composite += obsA5.unsafeSubscribeFn(new Subscriber[A5] {
      implicit val scheduler = out.scheduler

      def onNext(elem: A5): Future[Ack] = lock.synchronized {
        if (isDone) Stop
        else {
          elemA5 = elem
          if (!hasElemA5) hasElemA5 = true

          if (hasElemA1 && hasElemA2 && hasElemA3 && hasElemA4)
            signalOnNext(elemA1, elemA2, elemA3, elemA4, elemA5)
          else
            Continue
        }
      }

      def onError(ex: Throwable): Unit =
        signalOnError(ex)
      def onComplete(): Unit =
        signalOnComplete()
    })

    composite
  }
}
