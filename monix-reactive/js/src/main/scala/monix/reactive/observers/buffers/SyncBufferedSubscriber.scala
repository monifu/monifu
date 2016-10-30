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

package monix.reactive.observers.buffers

import monix.execution.Ack
import monix.execution.Ack.{Continue, Stop}
import monix.execution.internal.collection.{ArrayQueue, _}
import monix.reactive.exceptions.BufferOverflowException
import monix.reactive.observers.{BufferedSubscriber, Subscriber}
import scala.annotation.tailrec
import scala.util.control.NonFatal

/**
  * A [[BufferedSubscriber]] implementation for the
  * [[monix.reactive.OverflowStrategy.DropNew DropNew]] overflow strategy.
  */
private[buffers] final class SyncBufferedSubscriber[-T] private
  (underlying: Subscriber[T], buffer: EvictingQueue[T], onOverflow: Long => Option[T] = null)
  extends BufferedSubscriber[T] with Subscriber.Sync[T] {

  implicit val scheduler = underlying.scheduler
  // to be modified only in onError, before upstreamIsComplete
  private[this] var errorThrown: Throwable = null
  // to be modified only in onError / onComplete
  private[this] var upstreamIsComplete = false
  // to be modified only by consumer
  private[this] var downstreamIsDone = false
  // represents an indicator that there's a loop in progress
  private[this] var isLoopStarted = false
  // events being dropped
  private[this] var eventsDropped = 0L
  // Used on the consumer side to split big synchronous workloads in batches
  private[this] val em = scheduler.executionModel

  def onNext(elem: T): Ack = {
    if (!upstreamIsComplete && !downstreamIsDone) {
      try {
        eventsDropped += buffer.offer(elem)
        consume()
        Continue
      }
      catch {
        case NonFatal(ex) =>
          onError(ex)
          Stop
      }
    }
    else
      Stop
  }

  def onError(ex: Throwable): Unit = {
    if (!upstreamIsComplete && !downstreamIsDone) {
      errorThrown = ex
      upstreamIsComplete = true
      consume()
    }
  }

  def onComplete(): Unit = {
    if (!upstreamIsComplete && !downstreamIsDone) {
      upstreamIsComplete = true
      consume()
    }
  }

  private[this] def consume() = {
    // no synchronization here, because we are calling
    // this on the producer's side, which is already synchronized
    if (!isLoopStarted) {
      isLoopStarted = true
      scheduler.execute(consumer)
    }
  }

  private[this] val consumer: Runnable = new Runnable {
    def run(): Unit = {
      fastLoop(0)
    }

    @inline
    private[this] def downstreamSignalComplete(ex: Throwable = null): Unit = {
      downstreamIsDone = true
      if (ex != null)
        underlying.onError(ex)
      else
        underlying.onComplete()
    }

    @tailrec
    private[this] def fastLoop(syncIndex: Int): Unit = {
      val nextEvent =
        if (eventsDropped > 0 && onOverflow != null) {
          try {
            onOverflow(eventsDropped) match {
              case Some(message) =>
                eventsDropped = 0
                message.asInstanceOf[AnyRef]
              case None =>
                eventsDropped = 0
                buffer.poll()
            }
          } catch {
            case NonFatal(ex) =>
              errorThrown = ex
              upstreamIsComplete = true
              null
          }
        }
        else {
          buffer.poll()
        }

      if (nextEvent != null) {
        val next = nextEvent.asInstanceOf[T]
        val ack = underlying.onNext(next)

        // for establishing whether the next call is asynchronous,
        // note that the check with batchSizeModulus is meant for splitting
        // big synchronous loops in smaller batches
        val nextIndex = if (!ack.isCompleted) 0 else
          em.nextFrameIndex(syncIndex)

        if (nextIndex > 0) {
          if (ack == Continue || ack.value.get == Continue.AsSuccess)
            fastLoop(nextIndex) // process next
          else {
            // ending loop
            val ex = ack.value.get.failed.getOrElse(new MatchError(ack.value.get))
            downstreamSignalComplete(ex)
          }
        }
        else ack.onComplete {
          case Continue.AsSuccess =>
            // re-run loop (in different thread)
            run()

          case Stop.AsSuccess =>
            // ending loop
            downstreamIsDone = true

          case failure =>
            // ending loop
            val ex = failure.failed.getOrElse(new MatchError(failure))
            downstreamSignalComplete(ex)
        }
      }
      else {
        if (upstreamIsComplete) downstreamSignalComplete(errorThrown)
        // ending loop
        isLoopStarted = false
      }
    }
  }
}

private[monix] object SyncBufferedSubscriber {
  /**
    * Returns an instance of a [[SyncBufferedSubscriber]]
    * for the [[monix.reactive.OverflowStrategy.DropNew DropNew]]
    * overflow strategy.
    */
  def unbounded[T](underlying: Subscriber[T]): Subscriber.Sync[T] = {
    val buffer = ArrayQueue.unbounded[T]
    new SyncBufferedSubscriber[T](underlying, buffer, null)
  }

  /**
    * Returns an instance of a [[SyncBufferedSubscriber]]
    * for the [[monix.reactive.OverflowStrategy.DropNew DropNew]]
    * overflow strategy.
    */
  def bounded[T](underlying: Subscriber[T], bufferSize: Int): Subscriber.Sync[T] = {
    require(bufferSize > 1,
      "bufferSize must be a strictly positive number, bigger than 1")

    val buffer = ArrayQueue.bounded[T](bufferSize, capacity => {
      new BufferOverflowException(
        s"Downstream observer is too slow, buffer over capacity with a " +
          s"specified buffer size of $bufferSize")
    })

    new SyncBufferedSubscriber[T](underlying, buffer, null)
  }

  /**
    * Returns an instance of a [[SyncBufferedSubscriber]]
    * for the [[monix.reactive.OverflowStrategy.DropNew DropNew]]
    * overflow strategy.
    */
  def dropNew[T](underlying: Subscriber[T], bufferSize: Int): Subscriber.Sync[T] = {
    require(bufferSize > 1,
      "bufferSize must be a strictly positive number, bigger than 1")

    val buffer = ArrayQueue.bounded[T](bufferSize)
    new SyncBufferedSubscriber[T](underlying, buffer, null)
  }

  /**
    * Returns an instance of a [[SyncBufferedSubscriber]]
    * for the [[monix.reactive.OverflowStrategy.DropNew DropNew]]
    * overflow strategy.
    */
  def dropNewAndSignal[T](underlying: Subscriber[T], bufferSize: Int, onOverflow: Long => Option[T]): Subscriber.Sync[T] = {
    require(bufferSize > 1,
      "bufferSize must be a strictly positive number, bigger than 1")

    val buffer = ArrayQueue.bounded[T](bufferSize)
    new SyncBufferedSubscriber[T](underlying, buffer, onOverflow)
  }

  /**
    * Returns an instance of a [[SyncBufferedSubscriber]]
    * for the [[monix.reactive.OverflowStrategy.DropOld DropOld]]
    * overflow strategy.
    */
  def dropOld[T](underlying: Subscriber[T], bufferSize: Int): Subscriber.Sync[T] = {
    require(bufferSize > 1,
      "bufferSize must be a strictly positive number, bigger than 1")

    val buffer = DropHeadOnOverflowQueue[AnyRef](bufferSize).asInstanceOf[EvictingQueue[T]]
    new SyncBufferedSubscriber[T](underlying, buffer, null)
  }

  /**
    * Returns an instance of a [[SyncBufferedSubscriber]]
    * for the [[monix.reactive.OverflowStrategy.DropOld DropOld]]
    * overflow strategy, with signaling of the number of events that
    * were dropped.
    */
  def dropOldAndSignal[T](underlying: Subscriber[T], bufferSize: Int, onOverflow: Long => Option[T]): Subscriber.Sync[T] = {
    require(bufferSize > 1,
      "bufferSize must be a strictly positive number, bigger than 1")

    val buffer = DropHeadOnOverflowQueue[AnyRef](bufferSize).asInstanceOf[EvictingQueue[T]]
    new SyncBufferedSubscriber[T](underlying, buffer, onOverflow)
  }

  /**
    * Returns an instance of a [[SyncBufferedSubscriber]] for the
    * [[monix.reactive.OverflowStrategy.ClearBuffer ClearBuffer]]
    * overflow strategy.
    */
  def clearBuffer[T](underlying: Subscriber[T], bufferSize: Int): Subscriber.Sync[T] = {
    require(bufferSize > 1,
      "bufferSize must be a strictly positive number, bigger than 1")

    val buffer = DropAllOnOverflowQueue[AnyRef](bufferSize).asInstanceOf[EvictingQueue[T]]
    new SyncBufferedSubscriber[T](underlying, buffer, null)
  }

  /**
    * Returns an instance of a [[SyncBufferedSubscriber]]
    * for the [[monix.reactive.OverflowStrategy.ClearBuffer ClearBuffer]]
    * overflow strategy, with signaling of the number of events that
    * were dropped.
    */
  def clearBufferAndSignal[T](underlying: Subscriber[T], bufferSize: Int, onOverflow: Long => Option[T]): Subscriber.Sync[T] = {
    require(bufferSize > 1,
      "bufferSize must be a strictly positive number, bigger than 1")

    val buffer = DropAllOnOverflowQueue[AnyRef](bufferSize).asInstanceOf[EvictingQueue[T]]
    new SyncBufferedSubscriber[T](underlying, buffer, onOverflow)
  }
}
