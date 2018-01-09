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

package monix.reactive.observers.buffers

import monix.execution.Ack
import monix.execution.Ack.{Continue, Stop}
import monix.execution.atomic.Atomic
import monix.execution.atomic.PaddingStrategy.LeftRight256
import monix.execution.internal.math
import monix.execution.misc.NonFatal
import monix.reactive.observers.{BufferedSubscriber, Subscriber}

import scala.annotation.tailrec
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

/** Shared internals between [[BackPressuredBufferedSubscriber]] and
  * [[BatchedBufferedSubscriber]].
  */
private[observers] abstract class AbstractBackPressuredBufferedSubscriber[A,R]
  (out: Subscriber[R], _bufferSize: Int)
  extends CommonBufferMembers with BufferedSubscriber[A] {

  require(_bufferSize > 0, "bufferSize must be a strictly positive number")

  private[this] val bufferSize = math.nextPowerOf2(_bufferSize)
  private[this] val em = out.scheduler.executionModel
  implicit final val scheduler = out.scheduler

  protected final val queue: ConcurrentQueue[A] =
    ConcurrentQueue.unbounded()

  private[this] val itemsToPush =
    Atomic.withPadding(0, LeftRight256)
  private[this] val backPressured =
    Atomic.withPadding(null : Promise[Ack], LeftRight256)

  @tailrec
  private final def pushOnNext(elem: A, lastToPush: Option[Int]): Future[Ack] = {
    if (upstreamIsComplete || downstreamIsComplete)
      Stop
    else if (elem == null) {
      onError(new NullPointerException("Null not supported in onNext"))
      Stop
    }
    else {
      val toPush = lastToPush match {
        case None => itemsToPush.getAndIncrement()
        case Some(v) => v
      }

      backPressured.get match {
        case null =>
          if (toPush < bufferSize) {
            queue.offer(elem)
            pushToConsumer(toPush)
            Continue
          }
          else {
            val promise = Promise[Ack]()

            if (!backPressured.compareAndSet(null, promise))
              pushOnNext(elem, Some(toPush))
            else {
              queue.offer(elem)
              pushToConsumer(toPush)
              promise.future
            }
          }
        case promise =>
          queue.offer(elem)
          pushToConsumer(toPush)
          promise.future
      }
    }
  }

  final def onNext(elem: A): Future[Ack] =
    pushOnNext(elem, None)

  private def pushComplete(ex: Option[Throwable], toPush: Option[Int]): Unit = {
    if (!upstreamIsComplete && !downstreamIsComplete) {
      errorThrown = ex.orNull
      upstreamIsComplete = true
      val nr = toPush.getOrElse(itemsToPush.getAndIncrement())
      pushToConsumer(nr)
    }
  }

  final def onError(ex: Throwable): Unit =
    pushComplete(Some(ex), None)

  final def onComplete(): Unit =
    pushComplete(None, None)

  private final def pushToConsumer(currentNr: Int): Unit = {
    // If a run-loop isn't started, then go, go, go!
    if (currentNr == 0) {
      // Starting the run-loop, as at this point we can be sure
      // that no other loop is active
      scheduler.execute(consumerRunLoop)
    }
  }

  protected def fetchNext(): R
  protected def fetchSize(r: R): Int

  private[this] val consumerRunLoop = new Runnable {
    def run(): Unit = {
      fastLoop(lastIterationAck, 0, 0)
    }

    private final def signalNext(next: R): Future[Ack] =
      try {
        val ack = out.onNext(next)
        // Tries flattening the Future[Ack] to a
        // synchronous value
        if (ack == Continue || ack == Stop)
          ack
        else ack.value match {
          case Some(Success(success)) =>
            success
          case Some(Failure(ex)) =>
            signalError(ex)
            Stop
          case None =>
            ack
        }
      } catch {
        case ex if NonFatal(ex) =>
          signalError(ex)
          Stop
      }

    private final def signalComplete(): Unit =
      try out.onComplete() catch {
        case ex if NonFatal(ex) =>
          scheduler.reportFailure(ex)
      }

    private final def signalError(ex: Throwable): Unit =
      try out.onError(ex) catch {
        case err if NonFatal(err) =>
          scheduler.reportFailure(err)
      }

    private final def goAsync(next: R, nextSize: Int, ack: Future[Ack], processed: Int): Unit =
      ack.onComplete {
        case Success(Continue) =>
          val nextAck = signalNext(next)
          val isSync = ack == Continue || ack == Stop
          val nextFrame = if (isSync) em.nextFrameIndex(0) else 0
          fastLoop(nextAck, processed + nextSize, nextFrame)

        case Success(Stop) =>
          // ending loop
          downstreamIsComplete = true

        case Failure(ex) =>
          // ending loop
          downstreamIsComplete = true
          signalError(ex)
      }

    private final def fastLoop(prevAck: Future[Ack], lastProcessed: Int, startIndex: Int): Unit = {
      def stopStreaming(): Unit = {
        downstreamIsComplete = true
        val bp = backPressured.get
        if (bp != null) bp.success(Stop)
      }

      var ack = if (prevAck == null) Continue else prevAck
      var isFirstIteration = ack == Continue
      var processed = lastProcessed
      var nextIndex = startIndex

      while (!downstreamIsComplete) {
        val next = fetchNext()

        if (next != null) {
          val nextSize = fetchSize(next)

          if (nextIndex > 0 || isFirstIteration) {
            isFirstIteration = false

            ack match {
              case Continue =>
                ack = signalNext(next)
                if (ack == Stop) {
                  stopStreaming()
                  return
                } else {
                  val isSync = ack == Continue
                  nextIndex = if (isSync) em.nextFrameIndex(nextIndex) else 0
                  processed += nextSize
                }

              case Stop =>
                stopStreaming()
                return

              case _ =>
                goAsync(next, nextSize, ack, processed)
                return
            }
          }
          else {
            goAsync(next, nextSize, ack, processed)
            return
          }
        }
        else if (upstreamIsComplete) {
          // Race-condition check, but if upstreamIsComplete=true is
          // visible, then the queue should be fully published because
          // there's a clear happens-before relationship between
          // queue.offer() and upstreamIsComplete=true
          if (queue.isEmpty) {
            // ending loop
            stopStreaming()
            itemsToPush.decrement(processed + 1)

            if (errorThrown ne null) signalError(errorThrown)
            else signalComplete()
            return
          }
        }
        else {
          // Given we are writing in `itemsToPush` before this
          // assignment, it means that writes will not get reordered,
          // so when we observe that itemsToPush is zero on the
          // producer side, we will also have the latest lastIterationAck
          lastIterationAck = ack
          val remaining = itemsToPush.decrementAndGet(processed)
          processed = 0

          // if the queue is non-empty (i.e. concurrent modifications
          // just happened) then continue loop, otherwise stop
          if (remaining == 0) {
            val bp = backPressured.getAndSet(null)
            if (bp != null) bp.success(Continue)
            return
          }
        }
      }
    }
  }
}