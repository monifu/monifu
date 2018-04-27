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

import cats.effect.{Async, Timer}
import monix.tail.Iterant
import scala.concurrent.duration._

private[tail] object IterantIntervalWithFixedDelay {
  /**
    * Implementation for `Iterant.intervalWithFixedDelay`.
    */
  def apply[F[_]](initialDelay: FiniteDuration, delay: FiniteDuration)
    (implicit F: Async[F], timer: Timer[F]): Iterant[F, Long] = {

    // Recursive loop
    def loop(index: Long): Iterant[F, Long] = {
      val next = F.map(timer.sleep(delay))(_ => loop(index + 1))
      Iterant.nextS[F, Long](index, next, F.unit)
    }

    if (initialDelay > Duration.Zero)
      Iterant.suspendS(F.map(timer.sleep(initialDelay))(_ => loop(0)), F.unit)
    else
      loop(0)
  }
}
