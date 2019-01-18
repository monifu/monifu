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

package monix.reactiveTests

import cats.effect.Sync
import monix.execution.Scheduler.Implicits.global
import monix.eval.Task
import monix.execution.exceptions.DummyException
import monix.tail.Iterant
import monix.tail.Iterant.{Halt, Last}
import monix.tail.batches.{Batch, BatchCursor}
import org.reactivestreams.Publisher
import org.reactivestreams.tck.PublisherVerification
import org.scalatest.testng.TestNGSuiteLike

import scala.util.Random

class IterantToPublisherTest extends PublisherVerification[Long](env())
  with TestNGSuiteLike {

  def createPublisher(elements: Long): Publisher[Long] = {
    if (elements < 4096) {
      val list: List[Long] = (0 until elements.toInt).map(_.toLong).toList
      arbitraryListToIterant[Task, Long](list, Random.nextInt(), allowErrors = false)
        .toReactivePublisher
    }
    else if (elements <= Int.MaxValue)
      repeat(1L).take(elements.toInt).toReactivePublisher
    else
      repeat(1L).toReactivePublisher
  }

  def repeat[A](elem: A): Iterant[Task, A] =
    Iterant[Task].now(elem) ++ Task.eval(repeat(elem))

  def createFailedPublisher(): Publisher[Long] = {
    Iterant[Task].raiseError[Long](new RuntimeException("dummy"))
      .toReactivePublisher
  }

  def arbitraryListToIterant[F[_], A](list: List[A], idx: Int, allowErrors: Boolean = true)
    (implicit F: Sync[F]): Iterant[F, A] = {

    def loop(list: List[A], idx: Int): Iterant[F, A] =
      list match {
        case Nil =>
          if (!allowErrors || math.abs(idx % 4) != 0)
            Iterant[F].haltS(None)
          else
            Iterant[F].haltS(Some(DummyException("arbitrary")))

        case x :: Nil if math.abs(idx % 2) == 1 =>
          if (idx % 4 == 1)
            Iterant[F].lastS(x)
          else
            Iterant.Concat(F.delay(Iterant[F].lastS(x)), F.delay(Halt(None)))

        case ns =>
          math.abs(idx % 16) match {
            case 0 | 1 =>
              Iterant[F].nextS(ns.head, F.delay(loop(ns.tail, idx+1)))
            case 2 | 3 =>
              Iterant[F].suspend(F.delay(loop(list, idx+1)))
            case 4 | 5 =>
              Iterant[F].suspendS(F.delay(loop(ns, idx + 1)))
            case n @ 6 =>
              val (headSeq, tail) = list.splitAt(3)
              val cursor = BatchCursor.fromIterator(headSeq.toVector.iterator, n - 2)
              Iterant[F].nextCursorS(cursor, F.delay(loop(tail, idx+1)))
            case n @ (7 | 8 | 9) =>
              val (headSeq, tail) = list.splitAt(3)
              val batch = Batch.fromSeq(headSeq.toVector, n - 6)
              Iterant[F].nextBatchS(batch, F.delay(loop(tail, idx + 1)))
            case 10 | 11 =>
              Iterant[F].nextBatchS(Batch.empty, F.delay(loop(ns, idx + 1)))
            case 12 | 13 =>
              Iterant[F].resource(F.delay(1)) {
                case 1 => F.unit
                case a => F.raiseError(new IllegalStateException(s"$a"))
              }.flatMap(_ => loop(list, idx + 1))
            case 14 =>
              Iterant.Concat(F.delay(Last(ns.head)), F.delay(loop(ns.tail, idx + 1)))
            case 15 =>
              Iterant.Concat(F.delay(Halt(None)), F.delay(loop(list, idx + 1)))
          }
      }

    Iterant[F].suspend(loop(list, idx))
  }
}
