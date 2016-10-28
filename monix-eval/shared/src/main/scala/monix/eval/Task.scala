/*
 * Copyright (c) 2014-2016 by its authors. Some rights reserved.
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

import monix.types._
import monix.eval.Coeval.{Attempt, Error, Now}
import monix.eval.Task._
import monix.execution.Ack.Stop
import monix.execution.atomic.{Atomic, AtomicAny, PaddingStrategy}
import monix.execution.cancelables.{CompositeCancelable, SingleAssignmentCancelable, StackedCancelable}
import monix.execution.rstreams.Subscription
import monix.execution.schedulers.ExecutionModel
import monix.execution.{Cancelable, CancelableFuture, Scheduler}
import org.reactivestreams.Subscriber

import scala.annotation.tailrec
import scala.collection.generic.CanBuildFrom
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future, Promise, TimeoutException}
import scala.util.control.NonFatal
import scala.util.{Failure, Sorting, Success, Try}

/** `Task` represents a specification for a possibly lazy or
  * asynchronous computation, which when executed will produce
  * an `A` as a result, along with possible side-effects.
  *
  * Compared with `Future` from Scala's standard library, `Task` does
  * not represent a running computation or a value detached from time,
  * as `Task` does not execute anything when working with its builders
  * or operators and it does not submit any work into any thread-pool,
  * the execution eventually taking place only after `runAsync`
  * is called and not before that.
  *
  * Note that `Task` is conservative in how it spawns logical threads.
  * Transformations like `map` and `flatMap` for example will default
  * to being executed on the logical thread on which the asynchronous
  * computation was started. But one shouldn't make assumptions about
  * how things will end up executed, as ultimately it is the
  * implementation's job to decide on the best execution model. All
  * you are guaranteed is asynchronous execution after executing
  * `runAsync`.
  */
sealed abstract class Task[+A] extends Serializable { self =>
  /** Triggers the asynchronous execution.
    *
    * @param cb is a callback that will be invoked upon completion.
    * @return a [[monix.execution.Cancelable Cancelable]] that can
    *         be used to cancel a running task
    */
  def runAsync(cb: Callback[A])(implicit s: Scheduler): Cancelable = {
    val conn = StackedCancelable()
    Task.unsafeStartNow[A](this, s, conn, Callback.safe(cb))
    conn
  }

  /** Triggers the asynchronous execution.
    *
    * @param f is a callback that will be invoked upon completion.
    * @return a [[monix.execution.Cancelable Cancelable]] that can
    *         be used to cancel a running task
    */
  def runAsync(f: Try[A] => Unit)(implicit s: Scheduler): Cancelable =
    runAsync(new Callback[A] {
      def onSuccess(value: A): Unit = f(Success(value))
      def onError(ex: Throwable): Unit = f(Failure(ex))
    })

  /** Triggers the asynchronous execution.
    *
    * @return a [[monix.execution.CancelableFuture CancelableFuture]]
    *         that can be used to extract the result or to cancel
    *         a running task.
    */
  def runAsync(implicit s: Scheduler): CancelableFuture[A] =
    Task.startTrampolineForFuture(s, this, Nil)

  /** Transforms a [[Task]] into a [[Coeval]] that tries to
    * execute the source synchronously, returning either `Right(value)`
    * in case a value is available immediately, or `Left(future)` in case
    * we have an asynchronous boundary.
    */
  def coeval(implicit s: Scheduler): Coeval[Either[CancelableFuture[A], A]] =
    Coeval.eval {
      val f = this.runAsync(s)
      f.value match {
        case None => Left(f)
        case Some(Success(a)) => Right(a)
        case Some(Failure(ex)) => throw ex
      }
    }

  /** Creates a new Task by applying a function to the successful result
    * of the source Task, and returns a task equivalent to the result
    * of the function.
    */
  def flatMap[B](f: A => Task[B]): Task[B] =
    self match {
      case Delay(coeval) =>
        Suspend(() => try f(coeval.value) catch { case NonFatal(ex) => raiseError(ex) })
      case Suspend(thunk) =>
        BindSuspend(thunk, f)
      case ref: MemoizeSuspend[_] =>
        val task = ref.asInstanceOf[MemoizeSuspend[A]]
        BindSuspend(() => task, f)
      case BindSuspend(thunk, g) =>
        Suspend(() => BindSuspend(thunk, g andThen (_ flatMap f)))
      case Async(onFinish) =>
        BindAsync(onFinish, f)
      case BindAsync(listen, g) =>
        Suspend(() => BindAsync(listen, g andThen (_ flatMap f)))
    }

  /** Given a source Task that emits another Task, this function
    * flattens the result, returning a Task equivalent to the emitted
    * Task by the source.
    */
  def flatten[B](implicit ev: A <:< Task[B]): Task[B] =
    flatMap(a => a)

  /** Returns a task that waits for the specified `timespan` before
    * executing and mirroring the result of the source.
    */
  def delayExecution(timespan: FiniteDuration): Task[A] =
    Async { (scheduler, conn, cb) =>
      implicit val s = scheduler
      val c = SingleAssignmentCancelable()
      conn push c

      c := s.scheduleOnce(timespan.length, timespan.unit, new Runnable {
        def run(): Unit = {
          conn.pop()
          Task.startTrampolineRunLoop(scheduler, conn, self, Callback.async(cb), Nil)
        }
      })
    }

  /** Returns a task that waits for the specified `trigger` to succeed
    * before mirroring the result of the source.
    *
    * If the `trigger` ends in error, then the resulting task will also
    * end in error.
    */
  def delayExecutionWith(trigger: Task[Any]): Task[A] =
    Async { (scheduler, conn, cb) =>
      implicit val s = scheduler

      // Async boundary forced, prevents stack-overflows
      Task.unsafeStartAsync(trigger, scheduler, conn, new Callback[Any] {
        def onSuccess(value: Any): Unit =
          Task.unsafeStartAsync(self, scheduler, conn, Callback.async(cb))
        def onError(ex: Throwable): Unit =
          cb.asyncOnError(ex)
      })
    }

  /** Returns a task that executes the source immediately on `runAsync`,
    * but before emitting the `onSuccess` result for the specified
    * duration.
    *
    * Note that if an error happens, then it is streamed immediately
    * with no delay.
    */
  def delayResult(timespan: FiniteDuration): Task[A] =
    Async { (scheduler, conn, cb) =>
      implicit val s = scheduler

      Task.unsafeStartAsync(self, scheduler, conn, new Callback[A] {
        def onSuccess(value: A): Unit = {
          val task = SingleAssignmentCancelable()
          conn push task

          // Delaying result
          task := scheduler.scheduleOnce(timespan.length, timespan.unit,
            new Runnable {
              def run(): Unit = {
                conn.pop()
                cb.asyncOnSuccess(value)
              }
            })
        }

        def onError(ex: Throwable): Unit =
          cb.asyncOnError(ex)
      })
    }

  /** Returns a task that executes the source immediately on `runAsync`,
    * but before emitting the `onSuccess` result for the specified
    * duration.
    *
    * Note that if an error happens, then it is streamed immediately
    * with no delay.
    */
  def delayResultBySelector[B](selector: A => Task[B]): Task[A] =
    Async { (scheduler, conn, cb) =>
      implicit val s = scheduler

      Task.unsafeStartAsync(self, scheduler, conn, new Callback[A] {
        def onSuccess(value: A): Unit = {
          var streamErrors = true
          try {
            val trigger = selector(value)
            streamErrors = false
            // Delaying result
            Task.unsafeStartAsync(trigger, scheduler, conn, new Callback[B] {
              def onSuccess(b: B): Unit = cb.asyncOnSuccess(value)
              def onError(ex: Throwable): Unit = cb.asyncOnError(ex)
            })
          } catch {
            case NonFatal(ex) if streamErrors =>
              cb.asyncOnError(ex)
          }
        }

        def onError(ex: Throwable): Unit =
          cb.asyncOnError(ex)
      })
    }


  /** Mirrors the given source `Task`, but upon execution ensure
    * that evaluation forks into a separate (logical) thread.
    *
    * The given [[monix.execution.Scheduler Scheduler]]  will be
    * used for execution of the [[Task]], effectively overriding the
    * `Scheduler` that's passed in `runAsync`. Thus you can
    * execute a whole `Task` on a separate thread-pool, useful for
    * example in case of doing I/O.
    *
    * NOTE: the logic one cares about won't necessarily end up
    * executed on the given scheduler, or for transformations
    * that happen from here on. It all depends on what overrides
    * or asynchronous boundaries happen. But this function
    * guarantees that the this `Task` run-loop begins executing
    * on the given scheduler.
    *
    * @param s is the scheduler to use for execution
    */
  def executeOn(s: Scheduler): Task[A] =
    Task.fork(this, s)

  /** Returns a failed projection of this task.
    *
    * The failed projection is a future holding a value of type
    * `Throwable`, emitting a value which is the throwable of the
    * original task in case the original task fails, otherwise if the
    * source succeeds, then it fails with a `NoSuchElementException`.
    */
  def failed: Task[Throwable] =
    materializeAttempt.flatMap {
      case Error(ex) => now(ex)
      case Now(_) => raiseError(new NoSuchElementException("failed"))
    }

  /** Returns a new Task that applies the mapping function to
    * the element emitted by the source.
    */
  def map[B](f: A => B): Task[B] =
    flatMap(a => try now(f(a)) catch { case NonFatal(ex) => raiseError(ex) })

  /** Returns a new `Task` in which `f` is scheduled to be run on completion.
    * This would typically be used to release any resources acquired by this
    * `Task`.
    *
    * The returned `Task` completes when both the source and the
    * task returned by `f` complete.
    */
  def doOnFinish(f: Option[Throwable] => Task[Unit]): Task[A] =
    materializeAttempt.flatMap {
      case Now(value) =>
        f(None).map(_ => value)
      case Error(ex) =>
        f(Some(ex)).flatMap(_ => raiseError(ex))
    }

  /** Creates a new [[Task]] that will expose any triggered error from
    * the source.
    */
  def materialize: Task[Try[A]] =
    materializeAttempt.map(_.asScala)

  /** Creates a new [[Task]] that will expose any triggered error from
    * the source.
    */
  def materializeAttempt: Task[Attempt[A]] = {
    self match {
      case Delay(coeval) =>
        Suspend(() => Delay(coeval.materializeAttempt))
      case Suspend(thunk) =>
        Suspend(() => try {
          thunk().materializeAttempt
        } catch { case NonFatal(ex) =>
          now(Error(ex))
        })
      case ref: MemoizeSuspend[_] =>
        val task = ref.asInstanceOf[MemoizeSuspend[A]]
        Async[Attempt[A]] { (s, conn, cb) =>
          Task.unsafeStartAsync[A](task, s, conn, new Callback[A] {
            def onSuccess(value: A): Unit = cb.asyncOnSuccess(Now(value))(s)
            def onError(ex: Throwable): Unit = cb.asyncOnSuccess(Error(ex))(s)
          })
        }
      case BindSuspend(thunk, g) =>
        BindSuspend[Attempt[Any], Attempt[A]](
          () => Suspend(() =>
            try thunk().materializeAttempt catch {
              case NonFatal(ex) => now(Error(ex))
            }),
          result => result match {
            case Now(any) =>
              try {
                g.asInstanceOf[Any => Task[A]](any)
                  .materializeAttempt
              } catch {
                case NonFatal(ex) =>
                  now(Error(ex))
              }
            case Error(ex) =>
              now(Error(ex))
          })
      case Async(onFinish) =>
        Async((s, conn, cb) =>
          s.executeTrampolined(() => onFinish(s, conn, new Callback[A] {
            def onSuccess(value: A): Unit = cb.asyncOnSuccess(Now(value))(s)
            def onError(ex: Throwable): Unit = cb.asyncOnSuccess(Error(ex))(s)
          })))

      case BindAsync(onFinish, g) =>
        BindAsync[Attempt[Any], Attempt[A]](
          (s, conn, cb) =>
            s.executeTrampolined(() => onFinish(s, conn, new Callback[Any] {
              def onSuccess(value: Any): Unit = cb.asyncOnSuccess(Now(value))(s)
              def onError(ex: Throwable): Unit = cb.asyncOnSuccess(Error(ex))(s)
            })),
          result => result match {
            case Now(any) =>
              try {
                g.asInstanceOf[Any => Task[A]](any)
                  .materializeAttempt
              }
              catch {
                case NonFatal(ex) =>
                  now(Error(ex))
              }
            case Error(ex) =>
              now(Error(ex))
          })
    }
  }

  /** Dematerializes the source's result from a `Try`. */
  def dematerialize[B](implicit ev: A <:< Try[B]): Task[B] =
    self.asInstanceOf[Task[Try[B]]].flatMap(fromTry)

  /** Dematerializes the source's result from an `Attempt`. */
  def dematerializeAttempt[B](implicit ev: A <:< Attempt[B]): Task[B] =
    self.asInstanceOf[Task[Attempt[B]]].flatMap(Delay.apply)

  /** Creates a new task that will try recovering from an error by
    * matching it with another task using the given partial function.
    *
    * See [[onErrorHandleWith]] for the version that takes a total function.
    */
  def onErrorRecoverWith[B >: A](pf: PartialFunction[Throwable, Task[B]]): Task[B] =
    onErrorHandleWith(ex => pf.applyOrElse(ex, raiseError))

  /** Creates a new task that will handle any matching throwable that
    * this task might emit by executing another task.
    *
    * See [[onErrorRecoverWith]] for the version that takes a partial function.
    */
  def onErrorHandleWith[B >: A](f: Throwable => Task[B]): Task[B] =
    self.materializeAttempt.flatMap {
      case now @ Now(_) => Delay(now)
      case Error(ex) => try f(ex) catch { case NonFatal(err) => raiseError(err) }
    }

  /** Creates a new task that in case of error will fallback to the
    * given backup task.
    */
  def onErrorFallbackTo[B >: A](that: Task[B]): Task[B] =
    onErrorHandleWith(ex => that)

  /** Given a predicate function, keep retrying the
    * task until the function returns true.
    */
  def restartUntil(p: (A) => Boolean): Task[A] =
    self.flatMap(a => if (p(a)) now(a) else self.restartUntil(p))

  /** Creates a new task that in case of error will retry executing the
    * source again and again, until it succeeds.
    *
    * In case of continuous failure the total number of executions
    * will be `maxRetries + 1`.
    */
  def onErrorRestart(maxRetries: Long): Task[A] =
    self.onErrorHandleWith(ex =>
      if (maxRetries > 0) self.onErrorRestart(maxRetries-1)
      else raiseError(ex))

  /** Creates a new task that in case of error will retry executing the
    * source again and again, until it succeeds.
    *
    * In case of continuous failure the total number of executions
    * will be `maxRetries + 1`.
    */
  def onErrorRestartIf(p: Throwable => Boolean): Task[A] =
    self.onErrorHandleWith(ex => if (p(ex)) self.onErrorRestartIf(p) else raiseError(ex))

  /** Creates a new task that will handle any matching throwable that
    * this task might emit.
    *
    * See [[onErrorRecover]] for the version that takes a partial function.
    */
  def onErrorHandle[U >: A](f: Throwable => U): Task[U] =
    onErrorHandleWith(ex => try now(f(ex)) catch { case NonFatal(err) => raiseError(err) })

  /** Creates a new task that on error will try to map the error
    * to another value using the provided partial function.
    *
    * See [[onErrorHandle]] for the version that takes a total function.
    */
  def onErrorRecover[U >: A](pf: PartialFunction[Throwable, U]): Task[U] =
    onErrorRecoverWith(pf.andThen(now))

  /** Memoizes the result on the computation and reuses it on subsequent
    * invocations of `runAsync`.
    */
  def memoize: Task[A] =
    self match {
      case Delay(value) => Delay(value.memoize)
      case memoized: MemoizeSuspend[_] => self
      case other => new MemoizeSuspend[A](() => other)
    }

  /** Converts a [[Task]] to an `org.reactivestreams.Publisher` that
    * emits a single item on success, or just the error on failure.
    *
    * See [[http://www.reactive-streams.org/ reactive-streams.org]] for the
    * Reactive Streams specification.
    */
  def toReactivePublisher[B >: A](implicit s: Scheduler): org.reactivestreams.Publisher[B] =
    new org.reactivestreams.Publisher[B] {
      def subscribe(out: Subscriber[_ >: B]): Unit = {
        out.onSubscribe(new Subscription {
          private[this] var isActive = true
          private[this] val conn = StackedCancelable()

          def request(n: Long): Unit = {
            require(n > 0, "n must be strictly positive, according to " +
              "the Reactive Streams contract, rule 3.9")

            if (isActive) Task.unsafeStartAsync[A](self, s, conn, Callback.safe(
              new Callback[A] {
                def onError(ex: Throwable): Unit =
                  out.onError(ex)

                def onSuccess(value: A): Unit = {
                  out.onNext(value)
                  out.onComplete()
                }
              }))
          }

          def cancel(): Unit = {
            isActive = false
            conn.cancel()
          }
        })
      }
    }

  /** Returns a Task that mirrors the source Task but that triggers a
    * `TimeoutException` in case the given duration passes without the
    * task emitting any item.
    */
  def timeout(after: FiniteDuration): Task[A] =
    timeoutTo(after, raiseError(new TimeoutException(s"Task timed-out after $after of inactivity")))

  /** Returns a Task that mirrors the source Task but switches to the
    * given backup Task in case the given duration passes without the
    * source emitting any item.
    */
  def timeoutTo[B >: A](after: FiniteDuration, backup: Task[B]): Task[B] =
    Task.chooseFirstOf(self, backup.delayExecution(after)).map {
      case Left(((a, futureB))) =>
        futureB.cancel()
        a
      case Right((futureA, b)) =>
        futureA.cancel()
        b
    }

  /** Zips the values of `this` and `that` task, and creates a new task
    * that will emit the tuple of their results.
    */
  def zip[B](that: Task[B]): Task[(A, B)] =
    Task.mapBoth(this, that)((a,b) => (a,b))

  /** Zips the values of `this` and `that` and applies the given
    * mapping function on their results.
    */
  def zipMap[B,C](that: Task[B])(f: (A,B) => C): Task[C] =
    Task.mapBoth(this, that)(f)
}

object Task extends TaskInstances {
  /** Returns a new task that, when executed, will emit the result of
    * the given function, executed asynchronously.
    *
    * @param f is the callback to execute asynchronously
    */
  def apply[A](f: => A): Task[A] =
    fork(eval(f))

  /** Returns a `Task` that on execution is always successful, emitting
    * the given strict value.
    */
  def now[A](a: A): Task[A] =
    Delay(Coeval.Now(a))

  /** Lifts a value into the task context. Alias for [[now]]. */
  def pure[A](a: A): Task[A] = now(a)

  /** Returns a task that on execution is always finishing in error
    * emitting the specified exception.
    */
  def raiseError[A](ex: Throwable): Task[A] =
    Delay(Coeval.Error(ex))

  /** Promote a non-strict value representing a Task to a Task of the
    * same type.
    */
  def defer[A](fa: => Task[A]): Task[A] =
    Suspend(fa _)

  /** Alias for [[defer]]. */
  def suspend[A](fa: => Task[A]): Task[A] =
    Suspend(fa _)

  /** Promote a non-strict value to a Task that is memoized on the first
    * evaluation, the result being then available on subsequent evaluations.
    */
  def evalOnce[A](a: => A): Task[A] =
    Delay(Coeval.Once(a _))

  /** Promote a non-strict value to a Task, catching exceptions in the
    * process.
    *
    * Note that since `Task` is not memoized, this will recompute the
    * value each time the `Task` is executed.
    */
  def eval[A](a: => A): Task[A] =
    Delay(Coeval.Always(a _))

  /** Alias for [[coeval]]. */
  def delay[A](a: => A): Task[A] = eval(a)

  /** Alias for [[coeval]]. Deprecated. */
  @deprecated("Renamed, please use Task.eval", since="2.0-RC12")
  def evalAlways[A](a: => A): Task[A] = eval(a)

  /** A [[Task]] instance that upon evaluation will never complete. */
  def never[A]: Task[A] = neverRef

  /** Builds a [[Task]] instance out of a Scala `Try`. */
  def fromTry[A](a: Try[A]): Task[A] =
    Delay(Coeval.fromTry(a))

  private[this] final val neverRef: Async[Nothing] =
    Async((_,_,_) => ())

  /** A `Task[Unit]` provided for convenience. */
  final val unit: Task[Unit] = Delay(Coeval.unit)

  /** Transforms a [[Coeval]] into a [[Task]]. */
  def coeval[A](a: Coeval[A]): Task[A] = Delay(a)

  /** Mirrors the given source `Task`, but upon execution ensure
    * that evaluation forks into a separate (logical) thread.
    *
    * The [[monix.execution.Scheduler Scheduler]] used will be
    * the one that is used to start the run-loop in `runAsync`.
    *
    * @param fa is the task that will get executed asynchronously
    */
  def fork[A](fa: Task[A]): Task[A] =
    Async { (s, conn, cb) =>
      // Asynchronous boundary
      Task.startTrampolineAsync(s, conn, fa, Callback.async(cb)(s), Nil)
    }

  /** Mirrors the given source `Task`, but upon execution ensure
    * that evaluation forks into a separate (logical) thread.
    *
    * The given [[monix.execution.Scheduler Scheduler]]  will be
    * used for execution of the [[Task]], effectively overriding the
    * `Scheduler` that's passed in `runAsync`. Thus you can
    * execute a whole `Task` on a separate thread-pool, useful for
    * example in case of doing I/O.
    *
    * @param fa is the task that will get executed asynchronously
    * @param scheduler is the scheduler to use for execution
    */
  def fork[A](fa: Task[A], scheduler: Scheduler): Task[A] =
    Async { (_, conn, cb) =>
      // Asynchronous boundary
      Task.startTrampolineAsync(scheduler, conn, fa, Callback.async(cb)(scheduler), Nil)
    }

  /** Create a `Task` from an asynchronous computation.
    *
    * Alias for [[create]].
    */
  def async[A](register: (Scheduler, Callback[A]) => Cancelable): Task[A] =
    create(register)

  /** Create a `Task` from an asynchronous computation, which takes the
    * form of a function with which we can register a callback.
    *
    * This can be used to translate from a callback-based API to a
    * straightforward monadic version.
    *
    * Contract:
    *
    *  1. execution of the `register` callback is async,
    *     forking a (logical) thread
    *  2. execution of the `onSuccess` and `onError` callbacks,
    *     is async, forking another (logical) thread
    *
    * Point number 2 happens because [[create]] is supposed to be safe
    * or otherwise, depending on the executed logic, one can end up with
    * a stack overflow exception. So this contract happens in order to
    * guarantee safety. In order to bypass rule number 2, one can use
    * [[unsafeCreate]], but that's for people knowing what they are doing.
    *
    * @param register is a function that will be called when this `Task`
    *        is executed, receiving a callback as a parameter, a
    *        callback that the user is supposed to call in order to
    *        signal the desired outcome of this `Task`.
    */
  def create[A](register: (Scheduler, Callback[A]) => Cancelable): Task[A] = {
    // Wraps a callback into an implementation that pops the stack
    // before calling onSuccess/onError
    final class CreateCallback(conn: StackedCancelable, cb: Callback[A])
      (implicit s: Scheduler)
      extends Callback[A] {

      def onSuccess(value: A): Unit = {
        conn.pop()
        cb.asyncOnSuccess(value)
      }

      def onError(ex: Throwable): Unit = {
        conn.pop()
        cb.asyncOnError(ex)
      }
    }

    Async { (scheduler, conn, cb) =>
      val c = SingleAssignmentCancelable()
      conn push c

      // Forcing asynchronous boundary, otherwise
      // stack-overflows can happen
      scheduler.executeAsync(() =>
        try {
          c := register(scheduler, new CreateCallback(conn, cb)(scheduler))
        }
        catch {
          case NonFatal(ex) =>
            // We cannot stream the error, because the callback might have
            // been called already and we'd be violating its contract,
            // hence the only thing possible is to log the error.
            scheduler.reportFailure(ex)
        })
    }
  }

  /** Constructs a lazy [[Task]] instance whose result
    * will be computed asynchronously.
    *
    * Unsafe to use directly, only use if you know what you're doing.
    * For building `Task` instances safely see [[create]].
    */
  def unsafeCreate[A](onFinish: OnFinish[A]): Task[A] =
    Async(onFinish)

  /** Converts the given Scala `Future` into a `Task`.
    *
    * NOTE: if you want to defer the creation of the future, use
    * in combination with [[defer]].
    */
  def fromFuture[A](f: Future[A]): Task[A] = {
    if (f.isCompleted) {
      // An already computed result is synchronous
      fromTry(f.value.get)
    }
    else f match {
      // Do we have a CancelableFuture?
      case c: Cancelable =>
        // Cancelable future, needs canceling
        Async { (s, conn, cb) =>
          // Already completed future?
          if (f.isCompleted) cb.asyncApply(f.value.get)(s) else {
            conn.push(c)
            f.onComplete { result =>
              conn.pop()
              cb(result)
            }(s)
          }
        }
      case _ =>
        // Simple future, convert directly
        Async { (s, conn, cb) =>
          if (f.isCompleted)
            cb.asyncApply(f.value.get)(s)
          else
            f.onComplete(cb)(s)
        }
    }
  }

  /** Creates a `Task` that upon execution will execute both given tasks
    * (possibly in parallel in case the tasks are asynchronous) and will
    * return the result of the task that manages to complete first,
    * along with a cancelable future of the other task.
    *
    * If the first task that completes
    */
  def chooseFirstOf[A,B](fa: Task[A], fb: Task[B]): Task[Either[(A, CancelableFuture[B]), (CancelableFuture[A], B)]] =
    Async { (scheduler, conn, cb) =>
      implicit val s = scheduler
      val pa = Promise[A]()
      val pb = Promise[B]()

      val isActive = Atomic(true)
      val connA = StackedCancelable()
      val connB = StackedCancelable()
      conn push CompositeCancelable(connA, connB)

      // First task: A
      Task.unsafeStartAsync(fa, scheduler, connA, new Callback[A] {
        def onSuccess(valueA: A): Unit =
          if (isActive.getAndSet(false)) {
            val futureB = CancelableFuture(pb.future, connB)
            conn.pop()
            cb.asyncOnSuccess(Left((valueA, futureB)))
          } else {
            pa.success(valueA)
          }

        def onError(ex: Throwable): Unit =
          if (isActive.getAndSet(false)) {
            conn.pop()
            connB.cancel()
            cb.asyncOnError(ex)
          } else {
            pa.failure(ex)
          }
      })

      // Second task: B
      Task.unsafeStartAsync(fb, scheduler, connB, new Callback[B] {
        def onSuccess(valueB: B): Unit =
          if (isActive.getAndSet(false)) {
            val futureA = CancelableFuture(pa.future, connA)
            conn.pop()
            cb.asyncOnSuccess(Right((futureA, valueB)))
          } else {
            pb.success(valueB)
          }

        def onError(ex: Throwable): Unit =
          if (isActive.getAndSet(false)) {
            conn.pop()
            connA.cancel()
            cb.asyncOnError(ex)
          } else {
            pb.failure(ex)
          }
      })
    }


  /** Creates a `Task` that upon execution will return the result of the
    * first completed task in the given list and then cancel the rest.
    */
  def chooseFirstOfList[A](tasks: TraversableOnce[Task[A]]): Task[A] =
    Async { (scheduler, conn, callback) =>
      implicit val s = scheduler
      val isActive = Atomic.withPadding(true, PaddingStrategy.LeftRight128)
      val composite = CompositeCancelable()
      conn.push(composite)

      val cursor = tasks.toIterator

      while (isActive.get && cursor.hasNext) {
        val task = cursor.next()
        val taskCancelable = StackedCancelable()
        composite += taskCancelable

        Task.unsafeStartAsync(task, scheduler, taskCancelable, new Callback[A] {
          def onSuccess(value: A): Unit =
            if (isActive.getAndSet(false)) {
              composite -= taskCancelable
              composite.cancel()
              conn.popAndCollapse(taskCancelable)
              callback.asyncOnSuccess(value)
            }

          def onError(ex: Throwable): Unit =
            if (isActive.getAndSet(false)) {
              composite -= taskCancelable
              composite.cancel()
              conn.popAndCollapse(taskCancelable)
              callback.asyncOnError(ex)
            } else {
              scheduler.reportFailure(ex)
            }
        })
      }
    }

  /** Given a `TraversableOnce` of tasks, transforms it to a task signaling
    * the collection, executing the tasks one by one and gathering their
    * results in the same collection.
    *
    * This operation will execute the tasks one by one, in order, which means that
    * both effects and results will be ordered. See [[gather]] and [[gatherUnordered]]
    * for unordered results or effects, and thus potential of running in parallel.
    *
    *  It's a simple version of [[traverse]].
    */
  def sequence[A, M[X] <: TraversableOnce[X]](in: M[Task[A]])
    (implicit cbf: CanBuildFrom[M[Task[A]], A, M[A]]): Task[M[A]] = {
    val init = eval(cbf(in))
    val r = in.foldLeft(init)((acc,elem) => acc.flatMap(lb => elem.map(e => lb += e)))
    r.map(_.result())
  }

  /** Given a `TraversableOnce[A]` and a function `A => Task[B]`, sequentially
   *  apply the function to each element of the collection and gather their
   *  results in the same collection.
   *
   *  It's a generalized version of [[sequence]].
   */
  def traverse[A, B, M[X] <: TraversableOnce[X]](in: M[A])(f: A => Task[B])
    (implicit cbf: CanBuildFrom[M[A], B, M[B]]): Task[M[B]] = {
    val init = eval(cbf(in))
    val r = in.foldLeft(init)((acc,elem) => acc.flatMap(lb => f(elem).map(e => lb += e)))
    r.map(_.result())
  }

  /** Nondeterministically gather results from the given collection of tasks,
    * returning a task that will signal the same type of collection of results
    * once all tasks are finished.
    *
    * This function is the nondeterministic analogue of `sequence` and should
    * behave identically to `sequence` so long as there is no interaction between
    * the effects being gathered. However, unlike `sequence`, which decides on
    * a total order of effects, the effects in a `gather` are unordered with
    * respect to each other.
    *
    * Although the effects are unordered, we ensure the order of results
    * matches the order of the input sequence. Also see [[gatherUnordered]]
    * for the more efficient alternative.
    */
  def gather[A, M[X] <: TraversableOnce[X]](in: M[Task[A]])
    (implicit cbf: CanBuildFrom[M[Task[A]], A, M[A]]): Task[M[A]] = {

    val sortKey = new Ordering[(A,Int)] {
      def compare(x: (A, Int), y: (A, Int)): Int =
        if (x._2 < y._2) -1 else if (x._2 > y._2) 1 else 0
    }

    val tasks = in.toIterable
      .zipWithIndex.map { case (t,i) => t.map(a => (a,i)) }

    for (result <- gatherUnordered(tasks)) yield {
      val array = result.toArray
      // In place, because we're creating enough junk already
      Sorting.quickSort(array)(sortKey)
      val builder = cbf(in)
      var idx = 0
      while (idx < array.length) { builder += array(idx)._1; idx += 1 }
      builder.result()
    }
  }

  /** Nondeterministically gather results from the given collection of tasks,
    * without keeping the original ordering of results.
    *
    * This function is similar to [[gather]], but neither the effects nor the
    * results will be ordered. Useful when you don't need ordering because it
    * can be more efficient than `gather`.
    */
  def gatherUnordered[A, M[X] <: TraversableOnce[X]](in: M[Task[A]])
    (implicit cbf: CanBuildFrom[M[Task[A]], A, M[A]]): Task[M[A]] = {

    // We are using OOP for initializing this `OnFinish` callback because we
    // need something to synchronize on and because it's better for making the
    // state explicit (e.g. the builder, remaining, isActive vars)
    Async { (scheduler, conn, finalCallback) =>
      // We need a monitor to synchronize on, per evaluation!
      val lock = new AnyRef
      // Forces a fork on another (logical) thread!
      scheduler.executeAsync(() => lock.synchronized {
        // Aggregates all results into a buffer.
        // MUST BE synchronized by `lock`!
        var builder = cbf(in)

        // Keeps track of tasks remaining to be completed.
        // Is initialized by 1 because of the logic - tasks can run synchronously,
        // and we decrement this value whenever one finishes, so we must prevent
        // zero values before the loop is done.
        // MUST BE synchronized by `lock`!
        var remaining = 1

        // If this variable is false, then a task ended in error.
        // MUST BE synchronized by `lock`!
        var isActive = true

        // MUST BE synchronized by `lock`!
        // MUST NOT BE called if isActive == false!
        @inline def maybeSignalFinal(conn: StackedCancelable, finalCallback: Callback[M[A]])
          (implicit s: Scheduler): Unit = {

          remaining -= 1
          if (remaining == 0) {
            isActive = false
            conn.pop()
            val result = builder.result()
            builder = null // GC relief
            finalCallback.asyncOnSuccess(result)
          }
        }

        implicit val s = scheduler
        // Represents the collection of cancelables for all started tasks
        val composite = CompositeCancelable()
        conn.push(composite)

        // Collecting all cancelables in a buffer, because adding
        // cancelables one by one in our `CompositeCancelable` is
        // expensive, so we do it at the end
        val allCancelables = ListBuffer.empty[StackedCancelable]
        val cursor = in.toIterator

        // The `isActive` check short-circuits the process in case
        // we have a synchronous task that just completed in error
        while (cursor.hasNext && isActive) {
          remaining += 1
          val task = cursor.next()
          val stacked = StackedCancelable()
          allCancelables += stacked

          // Light asynchronous boundary; with most scheduler implementations
          // it will not fork a new (logical) thread!
          scheduler.executeTrampolined(() =>
            Task.unsafeStartNow(task, scheduler, stacked,
              new Callback[A] {
                def onSuccess(value: A): Unit =
                  lock.synchronized {
                    if (isActive) {
                      builder += value
                      maybeSignalFinal(conn, finalCallback)
                    }
                  }

                def onError(ex: Throwable): Unit =
                  lock.synchronized {
                    if (isActive) {
                      isActive = false
                      // This should cancel our CompositeCancelable
                      conn.pop().cancel()
                      finalCallback.asyncOnError(ex)
                      builder = null // GC relief
                    } else {
                      scheduler.reportFailure(ex)
                    }
                  }
              }))
        }

        // All tasks could have executed synchronously, so we might be
        // finished already. If so, then trigger the final callback.
        maybeSignalFinal(conn, finalCallback)

        // Note that if an error happened, this should cancel all
        // other active tasks.
        composite ++= allCancelables
      })
    }
  }

  /** Apply a mapping functions to the results of two tasks, nondeterministically
    * ordering their effects.
    *
    * If the two tasks are synchronous, they'll get executed one
    * after the other, with the result being available asynchronously.
    * If the two tasks are asynchronous, they'll get scheduled for execution
    * at the same time and in a multi-threading environment they'll execute
    * in parallel and have their results synchronized.
    */
  def mapBoth[A1,A2,R](fa1: Task[A1], fa2: Task[A2])(f: (A1,A2) => R): Task[R] = {
    /* For signaling the values after the successful completion of both tasks. */
    def sendSignal(conn: StackedCancelable, cb: Callback[R], a1: A1, a2: A2)
      (implicit s: Scheduler): Unit = {

      var streamErrors = true
      try {
        val r = f(a1,a2)
        streamErrors = false
        conn.pop()
        cb.asyncOnSuccess(r)
      } catch {
        case NonFatal(ex) if streamErrors =>
          // Both tasks completed by this point, so we don't need
          // to worry about the `state` being a `Stop`
          conn.pop()
          cb.asyncOnError(ex)
      }
    }

    /* For signaling an error. */
    @tailrec def sendError(conn: StackedCancelable, state: AtomicAny[AnyRef], cb: Callback[R], ex: Throwable)
      (implicit s: Scheduler): Unit = {

      // Guarding the contract of the callback, as we cannot send an error
      // if an error has already happened because of the other task
      state.get match {
        case Stop =>
          // We've got nowhere to send the error, so report it
          s.reportFailure(ex)
        case other =>
          if (!state.compareAndSet(other, Stop))
            sendError(conn, state, cb, ex)(s) // retry
          else {
            conn.pop().cancel()
            cb.asyncOnError(ex)(s)
          }
      }
    }

    // The resulting task will be executed asynchronously
    Async { (scheduler, conn, cb) =>
      // Initial asynchronous boundary
      scheduler.executeAsync { () =>
        // for synchronizing the results
        val state = Atomic(null : AnyRef)
        val task1 = StackedCancelable()
        val task2 = StackedCancelable()
        conn push CompositeCancelable(task1, task2)

        // Light asynchronous boundary; with most scheduler implementations
        // it will not fork a new (logical) thread!
        scheduler.executeTrampolined(() =>
          Task.unsafeStartNow(fa1, scheduler, task1, new Callback[A1] {
            @tailrec def onSuccess(a1: A1): Unit =
              state.get match {
                case null => // null means this is the first task to complete
                  if (!state.compareAndSet(null, Left(a1))) onSuccess(a1)
                case ref @ Right(a2) => // the other task completed, so we can send
                  sendSignal(conn, cb, a1, a2.asInstanceOf[A2])(scheduler)
                case Stop => // the other task triggered an error
                  () // do nothing
                case s @ Left(_) =>
                  // This task has triggered multiple onSuccess calls
                  // violating the protocol. Should never happen.
                  onError(new IllegalStateException(s.toString))
              }

            def onError(ex: Throwable): Unit =
              sendError(conn, state, cb, ex)(scheduler)
          }))

        // Light asynchronous boundary
        scheduler.executeTrampolined(() =>
          Task.unsafeStartNow(fa2, scheduler, task2, new Callback[A2] {
            @tailrec def onSuccess(a2: A2): Unit =
              state.get match {
                case null => // null means this is the first task to complete
                  if (!state.compareAndSet(null, Right(a2))) onSuccess(a2)
                case ref @ Left(a1) => // the other task completed, so we can send
                  sendSignal(conn, cb, a1.asInstanceOf[A1], a2)(scheduler)
                case Stop => // the other task triggered an error
                  () // do nothing
                case s @ Right(_) =>
                  // This task has triggered multiple onSuccess calls
                  // violating the protocol. Should never happen.
                  onError(new IllegalStateException(s.toString))
              }

            def onError(ex: Throwable): Unit =
              sendError(conn, state, cb, ex)(scheduler)
          }))
      }
    }
  }

  /** Gathers the results from a sequence of tasks into a single list.
    * The effects are not ordered, but the results are.
    */
  def zipList[A](sources: Task[A]*): Task[List[A]] = {
    val init = eval(mutable.ListBuffer.empty[A])
    val r = sources.foldLeft(init)((acc,elem) => Task.mapBoth(acc,elem)(_ += _))
    r.map(_.toList)
  }

  /** Pairs two [[Task]] instances. */
  def zip2[A1,A2,R](fa1: Task[A1], fa2: Task[A2]): Task[(A1,A2)] =
    Task.mapBoth(fa1, fa2)((_,_))

  /** Pairs two [[Task]] instances, creating a new instance that will apply
    * the given mapping function to the resulting pair. */
  def zipMap2[A1,A2,R](fa1: Task[A1], fa2: Task[A2])(f: (A1,A2) => R): Task[R] =
    Task.mapBoth(fa1, fa2)(f)

  /** Pairs three [[Task]] instances. */
  def zip3[A1,A2,A3](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3]): Task[(A1,A2,A3)] =
    zipMap3(fa1,fa2,fa3)((a1,a2,a3) => (a1,a2,a3))
  /** Pairs four [[Task]] instances. */
  def zip4[A1,A2,A3,A4](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4]): Task[(A1,A2,A3,A4)] =
    zipMap4(fa1,fa2,fa3,fa4)((a1,a2,a3,a4) => (a1,a2,a3,a4))
  /** Pairs five [[Task]] instances. */
  def zip5[A1,A2,A3,A4,A5](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4], fa5: Task[A5]): Task[(A1,A2,A3,A4,A5)] =
    zipMap5(fa1,fa2,fa3,fa4,fa5)((a1,a2,a3,a4,a5) => (a1,a2,a3,a4,a5))
  /** Pairs six [[Task]] instances. */
  def zip6[A1,A2,A3,A4,A5,A6](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4], fa5: Task[A5], fa6: Task[A6]): Task[(A1,A2,A3,A4,A5,A6)] =
    zipMap6(fa1,fa2,fa3,fa4,fa5,fa6)((a1,a2,a3,a4,a5,a6) => (a1,a2,a3,a4,a5,a6))

  /** Pairs three [[Task]] instances,
    * applying the given mapping function to the result.
    */
  def zipMap3[A1,A2,A3,R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3])(f: (A1,A2,A3) => R): Task[R] = {
    val fa12 = zip2(fa1, fa2)
    zipMap2(fa12, fa3) { case ((a1,a2), a3) => f(a1,a2,a3) }
  }

  /** Pairs four [[Task]] instances,
    * applying the given mapping function to the result.
    */
  def zipMap4[A1,A2,A3,A4,R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4])(f: (A1,A2,A3,A4) => R): Task[R] = {
    val fa123 = zip3(fa1, fa2, fa3)
    zipMap2(fa123, fa4) { case ((a1,a2,a3), a4) => f(a1,a2,a3,a4) }
  }

  /** Pairs five [[Task]] instances,
    * applying the given mapping function to the result.
    */
  def zipMap5[A1,A2,A3,A4,A5,R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4], fa5: Task[A5])(f: (A1,A2,A3,A4,A5) => R): Task[R] = {
    val fa1234 = zip4(fa1, fa2, fa3, fa4)
    zipMap2(fa1234, fa5) { case ((a1,a2,a3,a4), a5) => f(a1,a2,a3,a4,a5) }
  }

  /** Pairs six [[Task]] instances,
    * applying the given mapping function to the result.
    */
  def zipMap6[A1,A2,A3,A4,A5,A6,R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4], fa5: Task[A5], fa6: Task[A6])(f: (A1,A2,A3,A4,A5,A6) => R): Task[R] = {
    val fa12345 = zip5(fa1, fa2, fa3, fa4, fa5)
    zipMap2(fa12345, fa6) { case ((a1,a2,a3,a4,a5), a6) => f(a1,a2,a3,a4,a5,a6) }
  }

  @deprecated("Renamed to Task.zipMap2", since="2.0-RC12")
  def zipWith2[A1,A2,R](fa1: Task[A1], fa2: Task[A2])(f: (A1,A2) => R): Task[R] =
    zipMap2(fa1, fa2)(f)

  @deprecated("Renamed to Task.zipMap3", since="2.0-RC12")
  def zipWith3[A1,A2,A3,R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3])(f: (A1,A2,A3) => R): Task[R] =
    zipMap3(fa1, fa2, fa3)(f)

  @deprecated("Renamed to Task.zipMap4", since="2.0-RC12")
  def zipWith4[A1,A2,A3,A4,R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4])(f: (A1,A2,A3,A4) => R): Task[R] =
    zipMap4(fa1, fa2, fa3, fa4)(f)

  @deprecated("Renamed to Task.zipMap5", since="2.0-RC12")
  def zipWith5[A1,A2,A3,A4,A5,R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4], fa5: Task[A5])(f: (A1,A2,A3,A4,A5) => R): Task[R] =
    zipMap5(fa1, fa2, fa3, fa4, fa5)(f)

  @deprecated("Renamed to Task.zipMap6", since="2.0-RC12")
  def zipWith6[A1,A2,A3,A4,A5,A6,R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4], fa5: Task[A5], fa6: Task[A6])(f: (A1,A2,A3,A4,A5,A6) => R): Task[R] =
    zipMap6(fa1, fa2, fa3, fa4, fa5, fa6)(f)

  /** Type alias representing callbacks for [[create]] tasks. */
  type OnFinish[+A] = (Scheduler, StackedCancelable, Callback[A]) => Unit

  private case class Delay[A](coeval: Coeval[A]) extends Task[A] {
    override def runAsync(cb: Callback[A])(implicit s: Scheduler): Cancelable = {
      cb.asyncApply(coeval)
      Cancelable.empty
    }

    override def runAsync(implicit s: Scheduler): CancelableFuture[A] =
      coeval.runAttempt match {
        case Coeval.Now(value) => CancelableFuture.successful(value)
        case Coeval.Error(ex) => CancelableFuture.failed(ex)
      }
  }

  /** Constructs a lazy [[Task]] instance whose result will
    * be computed asynchronously.
    *
    * Unsafe to build directly, only use if you know what you're doing.
    * For building `Async` instances safely, see [[create]].
    */
  private final case class Async[+A](onFinish: OnFinish[A]) extends Task[A]

  /** Internal state, the result of [[Task.defer]] */
  private[eval] final case class Suspend[+A](thunk: () => Task[A]) extends Task[A]
  /** Internal [[Task]] state that is the result of applying `flatMap`. */
  private[eval] final case class BindSuspend[A,B](thunk: () => Task[A], f: A => Task[B]) extends Task[B]

  /** Internal [[Task]] state that is the result of applying `flatMap`
    * over an [[Async]] value.
    */
  private[eval] final case class BindAsync[A,B](onFinish: OnFinish[A], f: A => Task[B]) extends Task[B]

  /** Internal [[Task]] state that defers the evaluation of the
    * given [[Task]] and upon execution memoize its result to
    * be available for later evaluations.
    */
  private final class MemoizeSuspend[A](f: () => Task[A]) extends Task[A] {
    private[this] var thunk: () => Task[A] = f
    private[this] val state = Atomic(null : AnyRef)

    def value: Option[Attempt[A]] =
      state.get match {
        case null => None
        case (p: Promise[_], _) =>
          p.asInstanceOf[Promise[A]].future.value match {
            case None => None
            case Some(value) => Some(Attempt.fromTry(value))
          }
        case result: Try[_] =>
          Some(Attempt.fromTry(result.asInstanceOf[Try[A]]))
      }

    override def runAsync(cb: Callback[A])(implicit s: Scheduler): Cancelable =
      state.get match {
        case null =>
          super.runAsync(cb)(s)
        case (p: Promise[_], conn: StackedCancelable) =>
          val f = p.asInstanceOf[Promise[A]].future
          f.onComplete(cb)
          conn
        case result: Try[_] =>
          cb.asyncApply(result.asInstanceOf[Try[A]])
          Cancelable.empty
      }

    override def runAsync(implicit s: Scheduler): CancelableFuture[A] =
      state.get match {
        case null =>
          super.runAsync(s)
        case (p: Promise[_], conn: StackedCancelable) =>
          val f = p.asInstanceOf[Promise[A]].future
          CancelableFuture(f, conn)
        case result: Try[_] =>
          CancelableFuture.fromTry(result.asInstanceOf[Try[A]])
      }

    private def memoizeValue(value: Try[A]): Unit = {
      state.getAndSet(value) match {
        case (p: Promise[_], _) =>
          p.asInstanceOf[Promise[A]].complete(value)
        case _ =>
          () // do nothing
      }

      // GC purposes
      thunk = null
    }

    @tailrec def execute(active: StackedCancelable, cb: Callback[A], binds: List[Bind], nextFrame: Int)
      (implicit s: Scheduler): Boolean = {

      state.get match {
        case null =>
          val p = Promise[A]()

          if (!state.compareAndSet(null, (p, active)))
            execute(active, cb, binds, nextFrame)(s) // retry
          else {
            val underlying = try thunk() catch { case NonFatal(ex) => raiseError(ex) }
            val callback = new Callback[A] {
              def onError(ex: Throwable): Unit = {
                memoizeValue(Failure(ex))
                if (binds.isEmpty) cb.asyncOnError(ex) else
                  // Resuming trampoline with the rest of the binds
                  Task.startTrampolineAsync(s, active, raiseError(ex), cb, binds)
              }

              def onSuccess(value: A): Unit = {
                memoizeValue(Success(value))
                if (binds.isEmpty) cb.asyncOnSuccess(value) else
                  // Resuming trampoline with the rest of the binds
                  Task.startTrampolineAsync(s, active, now(value), cb, binds)
              }
            }

            // Asynchronous boundary to prevent stack-overflows!
            s.executeTrampolined(() =>
              runLoop(s, s.executionModel, active, underlying,
                callback.asInstanceOf[Callback[Any]], Nil,
                nextFrame))

            true
          }

        case (p: Promise[_], mainCancelable: StackedCancelable) =>
          // execution is pending completion
          active push mainCancelable
          p.asInstanceOf[Promise[A]].future.onComplete { r =>
            active.pop()
            Task.startTrampolineRunLoop(s, active, fromTry(r), cb, binds)
          }
          true

        case result: Try[_] =>
          // Race condition happened
          false
      }
    }
  }

  private type Current = Task[Any]
  private type Bind = Any => Task[Any]

  /** Unsafe utility - starts the execution of a Task with a guaranteed
    * asynchronous boundary, by providing
    * the needed [[monix.execution.Scheduler Scheduler]],
    * [[monix.execution.cancelables.StackedCancelable StackedCancelable]]
    * and [[Callback]].
    *
    * DO NOT use directly, as it is UNSAFE to use, unless you know
    * what you're doing. Prefer [[Task.runAsync(cb* Task.runAsync]]
    * and `Task.fork`.
    */
  def unsafeStartAsync[A](source: Task[A], scheduler: Scheduler, conn: StackedCancelable, cb: Callback[A]): Unit = {
    // Task is already known to execute asynchronously
    startTrampolineAsync(scheduler, conn, source, cb, Nil)
  }

  /** Unsafe utility - starts the execution of a Task, by providing
    * the needed [[monix.execution.Scheduler Scheduler]],
    * [[monix.execution.cancelables.StackedCancelable StackedCancelable]]
    * and [[Callback]].
    *
    * DO NOT use directly, as it is UNSAFE to use, unless you know
    * what you're doing. Prefer [[Task.runAsync(cb* Task.runAsync]].
    */
  def unsafeStartNow[A](source: Task[A], scheduler: Scheduler, conn: StackedCancelable, cb: Callback[A]): Unit =
    startTrampolineRunLoop(scheduler, conn, source, cb, Nil)

  /** Internal utility, returns true if the current state
    * is known to be asynchronous.
    */
  private def isNextAsync[A](source: Task[A]): Boolean =
    source match {
      case Async(_) | BindAsync(_,_) => true
      case _ => false
    }

  /** Internal utility, for forcing an asynchronous boundary in the
    * trampoline loop.
    */
  @inline private def startTrampolineAsync[A](
    scheduler: Scheduler,
    conn: StackedCancelable,
    source: Task[A],
    cb: Callback[A],
    binds: List[Bind]): Unit = {

    if (!conn.isCanceled)
      scheduler.executeAsync(() =>
        startTrampolineRunLoop(scheduler, conn, source, cb, binds))
  }

  /** Internal utility - the actual trampoline run-loop implementation. */
  @tailrec def runLoop(
    scheduler: Scheduler,
    em: ExecutionModel,
    conn: StackedCancelable,
    source: Current,
    cb: Callback[Any],
    binds: List[Bind],
    frameIndex: Int): Unit = {

    @inline def executeOnFinish(
      scheduler: Scheduler,
      conn: StackedCancelable,
      cb: Callback[Any],
      fs: List[Bind],
      onFinish: OnFinish[Any]): Unit = {

      if (!conn.isCanceled)
        onFinish(scheduler, conn, new Callback[Any] {
          def onSuccess(value: Any): Unit =
            startTrampolineRunLoop(scheduler, conn, now(value), cb, fs)
          def onError(ex: Throwable): Unit =
            cb.onError(ex)
        })
    }

    if (frameIndex == 0 && !Task.isNextAsync(source)) {
      // Asynchronous boundary is forced because of the Scheduler's ExecutionModel
      startTrampolineAsync(scheduler, conn, source, cb, binds)
    }
    else source match {
      case Delay(coeval) =>
        binds match {
          case Nil =>
            cb(coeval)
          case f :: rest =>
            coeval.runAttempt match {
              case Now(a) =>
                val fa = try f(a) catch { case NonFatal(ex) => raiseError(ex) }
                runLoop(scheduler, em, conn, fa, cb, rest, em.nextFrameIndex(frameIndex))
              case Error(ex) =>
                cb.onError(ex)
            }
        }

      case Suspend(thunk) =>
        val fa = try thunk() catch { case NonFatal(ex) => raiseError(ex) }
        runLoop(scheduler, em, conn, fa, cb, binds, em.nextFrameIndex(frameIndex))

      case ref: MemoizeSuspend[_] =>
        val nextFrame = em.nextFrameIndex(frameIndex)
        ref.value match {
          case Some(materialized) =>
            runLoop(scheduler, em, conn, coeval(materialized), cb, binds, nextFrame)
          case None =>
            val success = source.asInstanceOf[MemoizeSuspend[Any]].execute(
              conn, cb, binds, nextFrame)(scheduler)
            if (!success) // retry?
              runLoop(scheduler, em, conn, source, cb, binds, nextFrame)
        }

      case BindSuspend(thunk, f) =>
        val fa = try thunk() catch { case NonFatal(ex) => raiseError(ex) }
        runLoop(scheduler, em, conn, fa, cb, f.asInstanceOf[Bind] :: binds,
          em.nextFrameIndex(frameIndex))

      case BindAsync(onFinish, f) =>
        executeOnFinish(scheduler, conn, cb, f.asInstanceOf[Bind] :: binds, onFinish)

      case Async(onFinish) =>
        executeOnFinish(scheduler, conn, cb, binds, onFinish)
    }
  }

  /** Internal utility, starts or resumes evaluation of
    * the run-loop from where it left off.
    */
  private def startTrampolineRunLoop[A](
    scheduler: Scheduler,
    conn: StackedCancelable,
    source: Task[A],
    cb: Callback[A],
    binds: List[Bind]): Unit = {

    runLoop(scheduler, scheduler.executionModel,
      conn, source, cb.asInstanceOf[Callback[Any]], binds,
      // value ensures that first cycle is not async
      frameIndex = 1)
  }

  /** A run-loop that attempts to complete a
    * [[monix.execution.CancelableFuture CancelableFuture]] synchronously ,
    * falling back to [[startTrampolineRunLoop]] and actual asynchronous execution
    * in case of an asynchronous boundary.
    */
  private def startTrampolineForFuture[A](
    scheduler: Scheduler,
    source: Task[A],
    binds: List[Bind]): CancelableFuture[A] = {

    def goAsync(scheduler: Scheduler, source: Current, binds: List[Bind], isNextAsync: Boolean): CancelableFuture[Any] = {
      val p = Promise[Any]()
      val cb: Callback[Any] = new Callback[Any] {
        def onSuccess(value: Any): Unit = p.trySuccess(value)
        def onError(ex: Throwable): Unit = p.tryFailure(ex)
      }

      val conn = StackedCancelable()
      if (!isNextAsync)
        startTrampolineAsync(scheduler, conn, source, cb, binds)
      else
        startTrampolineRunLoop(scheduler, conn, source, cb, binds)

      CancelableFuture(p.future, conn)
    }

    @tailrec def loop(
      scheduler: Scheduler,
      em: ExecutionModel,
      source: Current,
      binds: List[Bind],
      frameIndex: Int): CancelableFuture[Any] = {

      if (frameIndex == 0 && !Task.isNextAsync(source)) {
        // Asynchronous boundary is forced
        goAsync(scheduler, source, binds, isNextAsync = false)
      }
      else source match {
        case Delay(coeval) =>
          coeval.runAttempt match {
            case Error(ex) =>
              CancelableFuture.failed(ex)
            case Now(a) =>
              binds match {
                case Nil =>
                  CancelableFuture.successful(a)
                case f :: rest =>
                  val fa = try f(a) catch { case NonFatal(ex) => raiseError(ex) }
                  loop(scheduler, em, fa, rest, em.nextFrameIndex(frameIndex))
              }
          }

        case Suspend(thunk) =>
          val fa = try thunk() catch { case NonFatal(ex) => raiseError(ex) }
          loop(scheduler, em, fa, binds, em.nextFrameIndex(frameIndex))

        case BindSuspend(thunk, f) =>
          val fa = try thunk() catch { case NonFatal(ex) => raiseError(ex) }
          loop(scheduler, em, fa, f.asInstanceOf[Bind] :: binds,
            em.nextFrameIndex(frameIndex))

        case ref: MemoizeSuspend[_] =>
          val task = ref.asInstanceOf[MemoizeSuspend[A]]
          task.value match {
            case Some(materialized) =>
              loop(scheduler, em, coeval(materialized), binds, em.nextFrameIndex(frameIndex))
            case None =>
              goAsync(scheduler, source, binds, isNextAsync = true)
          }

        case async =>
          goAsync(scheduler, async, binds, isNextAsync = true)
      }
    }

    loop(scheduler, scheduler.executionModel, source, binds, frameIndex = 1)
      .asInstanceOf[CancelableFuture[A]]
  }

  /** Type-class instances for [[Task]]. */
  implicit val typeClassInstances: TypeClassInstances = new TypeClassInstances
}

private[eval] trait TaskInstances {
  /** Type-class instances for [[Task]] that have
    * nondeterministic effects for [[monix.types.Applicative Applicative]].
    *
    * It can be optionally imported in scope to make `map2` and `ap` to
    * potentially run tasks in parallel.
    */
  implicit val nondeterminism: TypeClassInstances =
    new TypeClassInstances {
      override def ap[A, B](ff: Task[(A) => B])(fa: Task[A]): Task[B] =
        Task.mapBoth(ff,fa)(_(_))
      override def map2[A, B, Z](fa: Task[A], fb: Task[B])(f: (A, B) => Z): Task[Z] =
        Task.mapBoth(fa, fb)(f)
    }

  /** Groups the implementation for the type-classes defined in [[monix.types]]. */
  class TypeClassInstances
    extends DeferrableClass[Task]
    with MemoizableClass[Task]
    with RecoverableClass[Task,Throwable]
    with CoflatMapClass[Task]
    with MonadRecClass[Task] {

    override def pure[A](a: A): Task[A] = Task.now(a)
    override def defer[A](fa: => Task[A]): Task[A] = Task.defer(fa)
    override def evalOnce[A](a: => A): Task[A] = Task.evalOnce(a)
    override def eval[A](a: => A): Task[A] = Task.eval(a)
    override def memoize[A](fa: Task[A]): Task[A] = fa.memoize

    override def flatMap[A, B](fa: Task[A])(f: (A) => Task[B]): Task[B] =
      fa.flatMap(f)
    override def flatten[A](ffa: Task[Task[A]]): Task[A] =
      ffa.flatten
    override def coflatMap[A, B](fa: Task[A])(f: (Task[A]) => B): Task[B] =
      Task.eval(f(fa))
    override def ap[A, B](ff: Task[(A) => B])(fa: Task[A]): Task[B] =
      for (f <- ff; a <- fa) yield f(a)
    override def map2[A, B, Z](fa: Task[A], fb: Task[B])(f: (A, B) => Z): Task[Z] =
      for (a <- fa; b <- fb) yield f(a,b)
    override def map[A, B](fa: Task[A])(f: (A) => B): Task[B] =
      fa.map(f)
    override def raiseError[A](e: Throwable): Task[A] =
      Task.raiseError(e)
    override def onErrorHandle[A](fa: Task[A])(f: (Throwable) => A): Task[A] =
      fa.onErrorHandle(f)
    override def onErrorHandleWith[A](fa: Task[A])(f: (Throwable) => Task[A]): Task[A] =
      fa.onErrorHandleWith(f)
    override def onErrorRecover[A](fa: Task[A])(pf: PartialFunction[Throwable, A]): Task[A] =
      fa.onErrorRecover(pf)
    override def onErrorRecoverWith[A](fa: Task[A])(pf: PartialFunction[Throwable, Task[A]]): Task[A] =
      fa.onErrorRecoverWith(pf)
  }
}
