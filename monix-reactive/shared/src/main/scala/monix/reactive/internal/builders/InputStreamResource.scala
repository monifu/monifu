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

package monix.reactive.internal.builders

import java.io.{IOException, InputStream}

import cats.effect.Resource
import monix.eval.Task
import monix.execution.Ack.Continue
import monix.execution.{AsyncVar, Cancelable, Scheduler}
import monix.reactive.Observable
import monix.reactive.internal.builders.InputStreamResource.{Bytes, Completed, Failed, ObservableMessage}

import scala.annotation.tailrec
import scala.concurrent.{Await, blocking}
import scala.concurrent.duration.Duration

private[reactive] class InputStreamResource(observable: Observable[Array[Byte]], waitForNextElement: Duration) {

  def toResource: Resource[Task, InputStream] = {
    Resource.fromAutoCloseable(
      Task.create[InputStream] { (s, cb) =>
        val (state, cancelable) = subscribe(s)
        cb.onSuccess(new ObservableInputStream(state, cancelable))
      }
    )
  }

  private def subscribe(implicit s: Scheduler): (AsyncVar[ObservableMessage], Cancelable) = {
    val state = AsyncVar.empty[ObservableMessage]()

    val cancelable = observable
      .executeAsync
      .doOnError { e =>
        Task.deferFuture {
          state.put(Failed(e))
        }
      }
      .doOnComplete {
        Task.deferFuture {
          state.put(Completed)
        }
      }
      .subscribe(array => state.put(Bytes(array)).map(_ => Continue))

    (state, cancelable)
  }

  private[this] class ObservableInputStream(queue: AsyncVar[ObservableMessage], cancelable: Cancelable) extends InputStream {
    private[this] var buffer: Array[Byte] = new Array[Byte](0)
    private[this] var isClosed: Boolean = false

    override def read(): Int = {
      val a = new Array[Byte](1)
      read(a, 0, 1) match {
        case 1 => a(0) & 0xff
        case -1 => -1
        case len => throw new IllegalStateException(s"There was '1' byte expected, but there were '$len' bytes read")
      }
    }

    override def read(arr: Array[Byte], begin: Int, length: Int): Int = {
      require(arr.length > 0, "array size must be >= 0")
      require(begin >= 0, "begin must be >= 0")
      require(length > 0, "length must be > 0")
      require(begin + length <= arr.length, "begin + length must be smaller or equal to the array length")

      if (isClosed) -1
      else {
        val availableBytes = ensureBufferSize(length)
        val bytesWritten = if (availableBytes == length) {
          buffer.copyToArray(arr, begin, availableBytes)
          length
        } else if (availableBytes > 0) {
          buffer.copyToArray(arr, begin, availableBytes)
          isClosed = true
          availableBytes
        } else {
          isClosed = true
          -1
        }
        buffer = buffer.drop(availableBytes)
        bytesWritten
      }
    }

    @tailrec
    private def ensureBufferSize(requiredLength: Int): Int = {
      if (buffer.length >= requiredLength) {
        requiredLength
      } else {
        takeNextElement() match {
          case Bytes(polledArray) =>
            buffer ++= polledArray
            ensureBufferSize(requiredLength)
          case Completed =>
            buffer.length
          case Failed(e) =>
            throw new IOException(e)
        }
      }
    }

    private def takeNextElement(): ObservableMessage = {
      try {
        blocking {
          Await.result(queue.take(), waitForNextElement)
        }
      } catch {
        case e: Throwable => throw new IOException(e)
      }
    }

    override def close(): Unit = {
      cancelable.cancel()
      isClosed = true
    }

  }

}

private[builders] object InputStreamResource {

  sealed trait ObservableMessage

  final case class Bytes(array: Array[Byte]) extends ObservableMessage

  final case object Completed extends ObservableMessage

  final case class Failed(e: Throwable) extends ObservableMessage

}
