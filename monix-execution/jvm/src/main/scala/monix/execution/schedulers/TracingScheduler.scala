package monix.execution.schedulers

import java.util.concurrent.{ScheduledExecutorService, TimeUnit}
import monix.execution.{Cancelable, Scheduler, UncaughtExceptionReporter, ExecutionModel => ExecModel}
import scala.concurrent.ExecutionContext
import scala.util.DynamicVariable

final class TracingScheduler private (
   scheduler: ScheduledExecutorService,
   ec: ExecutionContext,
   r: UncaughtExceptionReporter,
   val executionModel: ExecModel)
  extends ReferenceScheduler with BatchingScheduler { self =>

  override def executeAsync(r: Runnable): Unit =
    executeWithTrace(r)

  /** Executes the given task with tracing.
    *
    * @param r is the callback to be executed
    */
  def executeWithTrace(r: Runnable): Unit = {
    val oldContext = TracingScheduler.TracingContext.value
    ec.execute(new Runnable {
      override def run = {
        TracingScheduler.TracingContext.withValue(oldContext)(r.run())
      }
    })
  }

  /** Schedules a task to run in the future, after `initialDelay`.
    *
    * For example the following schedules a message to be printed to
    * standard output after 5 minutes:
    * {{{
    *   val task = scheduler.scheduleOnce(5, TimeUnit.MINUTES, new Runnable {
    *     def run() = print("Hello, world!")
    *   })
    *
    *   // later if you change your mind ...
    *   task.cancel()
    * }}}
    *
    * @param initialDelay is the time to wait until the execution happens
    * @param unit         is the time unit used for `initialDelay`
    * @param r            is the callback to be executed
    * @return a `Cancelable` that can be used to cancel the created task
    *         before execution.
    */
  override def scheduleOnce(initialDelay: Long, unit: TimeUnit, r: Runnable): Cancelable = {
    if (initialDelay <= 0) {
      executeWithTrace(r)
      Cancelable.empty
    } else {
      val deferred = new Runnable {
        override def run(): Unit = executeWithTrace(r)
      }
      val task = scheduler.schedule(deferred, initialDelay, unit)
      Cancelable(() => task.cancel(true))
    }
  }

  /** Reports that an asynchronous computation failed. */
  override def reportFailure(t: Throwable): Unit =
    r.reportFailure(t)

  override def withExecutionModel(em: ExecModel): TracingScheduler =
    new TracingScheduler(scheduler, ec, r, em)
}

object TracingScheduler {

  object TracingContext extends DynamicVariable[Map[String, String]](Map.empty[String, String])

  object Implicits {
    implicit lazy val traced: Scheduler =
      new TracingScheduler(
        Scheduler.DefaultScheduledExecutor,
        ExecutionContext.Implicits.global,
        UncaughtExceptionReporter.LogExceptionsToStandardErr,
        ExecModel.Default
      )
  }

  def apply(
     schedulerService: ScheduledExecutorService,
     ec: ExecutionContext,
     reporter: UncaughtExceptionReporter,
     executionModel: ExecModel): TracingScheduler =
    new TracingScheduler(schedulerService, ec, reporter, executionModel)
}