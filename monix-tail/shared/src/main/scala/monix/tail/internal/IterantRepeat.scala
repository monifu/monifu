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
import monix.tail.Iterant

// TODO: temporary implementation to handle Scope correctly
private[tail] object IterantRepeat {
  /** Implementation for `Iterant.repeat`. */
  def apply[F[_], A, B](source: Iterant[F, A])(implicit F: Sync[F]): Iterant[F, A] = {
    Iterant.fromIterator(Iterator.continually(())).flatMap(_ => source)
  }
}