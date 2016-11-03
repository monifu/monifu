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

package monix.reactive.internal.rstreams

import monix.execution.Ack.{Continue, Stop}
import monix.reactive.OverflowStrategy.Unbounded
import monix.reactive.observers.{BufferedSubscriber, Subscriber}
import org.reactivestreams.{Subscriber => RSubscriber, Subscription => RSubscription}

/** Wraps a [[monix.reactive.Observer Observer]] instance into an
  * `org.reactiveSubscriber` instance. The resulting
  * subscriber respects the [[http://www.reactive-streams.org/ Reactive Streams]]
  * contract.
  *
  * Given that when emitting [[monix.reactive.Observer.onNext Observer.onNext]] calls,
  * the call may pass asynchronous boundaries, the emitted events need to be buffered.
  * The `requestCount` constructor parameter also represents the buffer size.
  *
  * To async an instance, [[SubscriberAsReactiveSubscriber]] must be used: {{{
  *   // uses the default requestCount of 128
  *   val subscriber = SubscriberAsReactiveSubscriber(new Observer[Int] {
  *     private[this] var sum = 0
  *
  *     def onNext(elem: Int) = {
  *       sum += elem
  *       Continue
  *     }
  *
  *     def onError(ex: Throwable) = {
  *       logger.error(ex)
  *     }
  *
  *     def onComplete() = {
  *       logger.info("Stream completed")
  *     }
  *   })
  * }}}
  *
  * @param subscriber the observer instance that will get wrapped into a
  *                   `org.reactiveSubscriber`, along with the scheduler used
  * @param requestCount the parameter passed to `Subscription.request`,
  *                    also representing the buffer size; MUST BE strictly positive
  */
private[monix] final class SubscriberAsReactiveSubscriber[T] private
  (subscriber: Subscriber[T], requestCount: Int)
  extends RSubscriber[T] {

  require(requestCount > 0, "requestCount must be strictly positive, according to the Reactive Streams contract")

  private[this] val buffer: RSubscriber[T] =
    SyncSubscriberAsReactiveSubscriber(
      BufferedSubscriber.synchronous(subscriber, Unbounded),
      requestCount = requestCount
    )

  def onSubscribe(s: RSubscription): Unit =
    buffer.onSubscribe(s)

  def onNext(elem: T): Unit = {
    if (elem == null) throw new NullPointerException("onNext(null)")
    buffer.onNext(elem)
  }

  def onError(ex: Throwable): Unit =
    buffer.onError(ex)

  def onComplete(): Unit =
    buffer.onComplete()
}


private[monix] object SubscriberAsReactiveSubscriber {
  /**
    * Wraps a [[monix.reactive.Observer Observer]] instance into a
    * `org.reactiveSubscriber` instance. The resulting
    * subscriber respects the [[http://www.reactive-streams.org/ Reactive Streams]]
    * contract.
    *
    * Given that when emitting [[monix.reactive.Observer.onNext Observer.onNext]] calls,
    * the call may pass asynchronous boundaries, the emitted events need to be buffered.
    * The `requestCount` constructor parameter also represents the buffer size.
    *
    * To async an instance, [[SubscriberAsReactiveSubscriber.apply]] must be used: {{{
    *   // uses the default requestCount of 128
    *   val subscriber = SubscriberAsReactiveSubscriber(new Observer[Int] {
    *     private[this] var sum = 0
    *
    *     def onNext(elem: Int) = {
    *       sum += elem
    *       Continue
    *     }
    *
    *     def onError(ex: Throwable) = {
    *       logger.error(ex)
    *     }
    *
    *     def onComplete() = {
    *       logger.info("Stream completed")
    *     }
    *   })
    * }}}
    *
    * @param subscriber the subscriber instance that will get wrapped into a
    *                  `org.reactiveSubscriber`
    * @param requestCount the parameter passed to each `Subscription.request` call,
    *                    also representing the buffer size; MUST BE strictly positive
    */
  def apply[T](subscriber: Subscriber[T], requestCount: Int = 128): RSubscriber[T] =
    subscriber match {
      case ref: Subscriber.Sync[_] =>
        SyncSubscriberAsReactiveSubscriber(ref.asInstanceOf[Subscriber.Sync[T]], requestCount)
      case _ =>
        new SubscriberAsReactiveSubscriber[T](subscriber, requestCount)
    }
}

/**
  * Wraps a [[monix.reactive.observers.Observer.Sync Observer.Sync]] instance into a
  * `org.reactiveSubscriber` instance. The resulting
  * subscriber respects the [[http://www.reactive-streams.org/ Reactive Streams]]
  * contract.
  *
  * Given that we can guarantee a [[monix.reactive.observers.Observer.Sync Observer.Sync]]
  * is used, then no buffering is needed and thus the implementation is very efficient.
  *
  * To async an instance, [[SyncSubscriberAsReactiveSubscriber]] must be used: {{{
  *   // uses the default requestCount of 128
  *   val subscriber = SyncSubscriberAsReactiveSubscriber(new Observer[Int] {
  *     private[this] var sum = 0
  *
  *     def onNext(elem: Int) = {
  *       sum += elem
  *       Continue
  *     }
  *
  *     def onError(ex: Throwable) = {
  *       logger.error(ex)
  *     }
  *
  *     def onComplete() = {
  *       logger.info("Stream completed")
  *     }
  *   })
  * }}}
  */
private[monix] final class SyncSubscriberAsReactiveSubscriber[T] private
  (subscriber: Subscriber.Sync[T], requestCount: Int)
  extends RSubscriber[T] {

  require(requestCount > 0, "requestCount must be strictly positive, according to the Reactive Streams contract")

  private[this] implicit val s = subscriber.scheduler

  private[this] var subscription = null : RSubscription
  private[this] var expectingCount = 0
  @volatile private[this] var isCanceled = false

  def onSubscribe(s: RSubscription): Unit = {
    if (subscription == null && !isCanceled) {
      subscription = s
      expectingCount = requestCount
      s.request(requestCount)
    }
    else {
      s.cancel()
    }
  }

  def onNext(elem: T): Unit = {
    if (subscription == null) throw new NullPointerException("onSubscription never happened")
    if (elem == null) throw new NullPointerException("onNext(null)")

    if (!isCanceled) {
      if (expectingCount > 0) expectingCount -= 1

      subscriber.onNext(elem) match {
        case Continue =>
          // should it request more events?
          if (expectingCount == 0) {
            expectingCount = requestCount
            subscription.request(requestCount)
          }
        case Stop =>
          // downstream canceled, so we MUST cancel too
          isCanceled = true
          subscription.cancel()
      }
    }
  }

  def onError(ex: Throwable): Unit = {
    if (ex == null) throw new NullPointerException("onError(null)")

    if (!isCanceled) {
      isCanceled = true
      subscriber.onError(ex)
    }
  }

  def onComplete(): Unit =
    if (!isCanceled) {
      isCanceled = true
      subscriber.onComplete()
    }
}


private[monix] object SyncSubscriberAsReactiveSubscriber {
  /**
    * Wraps a [[monix.reactive.observers.Observer.Sync Observer.Sync]] instance into a
    * `org.reactiveSubscriber` instance. The resulting
    * subscriber respects the [[http://www.reactive-streams.org/ Reactive Streams]]
    * contract.
    *
    * Given that we can guarantee a [[monix.reactive.observers.Observer.Sync Observer.Sync]]
    * is used, then no buffering is needed and thus the implementation is very efficient.
    *
    * To async an instance, [[SyncSubscriberAsReactiveSubscriber.apply]] must be used: {{{
    *   // uses the default requestCount of 128
    *   val subscriber = SyncSubscriberAsReactiveSubscriber(new Observer[Int] {
    *     private[this] var sum = 0
    *
    *     def onNext(elem: Int) = {
    *       sum += elem
    *       Continue
    *     }
    *
    *     def onError(ex: Throwable) = {
    *       logger.error(ex)
    *     }
    *
    *     def onComplete() = {
    *       logger.info("Stream completed")
    *     }
    *   })
    * }}}
    *
    * @param subscriber the observer instance that will get wrapped into a
    *                  `org.reactiveSubscriber`, along with the
    *                  used scheduler
    * @param requestCount the parameter passed to `Subscription.request`
    */
  def apply[T](subscriber: Subscriber.Sync[T], requestCount: Int = 128): RSubscriber[T] = {
    new SyncSubscriberAsReactiveSubscriber[T](subscriber, requestCount)
  }
}
