/*
 * Copyright (c) 2014-2017 by its authors. Some rights reserved.
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

package monix.eval.internal

import monix.eval.{Iterant, Task}
import monix.eval.Iterant._
import monix.eval.internal.IterantUtils._
import scala.util.control.NonFatal

private[eval] object IterantStop {
  /**
    * Implementation for `Iterant#doOnEarlyStop`
    */
  def doOnEarlyStop[A](source: Iterant[A], f: Task[Unit]): Iterant[A] = {
    source match {
      case Next(head, rest, stop) =>
        Next(head, rest.map(doOnEarlyStop[A](_, f)), stop.flatMap(_ => f))
      case NextSeq(items, rest, stop) =>
        NextSeq(items, rest.map(doOnEarlyStop[A](_, f)), stop.flatMap(_ => f))
      case Suspend(rest, stop) =>
        Suspend(rest.map(doOnEarlyStop[A](_, f)), stop.flatMap(_ => f))
      case NextGen(items, rest, stop) =>
        NextGen(items, rest.map(doOnEarlyStop[A](_, f)), stop.flatMap(_ => f))
      case ref @ (Halt(_) | Last(_)) =>
        ref // nothing to do
    }
  }

  /**
    * Implementation for `Iterant#doOnFinish`
    */
  def doOnFinish[A](source: Iterant[A], f: Option[Throwable] => Task[Unit]): Iterant[A] = {
    try source match {
      case Next(item, rest, stop) =>
        Next(item, rest.map(doOnFinish[A](_, f)), stop.flatMap(_ => f(None)))
      case NextSeq(items, rest, stop) =>
        NextSeq(items, rest.map(doOnFinish[A](_, f)), stop.flatMap(_ => f(None)))
      case NextGen(items, rest, stop) =>
        NextGen(items, rest.map(doOnFinish[A](_, f)), stop.flatMap(_ => f(None)))
      case Suspend(rest, stop) =>
        Suspend(rest.map(doOnFinish[A](_, f)), stop.flatMap(_ => f(None)))
      case last @ Last(_) =>
        val ref = f(None)
        Suspend[A](ref.map(_ => last), ref)
      case halt @ Halt(ex) =>
        val ref = f(ex)
        Suspend[A](ref.map(_ => halt), ref)
    }
    catch {
      case NonFatal(ex) => signalError(source, ex)
    }
  }
}
