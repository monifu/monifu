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
import monix.eval.Coeval
import org.openjdk.jmh.annotations._

/** To do comparative benchmarks between versions:
  *
  *     benchmarks/run-benchmark CoevalAttemptBenchmark
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
class CoevalAttemptBenchmark {
  @Param(Array("10000"))
  var size: Int = _

  @Benchmark
  def happyPath(): Int = {
    def loop(i: Int): Coeval[Int] =
      if (i < size) Coeval.pure(i + 1).attempt.flatMap(_.fold(Coeval.raiseError, loop))
      else Coeval.pure(i)

    loop(0).value
  }

  @Benchmark
  def errorRaised(): Int = {
    val dummy = new RuntimeException("dummy")
    val id = Coeval.pure[Int] _

    def loop(i: Int): Coeval[Int] =
      if (i < size)
        Coeval.raiseError[Int](dummy)
          .flatMap(x => Coeval.pure(x + 1))
          .attempt
          .flatMap(_.fold(_ => loop(i + 1), id))
      else
        Coeval.pure(i)

    loop(0).value
  }
}
