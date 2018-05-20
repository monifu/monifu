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

package monix.tail.internal

import cats.effect.Sync
import cats.syntax.all._
import scala.util.control.NonFatal
import monix.tail.Iterant
import monix.tail.Iterant.{Halt, Last, Next, NextBatch, NextCursor, Suspend}

import scala.runtime.ObjectRef

private[tail] object IterantSlice {
  /** Implementation for `Iterant#headOption`. */
  def headOptionL[F[_], A](source: Iterant[F, A])
    (implicit F: Sync[F]): F[Option[A]] = {

    def loop(stopRef: ObjectRef[F[Unit]])(source: Iterant[F, A]): F[Option[A]] = {
      try source match {
        case Next(a, _, stop) =>
          stopRef.elem = null.asInstanceOf[F[Unit]]
          stop.map(_ => Some(a))

        case NextCursor(items, rest, stop) =>
          stopRef.elem = stop
          if (items.hasNext()) stop.map { _ => stopRef.elem = null.asInstanceOf[F[Unit]]; Some(items.next()) }
          else rest.flatMap(loop(stopRef))

        case NextBatch(items, rest, stop) =>
          stopRef.elem = stop
          val cursor = items.cursor()
          if (cursor.hasNext()) stop.map { _ => stopRef.elem = null.asInstanceOf[F[Unit]]; Some(cursor.next()) }
          else rest.flatMap(loop(stopRef))

        case Suspend(rest, stop) =>
          stopRef.elem = stop
          rest.flatMap(loop(stopRef))

        case Last(a) =>
          stopRef.elem = null.asInstanceOf[F[Unit]]
          F.pure(Some(a))
        case Halt(None) =>
          F.pure(None)
        case Halt(Some(ex)) =>
          stopRef.elem = null.asInstanceOf[F[Unit]]
          F.raiseError(ex)
      } catch {
        case ex if NonFatal(ex) =>
          F.raiseError(ex)
      }
    }

    F.suspend {
      // Reference to keep track of latest `earlyStop` value
      val stopRef = ObjectRef.create(null.asInstanceOf[F[Unit]])
      // Catch-all exceptions, ensuring latest `earlyStop` gets called
      F.handleErrorWith(loop(stopRef)(source)) { ex =>
        stopRef.elem match {
          case null => F.raiseError(ex)
          case stop => stop *> F.raiseError(ex)
        }
      }
    }
  }
}