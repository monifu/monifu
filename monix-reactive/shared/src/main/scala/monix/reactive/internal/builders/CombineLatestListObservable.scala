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

import monix.execution.Ack.{Continue, Stop}
import monix.execution.cancelables.CompositeCancelable
import monix.execution.{Ack, Cancelable, Scheduler}
import monix.reactive.Observable
import monix.reactive.observers.Subscriber

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.Success
import scala.util.control.NonFatal

/** Only used in [[Observable.combineLatestList()]]. Is tested by `CombineLatestListSuite`. */
private[reactive] final class CombineLatestListObservable[A, +R](obss: Seq[Observable[A]])(f: Seq[A] => R)
  extends Observable[R] {

  def unsafeSubscribeFn(out: Subscriber[R]): Cancelable = {
    import out.scheduler

    val numberOfObservables = obss.size

    val lock = new AnyRef
    var isDone = false

    // NOTE: We use arrays and other mutable structures here to be as performant as possible.

    // MUST BE synchronized by `lock`
    val observables: Array[Observable[(A, Int)]] = new Array(numberOfObservables)
    observables.indices.foreach { i =>
      observables(i) = obss(i).map(x => (x, i))
    }
    // MUST BE synchronized by `lock`
    var lastAck = Continue: Future[Ack]
    // MUST BE synchronized by `lock`
    val elems: mutable.ArraySeq[A] = new mutable.ArraySeq(numberOfObservables)
    // MUST BE synchronized by `lock`
    val hasElems: Array[Boolean] = new Array(numberOfObservables)
    hasElems.indices.foreach { i =>
      hasElems(i) = false
    }
    // MUST BE synchronized by `lock`
    var completedCount = 0

    // MUST BE synchronized by `lock`
    def rawOnNext(as: Seq[A]): Future[Ack] =
      if (isDone) Stop
      else {
        var streamError = true
        try {
          val c = f(as)
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
    def signalOnNext(as: Seq[A]): Future[Ack] = {
      lastAck = lastAck match {
        case Continue => rawOnNext(as)
        case Stop => Stop
        case async =>
          async.flatMap {
            // async execution, we have to re-sync
            case Continue => lock.synchronized(rawOnNext(as))
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

      if (completedCount == numberOfObservables && !isDone) {
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

    observables.foreach { obs =>
      composite += obs.unsafeSubscribeFn(new Subscriber[(A, Int)] {
        implicit val scheduler: Scheduler = out.scheduler

        def onNext(elemAndIndex: (A, Int)): Future[Ack] = lock.synchronized {
          val elem: A = elemAndIndex._1
          val index: Int = elemAndIndex._2
          if (isDone) {
            Stop
          } else {
            elems(index) = elem
            if (!hasElems(index)) {
              hasElems(index) = true
            }

            if (hasElems.forall(identity)) {
              signalOnNext(Vector(elems: _*))
            } else {
              Continue
            }
          }
        }

        def onError(ex: Throwable): Unit =
          signalOnError(ex)
        def onComplete(): Unit =
          signalOnComplete()
      })
    }
    composite
  }
}
