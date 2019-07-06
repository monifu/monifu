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

package monix.benchmarks

import java.util.concurrent.TimeUnit
import monix.eval.Task
import monix.execution.Cancelable
import org.openjdk.jmh.annotations._
import scala.concurrent.Await
import scala.concurrent.duration.Duration

/** To do comparative benchmarks between versions:
  *
  *     benchmarks/run-benchmark TaskShiftBenchmark
  *
  * This will generate results in `benchmarks/results`.
  *
  * Or to run the benchmark from within SBT:
  *
  *     jmh:run monix.benchmarks.TaskShiftBenchmark
  *     The above test will take default values as "10 iterations", "10 warm-up iterations",
  *     "2 forks", "1 thread".
  *
  *     Or to specify custom values use below format:
  *
  *     jmh:run -i 20 -wi 20 -f 4 -t 2 monix.benchmarks.TaskShiftBenchmark
  *
  * Which means "20 iterations", "20 warm-up iterations", "4 forks", "2 thread".
  * Please note that benchmarks should be usually executed at least in
  * 10 iterations (as a rule of thumb), but more is better.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Measurement(iterations = 10)
@Warmup(iterations = 10)
@Fork(2)
@Threads(1)
class TaskShiftBenchmark {
  @Param(Array("3000"))
  var size: Int = _

  @Benchmark
  def trampolinedShift1(): Int = {
    def loop(i: Int): Task[Int] =
      if (i < size)
        TaskShiftBenchmark.trampolinedShift1.map(_ => i + 1).flatMap(loop)
      else
        Task.pure(i)

    val task = Task.pure(0).flatMap(loop)
    Await.result(task.runToFuture, Duration.Inf)
  }

  @Benchmark
  def trampolinedShift2(): Int = {
    def loop(i: Int): Task[Int] =
      if (i < size)
        TaskShiftBenchmark.trampolinedShift2.map(_ => i + 1).flatMap(loop)
      else
        Task.pure(i)

    val task = Task.pure(0).flatMap(loop)
    Await.result(task.runToFuture, Duration.Inf)
  }

  @Benchmark
  def forkedShift(): Int = {
    def loop(i: Int): Task[Int] =
      if (i < size)
        Task.shift.map(_ => i + 1).flatMap(loop)
      else
        Task.pure(i)

    val task = Task.pure(0).flatMap(loop)
    Await.result(task.runToFuture, Duration.Inf)
  }

  @Benchmark
  def lightAsync(): Int = {
    def loop(i: Int): Task[Int] =
      if (i < size)
        Task.async[Int](_.onSuccess(i + 1)).flatMap(loop)
      else
        Task.pure(i)

    val task = Task.pure(0).flatMap(loop)
    Await.result(task.runToFuture, Duration.Inf)
  }

  @Benchmark
  def executeWithOptions(): Int = {
    def loop(i: Int): Task[Int] =
      if (i < size)
        Task.now(i + 1).executeWithOptions(x => x).flatMap(loop)
      else
        Task.pure(i)

    val task = Task.pure(0).flatMap(loop)
    Await.result(task.runToFuture, Duration.Inf)
  }

  @Benchmark
  def createNonCancelable(): Int = {
    def loop(i: Int): Task[Int] =
      if (i < size)
        Task.create[Int] { (_, cb) => cb.onSuccess(i + 1); Cancelable.empty }.flatMap(loop)
      else
        Task.pure(i)

    val task = Task.pure(0).flatMap(loop)
    Await.result(task.runToFuture, Duration.Inf)
  }

  @Benchmark
  def createCancelable(): Int = {
    def loop(i: Int): Task[Int] =
      if (i < size)
        Task.create[Int] { (_, cb) => cb.onSuccess(i + 1); Cancelable(() => {}) }.flatMap(loop)
      else
        Task.pure(i)

    val task = Task.pure(0).flatMap(loop)
    Await.result(task.runToFuture, Duration.Inf)
  }
}

object TaskShiftBenchmark {
  val trampolinedShift1: Task[Unit] =
    Task.async(_.onSuccess(()))

  val trampolinedShift2: Task[Unit] =
    Task.Async((_, cb) => cb.onSuccess(()))
}