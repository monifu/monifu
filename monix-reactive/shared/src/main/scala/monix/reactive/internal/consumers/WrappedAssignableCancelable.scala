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

package monix.reactive.internal.consumers

import monix.execution.Cancelable
import monix.execution.cancelables.AssignableCancelable
import scala.collection.mutable.ArrayBuffer

/** Internal implementation of an `AssignableCancelable` that wraps another.
  *
  * Useful in the implementation of [[monix.reactive.Consumer consumers]]
  * that wrap other consumer references.
  */
private[reactive]
final class WrappedAssignableCancelable(underlying: AssignableCancelable, plus: Seq[Cancelable])
  extends AssignableCancelable {

  private[this] val all = {
    val buf = ArrayBuffer(underlying : Cancelable)
    plus.foreach { c => if (!c.isInstanceOf[Cancelable.IsDummy]) buf += c }
    Cancelable.collection(buf)
  }

  override def `:=`(value: Cancelable): WrappedAssignableCancelable.this.type = {
    underlying := value
    this
  }

  override def cancel(): Unit =
    all.cancel()
}

private[reactive] object WrappedAssignableCancelable {
  def apply(underlying: AssignableCancelable, plus: Cancelable*): AssignableCancelable =
    new WrappedAssignableCancelable(underlying, plus)
}
