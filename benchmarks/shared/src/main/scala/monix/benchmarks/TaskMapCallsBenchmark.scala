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

/*
package monix.benchmarks

import java.util.concurrent.TimeUnit
import monix.eval.Task
import org.openjdk.jmh.annotations._
import scala.concurrent.Await
import scala.concurrent.duration.Duration

/** To do comparative benchmarks between versions:
  *
  *     benchmarks/run-benchmark TaskMapCallsBenchmark
  *
  * This will generate results in `benchmarks/results`.
  *
  * Or to run the benchmark from within SBT:
  *
  *     jmh:run -i 10 -wi 10 -f 2 -t 1 monix.benchmarks.TaskMapCallsBenchmark
  *
  * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread".
  * Please note that benchmarks should be usually executed at least in
  * 10 iterations (as a rule of thumb), but more is better.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class TaskMapCallsBenchmark {
  import TaskMapCallsBenchmark.test

  @Benchmark
  def one(): Long = test(12000, 1)

  @Benchmark
  def batch30(): Long = test(12000 / 30, 30)

  @Benchmark
  def batch120(): Long = test(12000 / 120, 120)
}

object TaskMapCallsBenchmark {
  def test(iterations: Int, batch: Int): Long = {
    val f = (x: Int) => x + 1
    var task = Task.eval(0)

    var j = 0
    while (j < batch) { task = task.map(f); j += 1 }

    var sum = 0L
    var i = 0
    while (i < iterations) {
      sum += Await.result(task.runToFuture, Duration.Inf)
      i += 1
    }
    sum
  }
}
*/