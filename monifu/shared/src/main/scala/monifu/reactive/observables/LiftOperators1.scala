/*
 * Copyright (c) 2014-2015 by its authors. Some rights reserved.
 * See the project homepage at: http://www.monifu.org
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

package monifu.reactive.observables

import java.io.PrintStream
import monifu.concurrent.Scheduler
import monifu.concurrent.cancelables.BooleanCancelable
import monifu.reactive.OverflowStrategy.{Synchronous, Evicted}
import monifu.reactive.{Notification, Observable, OverflowStrategy}
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.language.higherKinds

/**
 * An interface to be extended in Observable types that want to preserve
 * the return type when applying operators. For example the result of
 * [[ConnectableObservable.map]] is still a `ConnectableObservable`
 * and this interface represents an utility to do just that.
 */
trait LiftOperators1[+T, Self[+U] <: Observable[U]] { self: Observable[T] =>
  protected def liftToSelf[U](f: Observable[T] => Observable[U]): Self[U]

  override def map[U](f: T => U): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).map(f))

  override def filter(p: (T) => Boolean): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).filter(p))

  override def collect[U](pf: PartialFunction[T, U]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).collect(pf))

  override def flatMap[U](f: (T) => Observable[U]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).flatMap(f))

  override def flatMapDelayError[U](f: (T) => Observable[U]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).flatMapDelayError(f))

  override def concatMap[U](f: (T) => Observable[U]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).concatMap(f))

  override def concatMapDelayError[U](f: (T) => Observable[U]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).concatMapDelayError(f))

  override def mergeMap[U](f: (T) => Observable[U]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).mergeMap(f))

  override def mergeMapDelayErrors[U](f: (T) => Observable[U]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).mergeMapDelayErrors(f))

  override def flatten[U](implicit ev: <:<[T, Observable[U]]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).flatten)

  override def flattenDelayError[U](implicit ev: <:<[T, Observable[U]]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).flattenDelayError)

  override def concat[U](implicit ev: <:<[T, Observable[U]]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).concat)

  override def concatDelayError[U](implicit ev: <:<[T, Observable[U]]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).concatDelayError)

  override def merge[U](implicit ev: <:<[T, Observable[U]]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).merge)

  override def merge[U](overflowStrategy: OverflowStrategy)(implicit ev: <:<[T, Observable[U]]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).merge(overflowStrategy))

  override def merge[U](overflowStrategy: Evicted, onOverflow: (Long) => U)(implicit ev: <:<[T, Observable[U]]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).merge(overflowStrategy, onOverflow))

  override def mergeDelayErrors[U](implicit ev: <:<[T, Observable[U]]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).mergeDelayErrors)

  override def mergeDelayErrors[U](overflowStrategy: OverflowStrategy)(implicit ev: <:<[T, Observable[U]]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).mergeDelayErrors(overflowStrategy))

  override def mergeDelayErrors[U](overflowStrategy: Evicted, onOverflow: (Long) => U)(implicit ev: <:<[T, Observable[U]]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).mergeDelayErrors(overflowStrategy, onOverflow))

  override def switch[U](implicit ev: <:<[T, Observable[U]]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).switch)

  override def switchDelayErrors[U](implicit ev: <:<[T, Observable[U]]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).switchDelayErrors)

  override def flatMapLatest[U](implicit ev: <:<[T, Observable[U]]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).flatMapLatest)

  override def flatMapLatestDelayErrors[U](implicit ev: <:<[T, Observable[U]]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).flatMapLatestDelayErrors)

  override def ambWith[U >: T](other: Observable[U]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).ambWith(other))

  override def defaultIfEmpty[U >: T](default: U): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).defaultIfEmpty(default))

  override def take(n: Long): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).take(n))

  override def take(timespan: FiniteDuration): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).take(timespan))

  override def takeRight(n: Int): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).takeRight(n))

  override def drop(n: Int): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).drop(n))

  override def dropByTimespan(timespan: FiniteDuration): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).dropByTimespan(timespan))

  override def dropWhile(p: (T) => Boolean): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).dropWhile(p))

  override def dropWhileWithIndex(p: (T, Int) => Boolean): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).dropWhileWithIndex(p))

  override def takeWhile(p: (T) => Boolean): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).takeWhile(p))

  override def takeWhileNotCanceled(c: BooleanCancelable): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).takeWhileNotCanceled(c))

  override def count(): Self[Long] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).count())

  override def buffer(count: Int): Self[Seq[T]] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).buffer(count))

  override def buffer(count: Int, skip: Int): Self[Seq[T]] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).buffer(count, skip))

  override def buffer(timespan: FiniteDuration): Self[Seq[T]] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).buffer(timespan))

  override def buffer(timespan: FiniteDuration, maxSize: Int): Self[Seq[T]] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).buffer(timespan, maxSize))

  override def window(count: Int): Self[Observable[T]] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).window(count))

  override def window(count: Int, skip: Int): Self[Observable[T]] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).window(count, skip))

  override def window(timespan: FiniteDuration): Self[Observable[T]] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).window(timespan))

  override def window(timespan: FiniteDuration, maxCount: Int): Self[Observable[T]] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).window(timespan, maxCount))

  override def throttleLast(period: FiniteDuration): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).throttleLast(period))

  override def throttleFirst(interval: FiniteDuration): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).throttleFirst(interval))

  override def throttleWithTimeout(initialDelay: FiniteDuration, timeout: FiniteDuration): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).throttleWithTimeout(initialDelay, timeout))

  override def sample(delay: FiniteDuration): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).sample(delay))

  override def sample(initialDelay: FiniteDuration, delay: FiniteDuration): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).sample(initialDelay, delay))

  override def sample[U](sampler: Observable[U]): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).sample(sampler))

  override def sampleRepeated(delay: FiniteDuration): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).sampleRepeated(delay))

  override def sampleRepeated(initialDelay: FiniteDuration, delay: FiniteDuration): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).sampleRepeated(initialDelay, delay))

  override def sampleRepeated[U](sampler: Observable[U]): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).sampleRepeated(sampler))

  override def debounce(initialDelay: FiniteDuration, timeout: FiniteDuration): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).debounce(initialDelay, timeout))

  override def echoOnce(timeout: FiniteDuration): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).echoOnce(timeout))

  override def echoRepeated(timeout: FiniteDuration): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).echoRepeated(timeout))

  override def delaySubscription(future: Future[_]): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).delaySubscription(future))

  override def delaySubscription(timespan: FiniteDuration): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).delaySubscription(timespan))

  override def foldLeft[R](initial: R)(op: (R, T) => R): Self[R] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).foldLeft(initial)(op))

  override def reduce[U >: T](op: (U, U) => U): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).reduce(op))

  override def scan[R](initial: R)(op: (R, T) => R): Self[R] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).scan(initial)(op))

  override def flatScan[R](initial: R)(op: (R, T) => Observable[R]): Self[R] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).flatScan(initial)(op))

  override def flatScanDelayError[R](initial: R)(op: (R, T) => Observable[R]): Self[R] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).flatScanDelayError(initial)(op))

  override def doOnComplete(cb: => Unit): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).doOnComplete(cb))

  override def doWork(cb: (T) => Unit): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).doWork(cb))

  override def doOnStart(cb: (T) => Unit): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).doOnStart(cb))

  override def doOnCanceled(cb: => Unit): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).doOnCanceled(cb))

  override def doOnError(cb: (Throwable) => Unit): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).doOnError(cb))

  override def find(p: (T) => Boolean): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).find(p))

  override def exists(p: (T) => Boolean): Self[Boolean] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).exists(p))

  override def isEmpty: Self[Boolean] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).isEmpty)

  override def nonEmpty: Self[Boolean] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).nonEmpty)

  override def forAll(p: (T) => Boolean): Self[Boolean] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).forAll(p))

  override def complete: Self[Nothing] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).complete)

  override def error: Self[Throwable] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).error)

  override def endWithError(error: Throwable): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).endWithError(error))

  override def +:[U >: T](elem: U): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).+:(elem))

  override def startWith[U >: T](elems: U*): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).startWith(elems:_*))

  override def :+[U >: T](elem: U): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).:+(elem))

  override def endWith[U >: T](elems: U*): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).endWith(elems:_*))

  override def ++[U >: T](other: => Observable[U]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).++(other))

  override def head: Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).head)

  override def tail: Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).tail)

  override def last: Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).last)

  override def headOrElse[B >: T](default: => B): Self[B] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).headOrElse(default))

  override def firstOrElse[U >: T](default: => U): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).firstOrElse(default))

  override def zip[U](other: Observable[U]): Self[(T, U)] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).zip(other))

  override def combineLatest[U](other: Observable[U]): Self[(T, U)] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).combineLatest(other))

  override def combineLatestDelayError[U](other: Observable[U]): Self[(T, U)] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).combineLatestDelayError(other))

  override def max[U >: T](implicit ev: Ordering[U]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).max(ev))

  override def maxBy[U](f: (T) => U)(implicit ev: Ordering[U]): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).maxBy(f))

  override def min[U >: T](implicit ev: Ordering[U]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).min(ev))

  override def minBy[U](f: (T) => U)(implicit ev: Ordering[U]): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).minBy(f))

  override def sum[U >: T](implicit ev: Numeric[U]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).sum(ev))

  override def distinct: Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).distinct)

  override def distinct[U](fn: (T) => U): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).distinct(fn))

  override def distinctUntilChanged: Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).distinctUntilChanged)

  override def distinctUntilChanged[U](fn: (T) => U): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).distinctUntilChanged(fn))

  override def subscribeOn(s: Scheduler): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).subscribeOn(s))

  override def materialize: Self[Notification[T]] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).materialize)

  override def dump(prefix: String, out: PrintStream = System.out): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).dump(prefix, out))

  override def repeat: Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).repeat)

  override def asyncBoundary(overflowStrategy: OverflowStrategy): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).asyncBoundary(overflowStrategy))

  override def asyncBoundary[U >: T](overflowStrategy: Evicted, onOverflow: (Long) => U): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).asyncBoundary(overflowStrategy, onOverflow))

  override def whileBusyDropEvents: Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).whileBusyDropEvents)

  override def whileBusyDropEvents[U >: T](onOverflow: (Long) => U): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).whileBusyDropEvents(onOverflow))

  override def whileBusyBuffer[U >: T](overflowStrategy: Synchronous): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).whileBusyBuffer(overflowStrategy))

  override def whileBusyBuffer[U >: T](overflowStrategy: Evicted, onOverflow: (Long) => U): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).whileBusyBuffer(overflowStrategy, onOverflow))

  override def onErrorRecoverWith[U >: T](pf: PartialFunction[Throwable, Observable[U]]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).onErrorRecoverWith(pf))

  override def onErrorFallbackTo[U >: T](that: => Observable[U]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).onErrorFallbackTo(that))

  override def onErrorRetryUnlimited: Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).onErrorRetryUnlimited)

  override def onErrorRetry(maxRetries: Long): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).onErrorRetry(maxRetries))

  override def onErrorRetryIf(p: (Throwable) => Boolean): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).onErrorRetryIf(p))

  override def timeout(timeout: FiniteDuration): Self[T] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).timeout(timeout))

  override def timeout[U >: T](timeout: FiniteDuration, backup: Observable[U]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).timeout(timeout, backup))

  override def lift[U](f: (Observable[T]) => Observable[U]): Self[U] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).lift(f))

  override def groupBy[K](keySelector: (T) => K): Self[GroupedObservable[K, T]] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).groupBy(keySelector))

  override def groupBy[K](keyBufferSize: Int, keySelector: (T) => K): Self[GroupedObservable[K, T]] =
    liftToSelf(o => Observable.create[T](o.onSubscribe).groupBy(keyBufferSize, keySelector))
}
