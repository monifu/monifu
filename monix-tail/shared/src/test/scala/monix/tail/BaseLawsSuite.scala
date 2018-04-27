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

import cats.effect.laws.discipline.{Parameters => EffectParameters}
import minitest.SimpleTestSuite
import minitest.api.IgnoredException
import minitest.laws.Checkers
import monix.execution.internal.Platform
import monix.execution.schedulers.TestScheduler
import org.scalacheck.Prop
import org.scalacheck.Test.Parameters
import org.typelevel.discipline.Laws

import scala.concurrent.duration._

/** Just a marker for what we need to extend in the tests
  * of `monix-tail`.
  */
trait BaseLawsSuite extends SimpleTestSuite with Checkers with ArbitraryInstances {
  override lazy val checkConfig: Parameters =
    Parameters.default
      .withMinSuccessfulTests(if (Platform.isJVM) 100 else 10)
      .withMaxDiscardRatio(if (Platform.isJVM) 5.0f else 50.0f)
      .withMaxSize(24)

  lazy val slowConfig: Parameters =
    Parameters.default
      .withMinSuccessfulTests(10)
      .withMaxDiscardRatio(50.0f)
      .withMaxSize(6)

  // Stack-safety tests are very taxing, so reducing burden
  implicit val effectParams = EffectParameters(
    stackSafeIterationsCount = {
      if (Platform.isJS || System.getenv("TRAVIS") == "true" || System.getenv("CI") == "true")
        100
      else
        1000
    })

  def checkAllAsync(name: String, config: Parameters = checkConfig)
    (f: TestScheduler => Laws#RuleSet): Unit = {

    val s = TestScheduler()
    var catchErrors = true
    try {
      val ruleSet = f(s)
      catchErrors = false

      for ((id, prop: Prop) ← ruleSet.all.properties)
        test(s"$name.$id") {
          s.tick(1.day)
          check(prop, config)
        }
    } catch {
      case e: IgnoredException if catchErrors =>
        test(name) { throw e }
    }
  }

  val emptyRuleSet: Laws#RuleSet =
    new Laws { val ref = new DefaultRuleSet("dummy", None) }.ref
}
