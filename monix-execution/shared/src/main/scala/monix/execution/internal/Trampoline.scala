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

package monix.execution.internal

import monix.execution.internal.collection.ArrayStack
import monix.execution.misc.NonFatal
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext

private[execution] class Trampoline(underlying: ExecutionContext) {
  private[this] var immediateQueue = new ArrayStack[Runnable]()
  private[this] var withinLoop = false

  def startLoop(runnable: Runnable): Unit = {
    withinLoop = true
    try immediateLoop(runnable) finally {
      withinLoop = false
    }
  }

  final def execute(runnable: Runnable): Unit = {
    if (!withinLoop) {
      startLoop(runnable)
    } else {
      immediateQueue.push(runnable)
    }
  }

  protected final def forkTheRest(): Unit = {
    final class ResumeRun(head: Runnable, rest: ArrayStack[Runnable])
      extends Runnable {

      def run(): Unit = {
        immediateQueue.pushAll(rest)
        immediateLoop(head)
      }
    }

    val head = immediateQueue.pop()
    if (head ne null) {
      val rest = immediateQueue
      immediateQueue = new ArrayStack[Runnable]
      underlying.execute(new ResumeRun(head, rest))
    }
  }

  @tailrec
  private final def immediateLoop(task: Runnable): Unit = {
    try {
      task.run()
    } catch {
      case ex: Throwable =>
        forkTheRest()
        if (NonFatal(ex)) underlying.reportFailure(ex)
        else throw ex
    }

    val next = immediateQueue.pop()
    if (next ne null) immediateLoop(next)
  }
}
