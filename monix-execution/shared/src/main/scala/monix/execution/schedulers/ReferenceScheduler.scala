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

package monix.execution.schedulers

import monix.execution.cancelables.OrderedCancelable
import monix.execution.schedulers.ReferenceScheduler.WrappedScheduler
import monix.execution.{Cancelable, Scheduler}
import scala.concurrent.duration.{TimeUnit, MILLISECONDS, NANOSECONDS}
// Prevents conflict with the deprecated symbol
import monix.execution.{ExecutionModel => ExecModel}

/** Helper for building a [[Scheduler]].
  *
  * You can inherit from this class and provided a correct
  * [[Scheduler.scheduleOnce(initialDelay* scheduleOnce]]
  * you'll get [[Scheduler.scheduleWithFixedDelay]] and
  * [[Scheduler.scheduleAtFixedRate]] for free.
  */
trait ReferenceScheduler extends Scheduler {
  override def clockRealTime(unit: TimeUnit): Long =
    unit.convert(System.currentTimeMillis(), MILLISECONDS)
  override def clockMonotonic(unit: TimeUnit): Long =
    unit.convert(System.nanoTime(), NANOSECONDS)

  override def scheduleWithFixedDelay(initialDelay: Long, delay: Long, unit: TimeUnit, r: Runnable): Cancelable = {
    val sub = OrderedCancelable()

    def loop(initialDelay: Long, delay: Long): Unit = {
      if (!sub.isCanceled)
        sub := scheduleOnce(initialDelay, unit, new Runnable {
          def run(): Unit = {
            r.run()
            loop(delay, delay)
          }
        })
    }

    loop(initialDelay, delay)
    sub
  }

  override def scheduleAtFixedRate(initialDelay: Long, period: Long, unit: TimeUnit, r: Runnable): Cancelable = {
    val sub = OrderedCancelable()

    def loop(initialDelayMs: Long, periodMs: Long): Unit = {
      // Measuring the duration of the task + possible scheduler lag
      val startedAtMillis = clockMonotonic(MILLISECONDS) + initialDelayMs

      if (!sub.isCanceled) {
        sub := scheduleOnce(initialDelayMs, MILLISECONDS, new Runnable {
          def run(): Unit = {
            r.run()

            val delay = {
              val durationMillis = clockMonotonic(MILLISECONDS) - startedAtMillis
              val d = periodMs - durationMillis
              if (d >= 0) d else 0
            }

            // Recursive call
            loop(delay, periodMs)
          }
        })
      }
    }

    val initialMs = MILLISECONDS.convert(initialDelay, unit)
    val periodMs = MILLISECONDS.convert(period, unit)
    loop(initialMs, periodMs)
    sub
  }

  override def withExecutionModel(em: ExecModel): Scheduler =
    WrappedScheduler(this, em)
}

object ReferenceScheduler {
  /** Wrapper around any scheduler implementation,
    * for specifying any execution model.
    */
  private final case class WrappedScheduler(
    s: Scheduler,
    override val executionModel: ExecModel)
    extends Scheduler {

    override def execute(runnable: Runnable): Unit =
      s.execute(runnable)
    override def reportFailure(t: Throwable): Unit =
      s.reportFailure(t)
    override def scheduleOnce(initialDelay: Long, unit: TimeUnit, r: Runnable): Cancelable =
      s.scheduleOnce(initialDelay, unit, r)
    override def scheduleWithFixedDelay(initialDelay: Long, delay: Long, unit: TimeUnit, r: Runnable): Cancelable =
      s.scheduleWithFixedDelay(initialDelay, delay, unit, r)
    override def scheduleAtFixedRate(initialDelay: Long, period: Long, unit: TimeUnit, r: Runnable): Cancelable =
      s.scheduleAtFixedRate(initialDelay, period, unit, r)
    override def clockRealTime(unit: TimeUnit): Long =
      s.clockRealTime(unit)
    override def clockMonotonic(unit: TimeUnit): Long =
      s.clockMonotonic(unit)
    override def withExecutionModel(em: ExecModel): Scheduler =
      copy(s, em)
  }
}
