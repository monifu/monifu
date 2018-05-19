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

import minitest.SimpleTestSuite
import monix.execution.ExecutionModel.AlwaysAsyncExecution
import monix.execution.{Cancelable, Scheduler}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

object TaskLocalJVMSuite extends SimpleTestSuite {
  def createShift(ec: ExecutionContext): Task[Unit] =
    Task.cancelableS { (_, cb) =>
      ec.execute(new Runnable { def run() = cb.onSuccess(()) })
      Cancelable.empty
    }

  test("locals get transported with executeOn and shift") {
    implicit val opts = Task.defaultOptions.enableLocalContextPropagation
    import Scheduler.Implicits.global

    val ec = Scheduler.computation(4, "ec1")
    val ec2 = Scheduler.computation(4, "ec2")

    try {
      val task =
        for {
          local <- TaskLocal(0)
          _ <- local.write(100).executeOn(ec2)
          v1 <- local.read.executeOn(ec)
          _ <- Task.shift(Scheduler.global)
          v2 <- local.read.executeOn(ec2)
          _ <- Task.shift
          v3 <- local.read.executeOn(ec2)
          _ <- createShift(ec2)
          v4 <- local.read
          v5 <- local.read.executeOn(ec)
        } yield v1 :: v2 :: v3 :: v4 :: v5 :: Nil

      val r = task.runSyncUnsafeOpt(Duration.Inf)
      assertEquals(r, List(100, 100, 100, 100, 100))
    } finally {
      ec.shutdown()
      ec2.shutdown()
    }
  }

  test("locals get transported with executeWithModel") {
    implicit val opts = Task.defaultOptions.enableLocalContextPropagation
    import Scheduler.Implicits.global

    val task =
      for {
        local <- TaskLocal(0)
        _ <- local.write(100).executeWithModel(AlwaysAsyncExecution)
        _ <- Task.shift
        v <- local.read
      } yield v

    val r = task.runSyncUnsafeOpt(Duration.Inf)
    assertEquals(r, 100)
  }

  test("locals get transported with executeWithOptions") {
    implicit val opts = Task.defaultOptions.enableLocalContextPropagation
    import Scheduler.Implicits.global

    val task =
      for {
        local <- TaskLocal(0)
        _ <- local.write(100).executeWithOptions(_.enableAutoCancelableRunLoops)
        _ <- Task.shift
        v <- local.read
      } yield v

    val r = task.runSyncUnsafeOpt(Duration.Inf)
    assertEquals(r, 100)
  }

  test("local.write.executeOn(forceAsync = false) works") {
    import Scheduler.Implicits.global
    implicit val opts = Task.defaultOptions.enableLocalContextPropagation
    val ec = Scheduler.computation(4, "ec1")

    val task = for {
      l <- TaskLocal(10)
      _ <- l.write(100).executeOn(ec, forceAsync = false)
      _ <- Task.shift
      v <- l.read
    } yield v

    val r = task.runSyncUnsafeOpt(Duration.Inf)
    assertEquals(r, 100)
  }

  test("local.write.executeOn(forceAsync = true) works") {
    import monix.execution.Scheduler.Implicits.global
    implicit val opts = Task.defaultOptions.enableLocalContextPropagation
    val ec = Scheduler.computation(4, "ec1")

    val task = for {
      l <- TaskLocal(10)
      _ <- l.write(100).executeOn(ec)
      _ <- Task.shift
      v <- l.read
    } yield v

    val r = task.runSyncUnsafeOpt(Duration.Inf)
    assertEquals(r, 100)
  }
}
