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

package monix.reactive.internal.operators

import monix.reactive.Observable
import scala.concurrent.duration._
import scala.concurrent.duration.Duration.Zero

object Zip6Suite extends BaseOperatorSuite {
  def createObservable(sourceCount: Int) = Some {
    val o1 = Observable.fork(Observable.range(0, sourceCount))
    val o2 = Observable.fork(Observable.range(0, sourceCount))
    val o3 = Observable.fork(Observable.range(0, sourceCount))
    val o4 = Observable.fork(Observable.range(0, sourceCount))
    val o5 = Observable.fork(Observable.range(0, sourceCount))
    val o6 = Observable.fork(Observable.range(0, sourceCount))

    val o = Observable.zipMap6(o1,o2,o3,o4,o5,o6)(_+_+_+_+_+_)
    Sample(o, count(sourceCount), sum(sourceCount), Zero, Zero)
  }

  def count(sourceCount: Int) = sourceCount
  def sum(sourceCount: Int) = (sourceCount * (sourceCount - 1)) / 2 * 6

  def observableInError(sourceCount: Int, ex: Throwable) = Some {
    val o1 = createObservableEndingInError(Observable.range(0, sourceCount), ex)
    val o2 = createObservableEndingInError(Observable.range(0, sourceCount), ex)
    val o3 = createObservableEndingInError(Observable.range(0, sourceCount), ex)
    val o4 = createObservableEndingInError(Observable.range(0, sourceCount), ex)
    val o5 = createObservableEndingInError(Observable.range(0, sourceCount), ex)
    val o6 = createObservableEndingInError(Observable.range(0, sourceCount), ex)

    val o = Observable.zipMap6(o1,o2,o3,o4,o5,o6)(_+_+_+_+_+_)
    Sample(o, count(sourceCount - 1), sum(sourceCount - 1), Zero, Zero)
  }

  def brokenUserCodeObservable(sourceCount: Int, ex: Throwable) = Some {
    val o1 = Observable.fork(Observable.range(0, sourceCount))
    val o2 = Observable.fork(Observable.range(0, sourceCount + 100))
    val o3 = Observable.fork(Observable.range(0, sourceCount))
    val o4 = Observable.fork(Observable.range(0, sourceCount))
    val o5 = Observable.fork(Observable.range(0, sourceCount))
    val o6 = Observable.fork(Observable.range(0, sourceCount))

    val o = Observable.zipMap6(o1, o2, o3, o4, o5,o6) { (x1, x2, x3, x4, x5, x6) =>
      if (x2 < sourceCount - 1) x1 + x2 + x3 + x4 + x5 + x6
      else throw ex
    }

    Sample(o, count(sourceCount - 1), sum(sourceCount - 1), Zero, Zero)
  }

  override def cancelableObservables(): Seq[Sample] = {
    val sample1 = {
      val o1 = Observable.range(0, 10).delayOnNext(1.second)
      val o2 = Observable.range(0, 10).delayOnNext(1.second)
      val o3 = Observable.range(0, 10).delayOnNext(1.second)
      val o4 = Observable.range(0, 10).delayOnNext(1.second)
      val o5 = Observable.range(0, 10).delayOnNext(1.second)
      val o6 = Observable.range(0, 10).delayOnNext(1.second)
      Observable.zipMap6(o1,o2,o3,o4,o5,o6)(_+_+_+_+_+_)
    }

    Seq(Sample(sample1, 0, 0, 0.seconds, 0.seconds))
  }
}