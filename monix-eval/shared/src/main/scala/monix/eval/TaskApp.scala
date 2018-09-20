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

package monix.eval

import cats.effect._
import monix.eval.instances.CatsConcurrentEffectForTask
import monix.execution.Scheduler

/** Safe `App` type that executes a [[Task]].  Shutdown occurs after
  * the `Task` completes, as follows:
  *
  * - If completed with `ExitCode.Success`, the main method exits and
  *   shutdown is handled by the platform.
  *
  * - If completed with any other `ExitCode`, `sys.exit` is called
  *   with the specified code.
  *
  * - If the `Task` raises an error, the stack trace is printed to
  *   standard error and `sys.exit(1)` is called.
  *
  * When a shutdown is requested via a signal, the `Task` is canceled and
  * we wait for the `IO` to release any resources.  The process exits
  * with the numeric value of the signal plus 128.
  *
  * {{{
  *   import cats.effect._
  *   import cats.implicits._
  *   import monix.eval._
  *
  *   object MyApp extends TaskApp {
  *     def run(args: List[String]): Task[ExitCode] =
  *       args.headOption match {
  *         case Some(name) =>
  *           Task(println(s"Hello, \\${name}.")).as(ExitCode.Success)
  *         case None =>
  *           Task(System.err.println("Usage: MyApp name")).as(ExitCode(2))
  *       }
  *   }
  * }}}
  *
  * N.B. this is homologous with
  * [[https://typelevel.org/cats-effect/datatypes/ioapp.html cats.effect.IOApp]],
  * but meant for usage with [[Task]].
  *
  * Works on top of JavaScript as well ;-)
  */
trait TaskApp {
  // To implement ...
  def run(args: List[String]): Task[ExitCode]

  /** Scheduler for executing the [[Task]] action.
    * Defaults to `global`, but can be overridden.
    */
  protected def scheduler: Scheduler = Scheduler.global

  /** Provides the
    * [[https://typelevel.org/cats-effect/typeclasses/concurrent-effect.html cats.effect.ConcurrentEffect]]
    * instance of this runtime environment.
    */
  protected implicit lazy val catsEffect: ConcurrentEffect[Task] =
    new CatsConcurrentEffectForTask()(scheduler)

  final def main(args: Array[String]): Unit = {
    val self = this
    val app = new IOApp {
      override lazy val timer: Timer[IO] =
        scheduler.timer[IO]
      override lazy val contextShift: ContextShift[IO] =
        scheduler.contextShift[IO]
      def run(args: List[String]): IO[ExitCode] =
        self.run(args).toIO
    }
    app.main(args)
  }
}
