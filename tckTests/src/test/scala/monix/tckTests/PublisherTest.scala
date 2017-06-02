/*
 * Copyright (c) 2014-2017 by The Monix Project Developers.
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

package monix.tckTests

import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable
import org.reactivestreams.Publisher
import org.reactivestreams.tck.{PublisherVerification, TestEnvironment}
import org.scalatest.testng.TestNGSuiteLike

class PublisherTest
  extends PublisherVerification[Long](new TestEnvironment(3000))
  with TestNGSuiteLike {

  def createPublisher(elements: Long): Publisher[Long] = {
    if (elements == Long.MaxValue)
      Observable.repeat(1L)
        .flatMap(x => Observable.fork(Observable.now(x)))
        .toReactivePublisher
    else
      Observable.range(0, elements)
        .flatMap(x => Observable.fork(Observable.now(x)))
        .toReactivePublisher
  }

  def createFailedPublisher(): Publisher[Long] = {
    Observable.raiseError(new RuntimeException("dummy"))
      .asInstanceOf[Observable[Long]]
      .toReactivePublisher
  }
}