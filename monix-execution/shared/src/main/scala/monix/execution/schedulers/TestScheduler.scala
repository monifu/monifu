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

import monix.execution.Cancelable
import monix.execution.atomic.AtomicAny
import monix.execution.cancelables.SingleAssignCancelable
import monix.execution.misc.NonFatal
import monix.execution.schedulers.TestScheduler._
// Prevents conflict with the deprecated symbol
import monix.execution.{ExecutionModel => ExecModel}

import scala.annotation.tailrec
import scala.collection.immutable.SortedSet
import scala.concurrent.duration.{Duration, FiniteDuration, TimeUnit}
import scala.util.Random

/** A scheduler meant for testing purposes. */
final class TestScheduler private (
  private[this] val stateRef: AtomicAny[State],
  override val executionModel: ExecModel)
  extends ReferenceScheduler with BatchingScheduler {

  /** Returns the internal state of the [[TestScheduler]]. */
  def state: State = stateRef.get

  override def currentTimeMillis(): Long =
    stateRef.get.clock.toMillis

  @tailrec
  private def cancelTask(t: Task): Unit = {
    val current: State = stateRef.get
    val update = current.copy(tasks = current.tasks - t)
    if (!stateRef.compareAndSet(current, update)) cancelTask(t)
  }

  @tailrec
  override def scheduleOnce(initialDelay: Long, unit: TimeUnit, r: Runnable): Cancelable = {
    val current: State = stateRef.get
    val (cancelable, newState) =
      current.scheduleOnce(FiniteDuration(initialDelay, unit), r, cancelTask)
    if (stateRef.compareAndSet(current, newState)) cancelable else
      scheduleOnce(initialDelay, unit, r)
  }

  @tailrec
  protected override def executeAsync(r: Runnable): Unit = {
    val current: State = stateRef.get
    val update = current.execute(r)
    if (!stateRef.compareAndSet(current, update)) executeAsync(r)
  }

  @tailrec
  override def reportFailure(t: Throwable): Unit = {
    val current: State = stateRef.get
    val update = current.copy(lastReportedError = t)
    if (!stateRef.compareAndSet(current, update)) reportFailure(t)
  }

  override def withExecutionModel(em: ExecModel): TestScheduler =
    new TestScheduler(stateRef, em)

  private[this] def extractOneTask(current: State, clock: FiniteDuration): Option[(Task, SortedSet[Task])] = {
    current.tasks.headOption.filter(_.runsAt <= clock) match {
      case Some(value) =>
        val firstTick = value.runsAt
        val forExecution = {
          val arr = current.tasks.iterator.takeWhile(_.runsAt == firstTick).take(10).toArray
          arr(Random.nextInt(arr.length))
        }

        val remaining = current.tasks - forExecution
        Some((forExecution, remaining))

      case None =>
        None
    }
  }

  @tailrec
  def tickOne(): Boolean = {
    val current = stateRef.get

    // extracting one task by taking the immediate tasks
    extractOneTask(current, current.clock) match {
      case Some((head, rest)) =>
        if (!stateRef.compareAndSet(current, current.copy(tasks = rest)))
          tickOne()
        else {
          // execute task
          try head.task.run() catch {
            case ex if NonFatal(ex) =>
              reportFailure(ex)
          }

          true
        }

      case None =>
        false
    }
  }

  def tick(time: FiniteDuration = Duration.Zero): Unit = {
    @tailrec
    def loop(time: FiniteDuration, result: Boolean): Unit = {
      val current: State = stateRef.get
      val currentClock = current.clock + time

      extractOneTask(current, currentClock) match {
        case Some((head, rest)) =>
          if (!stateRef.compareAndSet(current, current.copy(clock = head.runsAt, tasks = rest)))
            loop(time, result)
          else {
            // execute task
            try head.task.run() catch {
              case ex if NonFatal(ex) =>
                reportFailure(ex)
            }

            // have to retry execution, as those pending tasks
            // may have registered new tasks for immediate execution
            loop(currentClock - head.runsAt, result = true)
          }

        case None =>
          if (!stateRef.compareAndSet(current, current.copy(clock = currentClock)))
            loop(time, result)
      }
    }

    loop(time, result = false)
  }
}

object TestScheduler {
  /** Builder for [[TestScheduler]]. */
  def apply(): TestScheduler =
    apply(ExecModel.Default)

  /** Builder for [[TestScheduler]]. */
  def apply(executionModel: ExecModel): TestScheduler = {
    val state = AtomicAny(State(
      lastID = 0,
      clock = Duration.Zero,
      tasks = SortedSet.empty[Task],
      lastReportedError = null
    ))

    new TestScheduler(state, executionModel)
  }

  /** Used internally by [[TestScheduler]], represents a
    * unit of work pending execution.
    */
  case class Task(id: Long, task: Runnable, runsAt: FiniteDuration)

  object Task {
    implicit val ordering: Ordering[Task] =
      new Ordering[Task] {
        val longOrd = implicitly[Ordering[Long]]

        def compare(x: Task, y: Task): Int =
          x.runsAt.compare(y.runsAt) match {
            case nonZero if nonZero != 0 =>
              nonZero
            case _ =>
              longOrd.compare(x.id, y.id)
          }
      }
  }

  /** Used internally by [[TestScheduler]], represents the internal
    * state used for task scheduling and execution.
    */
  case class State(
    lastID: Long,
    clock: FiniteDuration,
    tasks: SortedSet[Task],
    lastReportedError: Throwable) {

    assert(!tasks.headOption.exists(_.runsAt < clock),
      "The runsAt for any task must never be in the past")

    /** Returns a new state with the runnable scheduled for execution. */
    def execute(runnable: Runnable): State = {
      val newID = lastID + 1
      val task = Task(newID, runnable, clock)
      copy(lastID = newID, tasks = tasks + task)
    }

    /** Returns a new state with a scheduled task included. */
    def scheduleOnce(delay: FiniteDuration, r: Runnable, cancelTask: Task => Unit): (Cancelable, State) = {
      require(delay >= Duration.Zero, "The given delay must be positive")

      val newID = lastID + 1
      SingleAssignCancelable()

      val task = Task(newID, r, this.clock + delay)
      val cancelable = new Cancelable {
        def cancel(): Unit = cancelTask(task)
        override def toString =
          s"monix.execution.schedulers.TestScheduler.TaskCancelable@$hashCode"
      }

      (cancelable, copy(
        lastID = newID,
        tasks = tasks + task
      ))
    }
  }
}
