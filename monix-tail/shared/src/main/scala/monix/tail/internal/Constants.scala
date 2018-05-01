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

package monix.tail
package internal

import cats.effect.ExitCase
import cats.effect.ExitCase.Canceled
import monix.tail.Iterant.Halt

private[tail] object Constants {

  /** Internal API — reusable reference. */
  val canceledRef: ExitCase[Throwable] = Canceled(None)

  /** Internal API — reusable reference. */
  val emptyRef = Halt(None)
}
