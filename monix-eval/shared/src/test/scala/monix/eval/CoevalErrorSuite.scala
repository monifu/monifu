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

import monix.execution.exceptions.DummyException
import scala.concurrent.TimeoutException
import scala.util.{Failure, Success}

object CoevalErrorSuite extends BaseTestSuite {
  test("Coeval.attempt should expose error") { implicit s =>
    val dummy = DummyException("ex")
    val r = Coeval.raiseError[Int](dummy).attempt.value
    assertEquals(r, Left(dummy))
  }

  test("Coeval.attempt should expose successful value") { implicit s =>
    val r = Coeval.now(10).attempt.value
    assertEquals(r, Right(10))
  }

  test("Coeval.fail should expose error") { implicit s =>
    val dummy = DummyException("dummy")
    val r = Coeval.raiseError[Int](dummy).failed.value
    assertEquals(r, dummy)
  }

  test("Coeval.fail should fail for successful values") { implicit s =>
    intercept[NoSuchElementException] {
      Coeval.now(10).failed.value
    }
  }

  test("Coeval.now.materialize") { implicit s =>
    assertEquals(Coeval.now(10).materialize.value, Success(10))
  }

  test("Coeval.error.materialize") { implicit s =>
    val dummy = DummyException("dummy")
    assertEquals(Coeval.raiseError[Int](dummy).materialize.value, Failure(dummy))
  }

  test("Coeval.evalOnce.materialize") { implicit s =>
    assertEquals(Coeval.evalOnce(10).materialize.value, Success(10))
  }

  test("Coeval.eval.materialize") { implicit s =>
    assertEquals(Coeval.eval(10).materialize.value, Success(10))
  }

  test("Coeval.defer.materialize") { implicit s =>
    assertEquals(Coeval.defer(Coeval.now(10)).materialize.value, Success(10))
  }

  test("Coeval.defer.flatMap.materialize") { implicit s =>
    assertEquals(Coeval.defer(Coeval.now(10)).flatMap(Coeval.now).materialize.value, Success(10))
  }

  test("Coeval.error.materialize") { implicit s =>
    val dummy = DummyException("dummy")
    assertEquals(Coeval.raiseError[Int](dummy).materialize.value, Failure(dummy))
  }

  test("Coeval.flatMap.materialize") { implicit s =>
    assertEquals(Coeval.eval(10).flatMap(x => Coeval.now(x))
      .materialize.runTry, Success(Success(10)))
  }

  test("Coeval.now.flatMap(error).materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val r = Coeval.now(10).flatMap[Int](_ => throw dummy).materialize
    assertEquals(r.runTry, Success(Failure(dummy)))
  }

  test("Coeval.defer(error).materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val f = Coeval.defer[Int](throw dummy).materialize
    assertEquals(f.runTry, Success(Failure(dummy)))
  }

  test("Coeval.defer(error).flatMap.materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val f = Coeval.defer[Int](throw dummy).flatMap(Coeval.now).materialize
    assertEquals(f.runTry, Success(Failure(dummy)))
  }

  test("Coeval.now.dematerialize") { implicit s =>
    val result = Coeval.now(10).materialize.dematerialize.runTry
    assertEquals(result, Success(10))
  }

  test("Coeval.error.dematerialize") { implicit s =>
    val dummy = DummyException("dummy")
    val result = Coeval.raiseError[Int](dummy).materialize.dematerialize.runTry
    assertEquals(result, Failure(dummy))
  }

  test("Coeval#onErrorRecover should mirror source on success") { implicit s =>
    val coeval = Coeval(1).onErrorRecover { case _: Throwable => 99 }
    assertEquals(coeval.runTry, Success(1))
  }

  test("Coeval#onErrorRecover should recover") { implicit s =>
    val ex = DummyException("dummy")
    val coeval = Coeval[Int](if (1 == 1) throw ex else 1).onErrorRecover {
      case _: DummyException => 99
    }

    assertEquals(coeval.runTry, Success(99))
  }

  test("Coeval#onErrorRecover should protect against user code") { implicit s =>
    val ex1 = DummyException("one")
    val ex2 = DummyException("two")

    val coeval = Coeval[Int](if (1 == 1) throw ex1 else 1)
      .onErrorRecover { case _ => throw ex2 }

    assertEquals(coeval.runTry, Failure(ex2))
  }

  test("Coeval#onErrorHandle should mirror source on success") { implicit s =>
    val f = Coeval(1).onErrorHandle { _: Throwable => 99 }
    assertEquals(f.runTry, Success(1))
  }

  test("Coeval#onErrorHandle should recover") { implicit s =>
    val ex = DummyException("dummy")
    val f = Coeval[Int](if (1 == 1) throw ex else 1)
      .onErrorHandle { case _: DummyException => 99 }

    assertEquals(f.runTry, Success(99))
  }

  test("Coeval#onErrorHandle should protect against user code") { implicit s =>
    val ex1 = DummyException("one")
    val ex2 = DummyException("two")
    val f = Coeval[Int](if (1 == 1) throw ex1 else 1)
      .onErrorHandle { _ => throw ex2 }

    assertEquals(f.runTry, Failure(ex2))
  }

  test("Coeval.onErrorFallbackTo should mirror source onSuccess") { implicit s =>
    val f = Coeval(1).onErrorFallbackTo(Coeval(2))
    assertEquals(f.runTry, Success(1))
  }

  test("Coeval.onErrorFallbackTo should fallback to backup onError") { implicit s =>
    val ex = DummyException("dummy")
    val f = Coeval(throw ex).onErrorFallbackTo(Coeval(2))
    assertEquals(f.runTry, Success(2))
  }

  test("Coeval.onErrorFallbackTo should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val err = DummyException("unexpected")
    val f = Coeval(throw ex).onErrorFallbackTo(Coeval.defer(throw err))
    assertEquals(f.runTry, Failure(err))
  }

  test("Coeval.onErrorRestart should mirror the source onSuccess") { implicit s =>
    var tries = 0
    val f = Coeval.eval { tries += 1; 1 }.onErrorRestart(10)
    assertEquals(f.runTry, Success(1))
    assertEquals(tries, 1)
  }

  test("Coeval.onErrorRestart should retry onError") { implicit s =>
    val ex = DummyException("dummy")
    var tries = 0
    val f = Coeval.eval { tries += 1; if (tries < 5) throw ex else 1 }.onErrorRestart(10)

    assertEquals(f.runTry, Success(1))
    assertEquals(tries, 5)
  }

  test("Coeval.onErrorRestart should emit onError after max retries") { implicit s =>
    val ex = DummyException("dummy")
    var tries = 0
    val f = Coeval.eval { tries += 1; throw ex }.onErrorRestart(10)

    assertEquals(f.runTry, Failure(ex))
    assertEquals(tries, 11)
  }

  test("Coeval.onErrorRestartIf should mirror the source onSuccess") { implicit s =>
    var tries = 0
    val f = Coeval.eval { tries += 1; 1 }.onErrorRestartIf(_ => tries < 10)
    assertEquals(f.runTry, Success(1))
    assertEquals(tries, 1)
  }

  test("Coeval.onErrorRestartIf should retry onError") { implicit s =>
    val ex = DummyException("dummy")
    var tries = 0
    val f = Coeval.eval { tries += 1; if (tries < 5) throw ex else 1 }
      .onErrorRestartIf(_ => tries <= 10)

    assertEquals(f.runTry, Success(1))
    assertEquals(tries, 5)
  }

  test("Coeval.onErrorRestartIf should emit onError") { implicit s =>
    val ex = DummyException("dummy")
    var tries = 0
    val f = Coeval.eval { tries += 1; throw ex }
      .onErrorRestartIf(_ => tries <= 10)

    assertEquals(f.runTry, Failure(ex))
    assertEquals(tries, 11)
  }

  test("Coeval#onErrorRecoverWith should mirror source on success") { implicit s =>
    val f = Coeval(1).onErrorRecoverWith { case _: Throwable => Coeval(99) }
    assertEquals(f.runTry, Success(1))
  }

  test("Coeval#onErrorRecoverWith should recover") { implicit s =>
    val ex = DummyException("dummy")
    val f = Coeval[Int](throw ex).onErrorRecoverWith {
      case _: DummyException => Coeval(99)
    }

    assertEquals(f.runTry, Success(99))
  }

  test("Coeval#onErrorRecoverWith should protect against user code") { implicit s =>
    val ex1 = DummyException("one")
    val ex2 = DummyException("two")

    val f = Coeval[Int](throw ex1)
      .onErrorRecoverWith { case _ => throw ex2 }

    assertEquals(f.runTry, Failure(ex2))
  }

  test("Coeval#onErrorRecover should emit error if not matches") { implicit s =>
    val dummy = DummyException("dummy")
    val f = Coeval[Int](throw dummy).onErrorRecover { case _: TimeoutException => 10 }
    assertEquals(f.runTry, Failure(dummy))
  }

  test("Coeval#onErrorRecoverWith should emit error if not matches") { implicit s =>
    val dummy = DummyException("dummy")
    val f = Coeval[Int](throw dummy).onErrorRecoverWith { case _: TimeoutException => Coeval.now(10) }
    assertEquals(f.runTry, Failure(dummy))
  }


  test("Coeval.onErrorRestartLoop works for success") { implicit s =>
    val dummy = DummyException("dummy")
    var tries = 0
    val source = Coeval.eval {
      tries += 1
      if (tries < 5) throw dummy
      tries
    }

    val coeval = source.onErrorRestartLoop(10) { (err, maxRetries, retry) =>
      if (maxRetries > 0)
        retry(maxRetries - 1)
      else
        Coeval.raiseError(err)
    }

    assertEquals(coeval.runTry, Success(5))
    assertEquals(tries, 5)
  }

  test("Coeval.onErrorRestartLoop can rethrow") { implicit s =>
    val dummy = DummyException("dummy")
    val source = Coeval.eval[Int] { throw dummy }

    val coeval = source.onErrorRestartLoop(10) { (err, maxRetries, retry) =>
      if (maxRetries > 0)
        retry(maxRetries - 1)
      else
        Coeval.raiseError(err)
    }

    assertEquals(coeval.runTry, Failure(dummy))
  }
}
