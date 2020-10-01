/*
 * Copyright (c) 2014-2020 by The Monix Project Developers.
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

package monix.eval

object CoevalLeftSuite extends BaseTestSuite {
  test("Coeval.left should return a Now with a Left") { implicit s =>
    val t = Coeval.left[Int, String](1)

    t.value() match {
      case Left(_: Int) =>
      case _ => fail("Expected Coeval with a Left")
    }
  }
}
