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

package monix.eval

import minitest.SimpleTestSuite
import monix.eval.Task.Options
import scala.concurrent.Promise

object TaskAppSuite extends SimpleTestSuite {
  test("runl works") {
    var wasExecuted = false
    val app = new TaskApp {
      override def runl(args: List[String]) =
        Task.evalAsync { wasExecuted = args.headOption.getOrElse("unknown") == "true" }
    }

    app.main(Array("true"))
    assert(wasExecuted, "wasExecuted")
  }

  test("runc works") {
    var wasExecuted = false
    val app = new TaskApp {
      override def runc =
        Task.evalAsync { wasExecuted = true }
    }

    app.main(Array.empty)
    assert(wasExecuted, "wasExecuted")
  }

  testAsync("options are configurable") {
    import monix.execution.Scheduler.Implicits.global

    val opts = Task.defaultOptions
    assert(!opts.localContextPropagation, "!opts.localContextPropagation")
    val opts2 = opts.enableLocalContextPropagation
    assert(opts2.localContextPropagation, "opts2.localContextPropagation")

    val p = Promise[Options]()
    val exposeOpts =
      Task.Async[Task.Options] { (ctx, cb) =>
        cb.onSuccess(ctx.options)
      }

    val app = new TaskApp {
      override val options = opts2
      override def runc =
        exposeOpts.map { x => p.success(x) }
    }

    app.main(Array.empty)
    for (r <- p.future) yield {
      assertEquals(r, opts2)
    }
  }
}
