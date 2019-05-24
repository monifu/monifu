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

package monix.execution.schedulers

import scala.concurrent.duration.TimeUnit

import monix.execution.{Cancelable, Scheduler, UncaughtExceptionReporter, ExecutionModel => ExecModel}
import scala.concurrent.ExecutionContext

/** The `TracingScheduler` is a [[monix.execution.Scheduler Scheduler]]
  * implementation that wraps another `Scheduler` reference, but
  * that propagates the [[monix.execution.misc.Local.Context Local.Context]]
  * on async execution.
  *
  * @param underlying the [[monix.execution.Scheduler Scheduler]]
  *        in charge of the actual execution and scheduling
  */
final class TracingScheduler private (underlying: Scheduler)
  extends TracingScheduler.Base(underlying) {

  override def withExecutionModel(em: ExecModel): TracingScheduler =
    new TracingScheduler(underlying.withExecutionModel(em))

  def withUncaughtExceptionReporter(r: UncaughtExceptionReporter): Scheduler =
    new TracingScheduler(underlying.withUncaughtExceptionReporter(r))
}

object TracingScheduler {
  /** Builds a [[TracingScheduler]] instance, wrapping the
    * `underlying` scheduler given.
    */
  def apply(underlying: ExecutionContext): TracingScheduler =
    underlying match {
      case ref: TracingScheduler => ref
      case ref: Scheduler => new TracingScheduler(ref)
      case _ => new TracingScheduler(Scheduler(underlying))
    }

  /** Common implementation between [[TracingScheduler]]
    * and [[TracingSchedulerService]].
    */
  private[execution] abstract class Base(underlying: Scheduler)
    extends Scheduler with BatchingScheduler {

    override final def executeAsync(r: Runnable): Unit =
      underlying.execute(new TracingRunnable(r))
    override final def scheduleOnce(initialDelay: Long, unit: TimeUnit, r: Runnable): Cancelable =
      underlying.scheduleOnce(initialDelay, unit, new TracingRunnable(r))
    override final def scheduleWithFixedDelay(initialDelay: Long, delay: Long, unit: TimeUnit, r: Runnable) =
      underlying.scheduleWithFixedDelay(initialDelay, delay, unit, new TracingRunnable(r))
    override final def scheduleAtFixedRate(initialDelay: Long, period: Long, unit: TimeUnit, r: Runnable) =
      underlying.scheduleAtFixedRate(initialDelay, period, unit, new TracingRunnable(r))
    override final def reportFailure(t: Throwable): Unit =
      underlying.reportFailure(t)
    override final def clockRealTime(unit: TimeUnit): Long =
      underlying.clockRealTime(unit)
    override def clockMonotonic(unit: TimeUnit): Long =
      underlying.clockMonotonic(unit)
    override final def executionModel: ExecModel =
      underlying.executionModel
  }
}