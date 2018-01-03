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
package batches

/** [[BatchCursor]] implementation specialized for `Byte`.
  *
  * Under the hood it uses an [[monix.tail.batches.ArrayCursor ArrayCursor]]
  * implementation, which is `@specialized`. Using `BytesCursor` might
  * be desirable instead for `isInstanceOf` checks.
  */
final class BytesCursor(underlying: ArrayCursor[Byte]) extends BatchCursor[Byte] {
  def this(array: Array[Byte]) =
    this(new ArrayCursor(array))
  def this(array: Array[Byte], offset: Int, length: Int) =
    this(new ArrayCursor(array, offset, length))

  override def hasNext(): Boolean = underlying.hasNext()
  override def next(): Byte = underlying.next()

  override def recommendedBatchSize: Int = underlying.recommendedBatchSize
  override def toIterator: Iterator[Byte] = underlying.toIterator

  override def map[B](f: Byte => B): ArrayCursor[B] =
    underlying.map(f)
  override def collect[B](pf: PartialFunction[Byte, B]): ArrayCursor[B] =
    underlying.collect(pf)

  override def take(n: Int): BytesCursor =
    new BytesCursor(underlying.take(n))
  override def drop(n: Int): BytesCursor =
    new BytesCursor(underlying.drop(n))
  override def slice(from: Int, until: Int): BytesCursor =
    new BytesCursor(underlying.slice(from, until))
  override def filter(p: Byte => Boolean): BytesCursor =
    new BytesCursor(underlying.filter(p))
}
