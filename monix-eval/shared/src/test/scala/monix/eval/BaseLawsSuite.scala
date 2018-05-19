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

import scala.util.{Either, Success, Try}
import cats.Eq
import cats.effect.{Async, IO}
import cats.effect.laws.discipline.arbitrary.{catsEffectLawsArbitraryForIO, catsEffectLawsCogenForIO}
import monix.execution.Cancelable
import monix.execution.atomic.Atomic
import monix.execution.schedulers.TestScheduler
import org.scalacheck.{Arbitrary, Cogen, Gen}
import org.scalacheck.Arbitrary.{arbitrary => getArbitrary}

/**
  * Base trait to inherit in all `monix-eval` tests that use ScalaCheck.
  */
trait BaseLawsSuite extends monix.execution.BaseLawsSuite with ArbitraryInstances

trait ArbitraryInstances extends ArbitraryInstancesBase {
  implicit def equalityTask[A](implicit A: Eq[A], ec: TestScheduler): Eq[Task[A]] =
    new Eq[Task[A]] {
      def eqv(lh: Task[A], rh: Task[A]): Boolean =
        equalityFuture(A, ec).eqv(lh.runAsync, rh.runAsync)
    }

  implicit def equalityTaskPar[A](implicit A: Eq[A], ec: TestScheduler): Eq[Task.Par[A]] =
    new Eq[Task.Par[A]] {
      import Task.Par.unwrap
      def eqv(lh: Task.Par[A], rh: Task.Par[A]): Boolean =
        Eq[Task[A]].eqv(unwrap(lh), unwrap(rh))
    }

  implicit def equalityIO[A](implicit A: Eq[A], ec: TestScheduler): Eq[IO[A]] =
    new Eq[IO[A]] {
      def eqv(x: IO[A], y: IO[A]): Boolean =
        equalityFuture[A].eqv(x.unsafeToFuture(), y.unsafeToFuture())
    }
}

trait ArbitraryInstancesBase extends monix.execution.ArbitraryInstances {
  implicit def arbitraryCoeval[A](implicit A: Arbitrary[A]): Arbitrary[Coeval[A]] =
    Arbitrary {
      for {
        a <- A.arbitrary
        coeval <- Gen.oneOf(
          Coeval.now(a),
          Coeval.evalOnce(a),
          Coeval.eval(a),
          Coeval.unit.map(_ => a),
          Coeval.unit.flatMap(_ => Coeval.now(a)))
      } yield coeval
    }

  implicit def arbitraryTask[A : Arbitrary : Cogen]: Arbitrary[Task[A]] = {
    def genPure: Gen[Task[A]] =
      getArbitrary[A].map(Task.pure)

    def genEvalAsync: Gen[Task[A]] =
      getArbitrary[A].map(Task.evalAsync(_))

    def genEval: Gen[Task[A]] =
      Gen.frequency(
        1 -> getArbitrary[A].map(Task.eval(_)),
        1 -> getArbitrary[A].map(Task(_))
      )

    def genFail: Gen[Task[A]] =
      getArbitrary[Throwable].map(Task.raiseError)

    def genAsync: Gen[Task[A]] =
      getArbitrary[(Either[Throwable, A] => Unit) => Unit].map(Async[Task].async)

    def genCancelable: Gen[Task[A]] =
      for (a <- getArbitrary[A]) yield
        Task.cancelableS[A] { (sc, cb) =>
          val isActive = Atomic(true)
          sc.executeAsync { () =>
            if (isActive.getAndSet(false))
              cb.onSuccess(a)
          }
          Cancelable(() => isActive.set(false))
        }

    def genNestedAsync: Gen[Task[A]] =
      getArbitrary[(Either[Throwable, Task[A]] => Unit) => Unit]
        .map(k => Async[Task].async(k).flatMap(x => x))

    def genBindSuspend: Gen[Task[A]] =
      getArbitrary[A].map(Task.evalAsync(_).flatMap(Task.pure))

    def genSimpleTask = Gen.frequency(
      1 -> genPure,
      1 -> genEval,
      1 -> genEvalAsync,
      1 -> genFail,
      1 -> genAsync,
      1 -> genNestedAsync,
      1 -> genBindSuspend
    )

    def genFlatMap: Gen[Task[A]] =
      for {
        ioa <- genSimpleTask
        f <- getArbitrary[A => Task[A]]
      } yield ioa.flatMap(f)

    def getMapOne: Gen[Task[A]] =
      for {
        ioa <- genSimpleTask
        f <- getArbitrary[A => A]
      } yield ioa.map(f)

    def getMapTwo: Gen[Task[A]] =
      for {
        ioa <- genSimpleTask
        f1 <- getArbitrary[A => A]
        f2 <- getArbitrary[A => A]
      } yield ioa.map(f1).map(f2)

    Arbitrary(Gen.frequency(
      5 -> genPure,
      5 -> genEvalAsync,
      5 -> genEval,
      1 -> genFail,
      5 -> genCancelable,
      5 -> genBindSuspend,
      5 -> genAsync,
      5 -> genNestedAsync,
      5 -> getMapOne,
      5 -> getMapTwo,
      10 -> genFlatMap))
  }

  implicit def arbitraryTaskPar[A : Arbitrary : Cogen]: Arbitrary[Task.Par[A]] =
    Arbitrary(arbitraryTask[A].arbitrary.map(Task.Par(_)))

  implicit def arbitraryIO[A : Arbitrary : Cogen]: Arbitrary[IO[A]] =
    catsEffectLawsArbitraryForIO

  implicit def arbitraryExToA[A](implicit A: Arbitrary[A]): Arbitrary[Throwable => A] =
    Arbitrary {
      val fun = implicitly[Arbitrary[Int => A]]
      for (f <- fun.arbitrary) yield (t: Throwable) => f(t.hashCode())
    }

  implicit def arbitraryPfExToA[A](implicit A: Arbitrary[A]): Arbitrary[PartialFunction[Throwable, A]] =
    Arbitrary {
      val fun = implicitly[Arbitrary[Int => A]]
      for (f <- fun.arbitrary) yield PartialFunction((t: Throwable) => f(t.hashCode()))
    }

  implicit def arbitraryCoevalToLong[A, B](implicit A: Arbitrary[A], B: Arbitrary[B]): Arbitrary[Coeval[A] => B] =
    Arbitrary {
      for (b <- B.arbitrary) yield (_: Coeval[A]) => b
    }

  implicit def arbitraryTaskToLong[A, B](implicit A: Arbitrary[A], B: Arbitrary[B]): Arbitrary[Task[A] => B] =
    Arbitrary {
      for (b <- B.arbitrary) yield (_: Task[A]) => b
    }

  implicit def arbitraryIOToLong[A, B](implicit A: Arbitrary[A], B: Arbitrary[B]): Arbitrary[IO[A] => B] =
    Arbitrary {
      for (b <- B.arbitrary) yield (_: IO[A]) => b
    }

  implicit def equalityCoeval[A](implicit A: Eq[A]): Eq[Coeval[A]] =
    new Eq[Coeval[A]] {
      def eqv(lh: Coeval[A], rh: Coeval[A]): Boolean = {
        Eq[Try[A]].eqv(lh.runTry(), rh.runTry())
      }
    }

  implicit def cogenForTask[A]: Cogen[Task[A]] =
    Cogen[Unit].contramap(_ => ())

  implicit def cogenForIO[A : Cogen]: Cogen[IO[A]] =
    catsEffectLawsCogenForIO

  implicit def cogenForCoeval[A](implicit cga: Cogen[A]): Cogen[Coeval[A]] =
    Cogen { (seed, coeval) =>
      coeval.runTry() match {
        case Success(a) => cga.perturb(seed, a)
        case _ => seed
      }
    }
}
