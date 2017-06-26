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

package monix.eval

import cats.effect.IO
import monix.eval.Coeval.Eager
import monix.eval.instances._
import monix.eval.internal._
import monix.execution.ExecutionModel.{AlwaysAsyncExecution, BatchedExecution, SynchronousExecution}
import monix.execution._
import monix.execution.atomic.Atomic
import monix.execution.cancelables.StackedCancelable
import monix.execution.internal.Platform
import monix.execution.misc.{NonFatal, ThreadLocal}

import scala.annotation.unchecked.{uncheckedVariance => uV}
import scala.collection.generic.CanBuildFrom
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future, Promise, TimeoutException}
import scala.util.{Failure, Success, Try}

/** `Task` represents a specification for a possibly lazy or
  * asynchronous computation, which when executed will produce an `A`
  * as a result, along with possible side-effects.
  *
  * Compared with `Future` from Scala's standard library, `Task` does
  * not represent a running computation or a value detached from time,
  * as `Task` does not execute anything when working with its builders
  * or operators and it does not submit any work into any thread-pool,
  * the execution eventually taking place only after `runAsync` is
  * called and not before that.
  *
  * Note that `Task` is conservative in how it spawns logical threads.
  * Transformations like `map` and `flatMap` for example will default
  * to being executed on the logical thread on which the asynchronous
  * computation was started. But one shouldn't make assumptions about
  * how things will end up executed, as ultimately it is the
  * implementation's job to decide on the best execution model. All
  * you are guaranteed is asynchronous execution after executing
  * `runAsync`.
  *
  * @define runAsyncDesc Triggers the asynchronous execution.
  *
  *         Without invoking `runAsync` on a `Task`, nothing
  *         gets evaluated, as a `Task` has lazy behavior.
  */
sealed abstract class Task[+A] extends Serializable { self =>
  import monix.eval.Task._

  /** $runAsyncDesc
    *
    * @param cb is a callback that will be invoked upon completion.
    *
    * @param s is an injected [[monix.execution.Scheduler Scheduler]]
    *        that gets used whenever asynchronous boundaries are needed
    *        when evaluating the task
    *
    * @return a [[monix.execution.Cancelable Cancelable]] that can
    *         be used to cancel a running task
    */
  def runAsync(cb: Callback[A])(implicit s: Scheduler): Cancelable = {
    val context = Context(s)
    val frameStart = TaskRunLoop.frameStart(s.executionModel)
    TaskRunLoop.startWithCallback(self, context, Callback.safe(cb), null, null, frameStart)
    context.connection
  }

  /** Deprecated overload. Use [[runOnComplete]]. */
  @deprecated("Renamed to runOnComplete", since="2.1.3")
  def runAsync(f: Try[A] => Unit)(implicit s: Scheduler): Cancelable =
    runOnComplete(f)

  /** Similar to Scala's `Future#onComplete`, this method triggers
    * the evaluation of a `Task` and invokes the given callback whenever
    * the result is available.
    *
    * @param f is a callback that will be invoked upon completion.
    *
    * @param s is an injected [[monix.execution.Scheduler Scheduler]]
    *        that gets used whenever asynchronous boundaries are needed
    *        when evaluating the task
    *
    * @return a [[monix.execution.Cancelable Cancelable]] that can
    *         be used to cancel a running task
    */
  def runOnComplete(f: Try[A] => Unit)(implicit s: Scheduler): Cancelable =
    runAsync(new Callback[A] {
      def onSuccess(value: A): Unit = f(Success(value))
      def onError(ex: Throwable): Unit = f(Failure(ex))
    })

  /** $runAsyncDesc
    *
    * @param s is an injected [[monix.execution.Scheduler Scheduler]]
    *        that gets used whenever asynchronous boundaries are needed
    *        when evaluating the task
    *
    * @return a [[monix.execution.CancelableFuture CancelableFuture]]
    *         that can be used to extract the result or to cancel
    *         a running task.
    */
  def runAsync(implicit s: Scheduler): CancelableFuture[A] =
    TaskRunLoop.startAsFuture(this, s)

  /** Tries to execute the source synchronously.
    *
    * As an alternative to `runAsync`, this method tries to execute
    * the source task immediately on the current thread and call-stack.
    *
    * This method can throw whatever error is generated by the
    * source task, whenever that error is available immediately,
    * otherwise errors are signaled asynchronously by means of
    * `CancelableFuture`.
    *
    * @return `Right(result)` in case a result was processed,
    *        or `Left(future)` in case an asynchronous boundary
    *        was hit and further async execution is needed
    */
  def runSyncMaybe(implicit s: Scheduler): Either[CancelableFuture[A], A] = {
    val f = this.runAsync(s)
    f.value match {
      case None => Left(f)
      case Some(Success(a)) => Right(a)
      case Some(Failure(ex)) => throw ex
    }
  }

  /** Creates a new [[Task]] that will expose any triggered error
    * from the source.
    */
  def attempt: Task[Either[Throwable, A]] =
    FlatMap(this, AttemptTask.asInstanceOf[Transformation[A, Task[Either[Throwable, A]]]])

  /** Transforms a [[Task]] into a [[Coeval]] that tries to execute the
    * source synchronously, returning either `Right(value)` in case a
    * value is available immediately, or `Left(future)` in case we
    * have an asynchronous boundary.
    */
  def coeval(implicit s: Scheduler): Coeval[Either[CancelableFuture[A], A]] =
    Coeval.eval(runSyncMaybe(s))

  /** Returns a failed projection of this task.
    *
    * The failed projection is a `Task` holding a value of type `Throwable`, 
    * emitting the error yielded by the source, in case the source fails,
    * otherwise if the source succeeds the result will fail with a 
    * `NoSuchElementException`.
    */
  def failed: Task[Throwable] =
    transformWith(_ => Error(new NoSuchElementException("failed")), e => Now(e))
  
  /** Creates a new Task by applying a function to the successful result
    * of the source Task, and returns a task equivalent to the result
    * of the function.
    */
  def flatMap[B](f: A => Task[B]): Task[B] =
    FlatMap(this, f)

  /** Given a source Task that emits another Task, this function
    * flattens the result, returning a Task equivalent to the emitted
    * Task by the source.
    */
  def flatten[B](implicit ev: A <:< Task[B]): Task[B] =
    flatMap(a => a)

  /** Returns a task that waits for the specified `timespan` before
    * executing and mirroring the result of the source.
    *
    * @see [[delayExecutionWith]] for delaying the execution of the
    *     source with a customizable trigger.
    */
  def delayExecution(timespan: FiniteDuration): Task[A] =
    TaskDelayExecution(self, timespan)

  /** Returns a task that waits for the specified `trigger` to succeed
    * before mirroring the result of the source.
    *
    * If the `trigger` ends in error, then the resulting task will
    * also end in error.
    *
    * As an example, these are equivalent (in the observed effects and
    * result, not necessarily in implementation):
    * {{{
    *   val ta = source.delayExecution(10.seconds)
    *   val tb = source.delayExecutionWith(Task.unit.delayExecution(10.seconds))
    * }}}
    *
    * @see [[delayExecution]] for delaying the execution of the
    *     source with a simple timespan
    */
  def delayExecutionWith(trigger: Task[Any]): Task[A] =
    TaskDelayExecutionWith(self, trigger)

  /** Returns a task that executes the source immediately on `runAsync`,
    * but before emitting the `onSuccess` result for the specified
    * duration.
    *
    * Note that if an error happens, then it is streamed immediately
    * with no delay.
    *
    * @see [[delayResultBySelector]] for applying different
    *     delay strategies depending on the signaled result.
    */
  def delayResult(timespan: FiniteDuration): Task[A] =
    TaskDelayResult(self, timespan)

  /** Returns a task that executes the source immediately on `runAsync`,
    * but with the result delayed by the specified `selector`.
    *
    * The `selector` generates another `Task` whose execution will
    * delay the signaling of the result generated by the source.
    * Compared with [[delayResult]] this gives you an opportunity
    * to apply different delay strategies depending on the
    * signaled result.
    *
    * As an example, these are equivalent (in the observed effects
    * and result, not necessarily in implementation):
    * {{{
    *   val t1 = source.delayResult(10.seconds)
    *   val t2 = source.delayResultBySelector(_ =>
    *     Task.unit.delayExecution(10.seconds))
    * }}}
    *
    * Note that if an error happens, then it is streamed immediately
    * with no delay.
    *
    * @see [[delayResult]] for delaying with a simple timeout
    */
  def delayResultBySelector[B](selector: A => Task[B]): Task[A] =
    TaskDelayResultBySelector(self, selector)

  /** Mirrors the given source `Task`, but upon execution ensure that
    * evaluation forks into a separate (logical) thread spawned by the
    * given `scheduler`.
    *
    * The given [[monix.execution.Scheduler Scheduler]] will be used
    * for execution of the [[Task]], effectively overriding the
    * `Scheduler` that's passed in `runAsync`. Thus you can execute a
    * whole `Task` on a separate thread-pool, useful for example in
    * case of doing I/O.
    *
    * NOTE: the logic one cares about won't necessarily end up
    * executed on the given scheduler, or for transformations that
    * happen from here on. It all depends on what overrides or
    * asynchronous boundaries happen. But this function guarantees
    * that this `Task` run-loop begins executing on the given
    * scheduler.
    *
    * Alias for
    * [[Task.fork[A](fa:monix\.eval\.Task[A],scheduler* Task.fork(fa,scheduler)]].
    *
    * @param s is the scheduler to use for execution
    */
  def executeOn(s: Scheduler): Task[A] =
    Task.fork(this, s)

  /** Mirrors the given source `Task`, but upon execution ensure
    * that evaluation forks into a separate (logical) thread.
    *
    * The [[monix.execution.Scheduler Scheduler]] used will be
    * the one that is used to start the run-loop in `runAsync`.
    *
    * Alias for [[Task.fork[A](fa:monix\.eval\.Task[A])* Task.fork(fa)]].
    */
  def executeWithFork: Task[A] =
    Task.fork(this)

  /** Returns a new task that will execute the source with a different
    * [[monix.execution.ExecutionModel ExecutionModel]].
    *
    * This allows fine-tuning the options injected by the scheduler
    * locally. Example:
    *
    * {{{
    *   import monix.execution.ExecutionModel.AlwaysAsyncExecution
    *   task.executeWithModel(AlwaysAsyncExecution)
    * }}}
    *
    * @param em is the
    *        [[monix.execution.ExecutionModel ExecutionModel]]
    *        with which the source will get evaluated on `runAsync`
    */
  def executeWithModel(em: ExecutionModel): Task[A] =
    TaskExecuteWithModel(self, em)

  /** Returns a new task that will execute the source with a different
    * set of [[Task.Options Options]].
    *
    * This allows fine-tuning the default options. Example:
    *
    * {{{
    *   task.executeWithOptions(_.enableAutoCancelableRunLoops)
    * }}}
    *
    * @param f is a function that takes the source's current set of
    *        [[Task.Options options]] and returns a modified set of
    *        options that will be used to execute the source
    *        upon `runAsync`
    */
  def executeWithOptions(f: Options => Options): Task[A] =
    TaskExecuteWithOptions(self, f)

  /** Introduces an asynchronous boundary at the current stage in the
    * asynchronous processing pipeline.
    *
    * Consider the following example:
    *
    * {{{
    *   import monix.execution.Scheduler
    *   val io = Scheduler.io()
    *
    *   val source = Task(1).executeOn(io).map(_ + 1)
    * }}}
    *
    * That task is being forced to execute on the `io` scheduler,
    * including the `map` transformation that follows after
    * `executeOn`. But what if we want to jump with the execution
    * run-loop on the default scheduler for the following
    * transformations?
    *
    * Then we can do:
    *
    * {{{
    *   source.asyncBoundary.map(_ + 2)
    * }}}
    *
    * In this sample, whatever gets evaluated by the `source` will
    * happen on the `io` scheduler, however the `asyncBoundary` call
    * will make all subsequent operations to happen on the default
    * scheduler.
    */
  def asyncBoundary: Task[A] =
    self.flatMap(r => Task.forkedUnit.map(_ => r))

  /** Introduces an asynchronous boundary at the current stage in the
    * asynchronous processing pipeline, making processing to jump on
    * the given [[monix.execution.Scheduler Scheduler]] (until the
    * next async boundary).
    *
    * Consider the following example:
    * {{{
    *   import monix.execution.Scheduler
    *   val io = Scheduler.io()
    *
    *   val source = Task(1).executeOn(io).map(_ + 1)
    * }}}
    *
    * That task is being forced to execute on the `io` scheduler,
    * including the `map` transformation that follows after
    * `executeOn`. But what if we want to jump with the execution
    * run-loop on another scheduler for the following transformations?
    *
    * Then we can do:
    * {{{
    *   import monix.execution.Scheduler.global
    *
    *   source.asyncBoundary(global).map(_ + 2)
    * }}}
    *
    * In this sample, whatever gets evaluated by the `source` will
    * happen on the `io` scheduler, however the `asyncBoundary` call
    * will make all subsequent operations to happen on the specified
    * `global` scheduler.
    *
    * @param s is the scheduler triggering the asynchronous boundary
    */
  def asyncBoundary(s: Scheduler): Task[A] =
    self.flatMap(r => Task.forkedUnit.executeOn(s).map(_ => r))
  
  /** Returns a new task that upon evaluation will execute the given
    * function for the generated element, transforming the source into
    * a `Task[Unit]`.
    *
    * Similar in spirit with normal [[foreach]], but lazy, as
    * obviously nothing gets executed at this point.
    */
  def foreachL(f: A => Unit): Task[Unit] =
    self.map { a => f(a); () }

  /** Triggers the evaluation of the source, executing the given
    * function for the generated element.
    *
    * The application of this function has strict behavior, as the
    * task is immediately executed.
    */
  def foreach(f: A => Unit)(implicit s: Scheduler): CancelableFuture[Unit] =
    foreachL(f).runAsync(s)

  /** Returns a new Task that applies the mapping function to the
    * element emitted by the source.
    */
  def map[B](f: A => B): Task[B] =
    flatMap(a => try now(f(a)) catch { case NonFatal(ex) => raiseError(ex) })

  /** Returns a new `Task` in which `f` is scheduled to be run on
    * completion. This would typically be used to release any
    * resources acquired by this `Task`.
    *
    * The returned `Task` completes when both the source and the task
    * returned by `f` complete.
    *
    * NOTE: The given function is only called when the task is
    * complete.  However the function does not get called if the task
    * gets canceled.  Cancellation is a process that's concurrent with
    * the execution of a task and hence needs special handling.
    *
    * See [[doOnCancel]] for specifying a callback to call on
    * canceling a task.
    */
  def doOnFinish(f: Option[Throwable] => Task[Unit]): Task[A] =
    transformWith(
      a => f(None).map(_ => a),
      e => f(Some(e)).flatMap(_ => Error(e))
    )

  /** Returns a new `Task` that will mirror the source, but that will
    * execute the given `callback` if the task gets canceled before
    * completion.
    *
    * This only works for premature cancellation. See [[doOnFinish]]
    * for triggering callbacks when the source finishes.
    *
    * @param callback is the callback to execute if the task gets
    *        canceled prematurely
    */
  def doOnCancel(callback: Task[Unit]): Task[A] =
    TaskDoOnCancel(self, callback)

  /** Creates a new [[Task]] that will expose any triggered error from
    * the source.
    */
  def materialize: Task[Try[A]] =
    FlatMap(this, MaterializeTask.asInstanceOf[Transformation[A, Task[Try[A]]]])

  /** Dematerializes the source's result from a `Try`. */
  def dematerialize[B](implicit ev: A <:< Try[B]): Task[B] =
    self.asInstanceOf[Task[Try[B]]].flatMap(fromTry)

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
    self.transformWith(Task.Now.apply, f)

  /** Creates a new task that in case of error will fallback to the
    * given backup task.
    */
  def onErrorFallbackTo[B >: A](that: Task[B]): Task[B] =
    onErrorHandleWith(_ => that)

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
    transform(a => a, f)

  /** Creates a new task that on error will try to map the error
    * to another value using the provided partial function.
    *
    * See [[onErrorHandle]] for the version that takes a total function.
    */
  def onErrorRecover[U >: A](pf: PartialFunction[Throwable, U]): Task[U] =
    onErrorRecoverWith(pf.andThen(now))

  /** Memoizes (caches) the result of the source task and reuses it on
    * subsequent invocations of `runAsync`.
    *
    * The resulting task will be idempotent, meaning that
    * evaluating the resulting task multiple times will have the
    * same effect as evaluating it once.
    *
    * @see [[memoizeOnSuccess]] for a version that only caches
    *     successful results
    */
  def memoize: Task[A] =
    self match {
      case Now(_) | Error(_) =>
        self
      case Eval(f) =>
        f match {
          case _:Coeval.Once[_] => self
          case _ =>
            val coeval = Coeval.Once(f)
            Eval(coeval)
        }
      case ref: MemoizeSuspend[_] if ref.isCachingAll =>
        self
      case other =>
        new MemoizeSuspend[A](() => other, cacheErrors = true)
    }

  /** Memoizes (cache) the successful result of the source task
    * and reuses it on subsequent invocations of `runAsync`.
    * Thrown exceptions are not cached.
    *
    * The resulting task will be idempotent, but only if the
    * result is successful.
    *
    * @see [[memoize]] for a version that caches both successful
    *     results and failures
    */
  def memoizeOnSuccess: Task[A] =
    self match {
      case Now(_) | Error(_) =>
        self
      case Eval(f) =>
        val lf = LazyOnSuccess(f)
        if (lf eq f) self else Eval(lf)
      case _: MemoizeSuspend[_] =>
        self
      case other =>
        new MemoizeSuspend[A](() => other, cacheErrors = false)
    }

  /** Converts the source `Task` to a `cats.effect.IO` value. */
  def toIO(implicit s: Scheduler): IO[A] =
    TaskIOConversions.taskToIO(this)(s)

  /** Converts a [[Task]] to an `org.reactivestreams.Publisher` that
    * emits a single item on success, or just the error on failure.
    *
    * See [[http://www.reactive-streams.org/ reactive-streams.org]] for the
    * Reactive Streams specification.
    */
  def toReactivePublisher(implicit s: Scheduler): org.reactivestreams.Publisher[A @uV] =
    TaskToReactivePublisher[A](self)(s)

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

  /** Creates a new `Task` by applying the 'fa' function to the successful result of
    * this future, or the 'fe' function to the potential errors that might happen.
    *
    * This function is similar with [[map]], except that it can also transform
    * errors and not just successful results.
    *
    * @param fa function that transforms a successful result of the receiver
    * @param fe function that transforms an error of the receiver
    */
  def transform[R](fa: A => R, fe: Throwable => R): Task[R] =
    FlatMap(this, Transformation(fa, fe).andThen(Task.now))

  /** Creates a new `Task` by applying the 'fa' function to the successful result of
    * this future, or the 'fe' function to the potential errors that might happen.
    *
    * This function is similar with [[flatMap]], except that it can also transform
    * errors and not just successful results.
    *
    * @param fa function that transforms a successful result of the receiver
    * @param fe function that transforms an error of the receiver
    */
  def transformWith[R](fa: A => Task[R], fe: Throwable => Task[R]): Task[R] =
    FlatMap(this, Transformation(fa, fe))

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

  // ---- Deprecated operations, preserved for backwards compatibility

  /** Creates a new [[Task]] that will expose any triggered error from
    * the source.
    *
    * Deprecated, please use [[Task#attempt]] or [[Task#materialize]].
    *
    * The reason for the deprecation is the naming alignment
    * with the Cats ecosystem, where `Eager` is being used
    * as an alias for `Either[Throwable, A]`.
    */
  @deprecated("Use Task#attempt or Task#materialize", "2.3.0")
  def materializeAttempt: Task[Eager[A]] =
    self.transformWith(a => Task.now(Coeval.Now(a)), e => Task.now(Coeval.Error(e)))

  /** Dematerializes the source's result from an `Eager`.
    *
    * Deprecated, please use [[Task#dematerialize]] or just
    * [[Task#flatMap flatMap]].
    *
    * The reason for the deprecation is the naming alignment
    * with the Cats ecosystem, where `Eager` is being used
    * as an alias for `Either[Throwable, A]`.
    */
  @deprecated("Use Task#dematerialize or Task#flatMap", "2.3.0")
  def dematerializeAttempt[B](implicit ev: A <:< Eager[B]): Task[B] =
    self.asInstanceOf[Task[Eager[B]]].flatMap(Task.coeval)
}

/** Builders for [[Task]].
  *
  * @define createAsyncDesc Create a `Task` from an
  *         asynchronous computation, which takes the form of a
  *         function with which we can register a callback.
  *
  *         This can be used to translate from a callback-based API to
  *         a straightforward monadic version.
  *
  * @define registerParamDesc is a function that will be called when
  *         this `Task` is executed, receiving a callback as a
  *         parameter, a callback that the user is supposed to call in
  *         order to signal the desired outcome of this `Task`.
  */
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
    Task.Now(a)

  /** Lifts a value into the task context. Alias for [[now]]. */
  def pure[A](a: A): Task[A] = now(a)

  /** Returns a task that on execution is always finishing in error
    * emitting the specified exception.
    */
  def raiseError[A](ex: Throwable): Task[A] =
    Error(ex)

  /** Promote a non-strict value representing a Task to a Task of the
    * same type.
    */
  def defer[A](fa: => Task[A]): Task[A] =
    Suspend(fa _)

  /** Defers the creation of a `Task` by using the provided
    * function, which has the ability to inject a needed
    * [[monix.execution.Scheduler Scheduler]].
    *
    * Example:
    * {{{
    *   def measureLatency[A](source: Task[A]): Task[(A, Long)] =
    *     Task.deferAction { implicit s =>
    *       // We have our Scheduler, which can inject time, we
    *       // can use it for side-effectful operations
    *       val start = s.currentTimeMillis()
    *
    *       source.map { a =>
    *         val finish = s.currentTimeMillis()
    *         (a, finish - start)
    *       }
    *     }
    * }}}
    *
    * @param f is the function that's going to be called when the
    *        resulting `Task` gets evaluated
    */
  def deferAction[A](f: Scheduler => Task[A]): Task[A] =
    TaskDeferAction(f)

  /** Promote a non-strict Scala `Future` to a `Task` of the same type.
    *
    * The equivalent of doing:
    * {{{
    *   Task.defer(Task.fromFuture(fa))
    * }}}
    */
  def deferFuture[A](fa: => Future[A]): Task[A] =
    defer(fromFuture(fa))

  /** Wraps calls that generate `Future` results into [[Task]], provided 
    * a callback with an injected [[monix.execution.Scheduler Scheduler]]
    * to act as the necessary `ExecutionContext`.
    *
    * This builder helps with wrapping `Future`-enabled APIs that need
    * an implicit `ExecutionContext` to work. Consider this example:
    *
    * {{{
    *   import scala.concurrent.{ExecutionContext, Future}
    *
    *   def sumFuture(list: Seq[Int])(implicit ec: ExecutionContext): Future[Int] =
    *     Future(list.sum)
    * }}}
    *
    * We'd like to wrap this function into one that returns a lazy
    * `Task` that evaluates this sum every time it is called, because
    * that's how tasks work best. However in order to invoke this
    * function an `ExecutionContext` is needed:
    *
    * {{{
    *   def sumTask(list: Seq[Int])(implicit ec: ExecutionContext): Task[Int] =
    *     Task.deferFuture(sumFuture(list))
    * }}}
    *
    * But this is not only superfluous, but against the best practices
    * of using `Task`. The difference is that `Task` takes a
    * [[monix.execution.Scheduler Scheduler]] (inheriting from
    * `ExecutionContext`) only when [[monix.eval.Task!.runAsync(cb* runAsync]]
    * happens. But with `deferFutureAction` we get to have an injected
    * `Scheduler` in the passed callback:
    *
    * {{{
    *   def sumTask(list: Seq[Int]): Task[Int] =
    *     Task.deferFutureAction { implicit scheduler =>
    *       sumFuture(list)
    *     }
    * }}}
    *
    * @param f is the function that's going to be executed when the task
    *        gets evaluated, generating the wrapped `Future`
    */
  def deferFutureAction[A](f: Scheduler => Future[A]): Task[A] =
    TaskFromFuture.deferAction(f)

  /** Alias for [[defer]]. */
  def suspend[A](fa: => Task[A]): Task[A] =
    Suspend(fa _)

  /** Promote a non-strict value to a Task that is memoized on the first
    * evaluation, the result being then available on subsequent evaluations.
    */
  def evalOnce[A](a: => A): Task[A] = {
    val coeval = Coeval.Once(a _)
    Eval(coeval)
  }

  /** Promote a non-strict value to a Task, catching exceptions in the
    * process.
    *
    * Note that since `Task` is not memoized, this will recompute the
    * value each time the `Task` is executed.
    */
  def eval[A](a: => A): Task[A] =
    Eval(a _)

  /** Alias for [[eval]]. */
  def delay[A](a: => A): Task[A] = eval(a)

  /** A [[Task]] instance that upon evaluation will never complete. */
  def never[A]: Task[A] = neverRef

  /** Builds a [[Task]] instance out of a `cats.Eval`. */
  def fromEval[A](a: cats.Eval[A]): Task[A] =
    Coeval.fromEval(a).task

  /** Builds a [[Task]] instance out of a `cats.effect.IO`. */
  def fromIO[A](a: IO[A]): Task[A] =
    TaskIOConversions.taskFromIO(a)

  /** Builds a [[Task]] instance out of a Scala `Try`. */
  def fromTry[A](a: Try[A]): Task[A] =
    a match {
      case Success(v) => Now(v)
      case Failure(ex) => Error(ex)
    }

  /** Keeps calling `f` until it returns a `Right` result.
    *
    * Based on Phil Freeman's
    * [[http://functorial.com/stack-safety-for-free/index.pdf Stack Safety for Free]].
    */
  def tailRecM[A,B](a: A)(f: A => Task[Either[A,B]]): Task[B] =
    Task.defer(f(a)).flatMap {
      case Left(continueA) => tailRecM(continueA)(f)
      case Right(b) => Task.now(b)
    }

  /** A `Task[Unit]` provided for convenience. */
  final val unit: Task[Unit] = Now(())

  /** Transforms a [[Coeval]] into a [[Task]]. */
  def coeval[A](a: Coeval[A]): Task[A] = Eval(a)

  /** Mirrors the given source `Task`, but upon execution ensure
    * that evaluation forks into a separate (logical) thread.
    *
    * The [[monix.execution.Scheduler Scheduler]] used will be
    * the one that is used to start the run-loop in `runAsync`.
    *
    * @param fa is the task that will get executed asynchronously
    */
  def fork[A](fa: Task[A]): Task[A] =
    Task.forkedUnit.flatMap(_ => fa)

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
    Async { (context, cb) =>
      // Asynchronous boundary
      val newContext = context.copy(scheduler = scheduler)
      Task.unsafeStartAsync(fa, newContext, Callback.async(cb)(scheduler))
    }

  /** $createAsyncDesc
    *
    * Alias for [[Task.create]].
    *
    * @param register $registerParamDesc
    */
  def async[A](register: (Scheduler, Callback[A]) => Cancelable): Task[A] =
    create(register)

  /** $createAsyncDesc
    *
    * @param register $registerParamDesc
    */
  def create[A](register: (Scheduler, Callback[A]) => Cancelable): Task[A] =
    TaskCreate(register)

  /** Constructs a lazy [[Task]] instance whose result
    * will be computed asynchronously.
    *
    * **WARNING:** Unsafe to use directly, only use if you know
    * what you're doing. For building `Task` instances safely
    * see [[create Task.create]].
    *
    * Rules of usage:
    *
    *  - the received `StackedCancelable` can be used to store
    *    cancelable references that will be executed upon cancel;
    *    every `push` must happen at the beginning, before any
    *    execution happens and `pop` must happen afterwards
    *    when the processing is finished, before signaling the
    *    result
    *  - the received `FrameRef` indicates the current frame
    *    index and must be reset on real asynchronous boundaries
    *    (which avoids doing extra async boundaries in batched
    *    execution mode)
    *  - before execution, an asynchronous boundary is recommended,
    *    to avoid stack overflow errors, but can happen using the
    *    scheduler's facilities for trampolined execution
    *  - on signaling the result (`onSuccess`, `onError`),
    *    another async boundary is necessary, but can also
    *    happen with the scheduler's facilities for trampolined
    *    execution (e.g. `asyncOnSuccess` and `asyncOnError`)
    *
    * **WARNING:** note that not only is this builder unsafe, but also
    * unstable, as the [[OnFinish]] callback type is exposing volatile
    * internal implementation details. This builder is meant to create
    * optimized asynchronous tasks, but for normal usage prefer
    * [[Task.create]].
    */
  def unsafeCreate[A](onFinish: OnFinish[A]): Task[A] =
    Async(onFinish)

  /** Converts the given Scala `Future` into a `Task`.
    *
    * NOTE: if you want to defer the creation of the future, use
    * in combination with [[defer]].
    */
  def fromFuture[A](f: Future[A]): Task[A] =
    TaskFromFuture.strict(f)

  /** Creates a `Task` that upon execution will execute both given tasks
    * (possibly in parallel in case the tasks are asynchronous) and will
    * return the result of the task that manages to complete first,
    * along with a cancelable future of the other task.
    *
    * If the first task that completes
    */
  def chooseFirstOf[A,B](fa: Task[A], fb: Task[B]): Task[Either[(A, CancelableFuture[B]), (CancelableFuture[A], B)]] =
    TaskChooseFirstOf(fa, fb)

  /** Creates a `Task` that upon execution will return the result of the
    * first completed task in the given list and then cancel the rest.
    */
  def chooseFirstOfList[A](tasks: TraversableOnce[Task[A]]): Task[A] =
    TaskChooseFirstOfList(tasks)

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
    (implicit cbf: CanBuildFrom[M[Task[A]], A, M[A]]): Task[M[A]] =
    TaskSequence.list(in)(cbf)

  /** Given a `TraversableOnce[A]` and a function `A => Task[B]`, sequentially
   *  apply the function to each element of the collection and gather their
   *  results in the same collection.
   *
   *  It's a generalized version of [[sequence]].
   */
  def traverse[A, B, M[X] <: TraversableOnce[X]](in: M[A])(f: A => Task[B])
    (implicit cbf: CanBuildFrom[M[A], B, M[B]]): Task[M[B]] =
    TaskSequence.traverse(in, f)(cbf)

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
    (implicit cbf: CanBuildFrom[M[Task[A]], A, M[A]]): Task[M[A]] =
    TaskGather(in, () => cbf(in))

   /** Given a `TraversableOnce[A]` and a function `A => Task[B]`,
   * nondeterministically apply the function to each element of the collection
   * and return a task that will signal a collection of the results once all
   * tasks are finished.
   *
   * This function is the nondeterministic analogue of `traverse` and should
   * behave identically to `traverse` so long as there is no interaction between
   * the effects being gathered. However, unlike `traverse`, which decides on
   * a total order of effects, the effects in a `wander` are unordered with
   * respect to each other.
   *
   * Although the effects are unordered, we ensure the order of results
   * matches the order of the input sequence. Also see [[wanderUnordered]]
   * for the more efficient alternative.
   *
   * It's a generalized version of [[gather]].
   */
  def wander[A, B, M[X] <: TraversableOnce[X]](in: M[A])(f: A => Task[B])
    (implicit cbf: CanBuildFrom[M[A], B, M[B]]): Task[M[B]] =
    Task.eval(in.map(f)).flatMap(col => TaskGather(col, () => cbf(in)))

  /** Nondeterministically gather results from the given collection of tasks,
    * without keeping the original ordering of results.
    *
    * If the tasks in the list are set to execute asynchronously, forking
    * logical threads, then the tasks will execute in parallel.
    *
    * This function is similar to [[gather]], but neither the effects nor the
    * results will be ordered. Useful when you don't need ordering because:
    *
    *  - it has non-blocking behavior (but not wait-free)
    *  - it can be more efficient (compared with [[gather]]), but not
    *    necessarily (if you care about performance, then test)
    *
    * @param in is a list of tasks to execute
    */
  def gatherUnordered[A](in: TraversableOnce[Task[A]]): Task[List[A]] =
    TaskGatherUnordered(in)

  /** Given a `TraversableOnce[A]` and a function `A => Task[B]`,
    * nondeterministically apply the function to each element of the collection
    * without keeping the original ordering of the results.
    *
    * This function is similar to [[wander]], but neither the effects nor the
    * results will be ordered. Useful when you don't need ordering because:
    *
    *  - it has non-blocking behavior (but not wait-free)
    *  - it can be more efficient (compared with [[wander]]), but not
    *    necessarily (if you care about performance, then test)
    *
    * It's a generalized version of [[gatherUnordered]].
    */
  def wanderUnordered[A, B, M[X] <: TraversableOnce[X]](in: M[A])(f: A => Task[B]): Task[List[B]] =
    Task.eval(in.map(f)).flatMap(gatherUnordered)

  /** Apply a mapping functions to the results of two tasks, nondeterministically
    * ordering their effects.
    *
    * If the two tasks are synchronous, they'll get executed one
    * after the other, with the result being available asynchronously.
    * If the two tasks are asynchronous, they'll get scheduled for execution
    * at the same time and in a multi-threading environment they'll execute
    * in parallel and have their results synchronized.
    */
  def mapBoth[A1,A2,R](fa1: Task[A1], fa2: Task[A2])(f: (A1,A2) => R): Task[R] =
    TaskMapBoth(fa1, fa2)(f)

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

  /** A run-loop frame index is a number representing the current run-loop
    * cycle, being incremented whenever a `flatMap` evaluation happens.
    *
    * It gets used for automatically forcing asynchronous boundaries, according to the
    * [[monix.execution.ExecutionModel ExecutionModel]]
    * injected by the [[monix.execution.Scheduler Scheduler]] when
    * the task gets evaluated with `runAsync`.
    *
    * @see [[FrameIndexRef]]
    */
  type FrameIndex = Int

  /** Type alias representing callbacks for
    * [[unsafeCreate asynchronous]] tasks.
    */
  type OnFinish[+A] = (Context, Callback[A]) => Unit

  /** Set of options for customizing the task's behavior.
    *
    * @param autoCancelableRunLoops should be set to `true` in
    *        case you want `flatMap` driven loops to be
    *        auto-cancelable. Defaults to `false`.
    */
  final case class Options(
    autoCancelableRunLoops: Boolean) {

    /** Creates a new set of options from the source, but with
      * the [[autoCancelableRunLoops]] value set to `true`.
      */
    def enableAutoCancelableRunLoops: Options =
      copy(autoCancelableRunLoops = true)

    /** Creates a new set of options from the source, but with
      * the [[autoCancelableRunLoops]] value set to `false`.
      */
    def disableAutoCancelableRunLoops: Options =
      copy(autoCancelableRunLoops = false)
  }

  /** Default [[Options]] to use for [[Task]] evaluation,
    * thus:
    *
    *  - `autoCancelableRunLoops` is `false` by default
    *
    * On top of the JVM the default can be overridden by
    * setting the following system properties:
    *
    *  - `monix.environment.autoCancelableRunLoops`
    *    (`true`, `yes` or `1` for enabling)
    *
    * @see [[Task.Options]]
    */
  val defaultOptions: Options = {
    if (Platform.isJS)
      Options(autoCancelableRunLoops = false)
    else
      Options(
        autoCancelableRunLoops =
          Option(System.getProperty("monix.environment.autoCancelableRunLoops", ""))
            .map(_.toLowerCase)
            .exists(v => v == "yes" || v == "true" || v == "1")
      )
  }

  /** A reference that boxes a [[FrameIndex]] possibly using a thread-local.
    *
    * This definition is of interest only when creating
    * tasks with [[Task.unsafeCreate]], which exposes internals and
    * is considered unsafe to use.
    *
    * In case the [[Task]] is executed with
    * [[monix.execution.ExecutionModel.BatchedExecution BatchedExecution]],
    * this class boxes a [[FrameIndex]] in order to transport it over
    * light async boundaries, possibly using a
    * [[monix.execution.misc.ThreadLocal ThreadLocal]], since this
    * index is not supposed to survive when threads get forked.
    *
    * The [[FrameIndex]] is a counter that increments whenever a
    * `flatMap` operation is evaluated. And with `BatchedExecution`,
    * whenever that counter exceeds the specified threshold, an
    * asynchronous boundary is automatically inserted. However this
    * capability doesn't blend well with light asynchronous
    * boundaries, for example `Async` tasks that never fork logical threads or
    * [[monix.execution.schedulers.TrampolinedRunnable TrampolinedRunnable]]
    * instances executed by capable schedulers. This is why
    * [[FrameIndexRef]] is part of the [[Context]] of execution for
    * [[Task]], available for asynchronous tasks that get created with
    * [[Task.unsafeCreate]].
    *
    * Note that in case the execution model is not
    * [[monix.execution.ExecutionModel.BatchedExecution BatchedExecution]]
    * then this reference is just a dummy, since there's no point in
    * keeping a counter around, plus setting and fetching from a
    * `ThreadLocal` can be quite expensive.
    */
  sealed abstract class FrameIndexRef {
    /** Returns the current [[FrameIndex]]. */
    def apply(): FrameIndex

    /** Stores a new [[FrameIndex]]. */
    def `:=`(update: FrameIndex): Unit

    /** Resets the stored [[FrameIndex]] to 1, which is the
      * default value that should be used after an asynchronous
      * boundary happened.
      */
    def reset(): Unit
  }

  object FrameIndexRef {
    /** Builds a [[FrameIndexRef]]. */
    def apply(em: ExecutionModel): FrameIndexRef =
      em match {
        case AlwaysAsyncExecution | SynchronousExecution => Dummy
        case BatchedExecution(_) => new Local
      }

    // Keeps our frame index in a thread-local
    private final class Local extends FrameIndexRef {
      private[this] val local = ThreadLocal(1)
      def apply(): FrameIndex = local.get()
      def `:=`(update: FrameIndex): Unit = local.set(update)
      def reset(): Unit = local.reset()
    }

    // Dummy implementation that doesn't do anything
    private object Dummy extends FrameIndexRef {
      def apply(): FrameIndex = 1
      def `:=`(update: FrameIndex): Unit = ()
      def reset(): Unit = ()
    }
  }

  /** The `Context` under which [[Task]] is supposed to be executed.
    *
    * This definition is of interest only when creating
    * tasks with [[Task.unsafeCreate]], which exposes internals and
    * is considered unsafe to use.
    *
    * @param scheduler is the [[monix.execution.Scheduler Scheduler]]
    *        in charge of evaluation on `runAsync`.
    * 
    * @param connection is the
    *        [[monix.execution.cancelables.StackedCancelable StackedCancelable]] 
    *        that handles the cancellation on `runAsync`
    * 
    * @param frameRef is a thread-local counter that keeps track
    *        of the current frame index of the run-loop. The run-loop
    *        is supposed to force an asynchronous boundary upon
    *        reaching a certain threshold, when the task is evaluated
    *        with
    *        [[monix.execution.ExecutionModel.BatchedExecution]].
    *        And this `frameIndexRef` should be reset whenever a real
    *        asynchronous boundary happens.
    *
    *        See the description of [[FrameIndexRef]].
    *
    * @param options is a set of options for customizing the task's
    *        behavior upon evaluation.
    */
  final case class Context(
    scheduler: Scheduler,
    connection: StackedCancelable,
    frameRef: FrameIndexRef,
    options: Options) {

    /** Helper that returns the
      * [[monix.execution.ExecutionModel ExecutionModel]]
      * specified by the [[scheduler]].
      */
    @inline def executionModel: ExecutionModel =
      scheduler.executionModel

    /** Helper that returns `true` if the current `Task` run-loop
      * should be canceled or `false` otherwise.
      */
    @inline def shouldCancel: Boolean =
      options.autoCancelableRunLoops &&
      connection.isCanceled
  }

  object Context {
    /** Initialize fresh [[Context]] reference. */
    def apply(s: Scheduler): Context = {
      val conn = StackedCancelable()
      val em = s.executionModel
      val frameRef = FrameIndexRef(em)
      Context(s, conn, frameRef, defaultOptions)
    }
  }

  /** [[Task]] state describing an immediate synchronous value. */
  private[eval] final case class Now[A](value: A) extends Task[A] {
    // Optimization to avoid the run-loop
    override def runAsync(cb: Callback[A])(implicit s: Scheduler): Cancelable = {
      if (s.executionModel != AlwaysAsyncExecution) cb.onSuccess(value)
      else s.executeAsync(() => cb.onSuccess(value))
      Cancelable.empty
    }

    // Optimization to avoid the run-loop
    override def runAsync(implicit s: Scheduler): CancelableFuture[A] =
      CancelableFuture.successful(value)

    override def toString: String =
      s"Task.Now($value)"
  }

  /** [[Task]] state describing an immediate exception. */
  private[eval] final case class Error[A](ex: Throwable) extends Task[A] {
    // Optimization to avoid the run-loop
    override def runAsync(cb: Callback[A])(implicit s: Scheduler): Cancelable = {
      if (s.executionModel != AlwaysAsyncExecution) cb.onError(ex)
      else s.executeAsync(() => cb.onError(ex))
      Cancelable.empty
    }

    // Optimization to avoid the run-loop
    override def runAsync(implicit s: Scheduler): CancelableFuture[A] =
      CancelableFuture.failed(ex)

    override def toString: String =
      s"Task.Error($ex)"
  }

  /** [[Task]] state describing an immediate synchronous value. */
  private[eval] final case class Eval[A](thunk: () => A) extends Task[A] {
    override def toString: String =
      s"Task.Eval($thunk)"
  }

  /** Internal state, the result of [[Task.defer]] */
  private[eval] final case class Suspend[+A](thunk: () => Task[A]) extends Task[A] {
    override def toString: String =
      s"Task.Suspend($thunk)"
  }

  /** Internal [[Task]] state that is the result of applying `flatMap`. */
  private[eval] final case class FlatMap[A, B](source: Task[A], f: A => Task[B]) extends Task[B] {
    override def toString: String =
      s"Task.FlatMap(Task@${System.identityHashCode(source)}, $f)"
  }

  /** Constructs a lazy [[Task]] instance whose result will
    * be computed asynchronously.
    *
    * Unsafe to build directly, only use if you know what you're doing.
    * For building `Async` instances safely, see [[create]].
    */
  private[eval] final case class Async[+A](onFinish: OnFinish[A]) extends Task[A] {
    override def toString: String =
      s"Task.Async($onFinish)"
  }

  /** Internal [[Task]] state that defers the evaluation of the
    * given [[Task]] and upon execution memoize its result to
    * be available for later evaluations.
    */
  private[eval] final class MemoizeSuspend[A](
    f: () => Task[A],
    private[eval] val cacheErrors: Boolean)
    extends Task[A] { self =>

    private[eval] var thunk: () => Task[A] = f
    private[eval] val state = Atomic(null : AnyRef)

    /* Constructor provided for keeping binary backwards compatibility. */
    def this(f: () => Task[A]) = this(f, cacheErrors = true)

    def isCachingAll: Boolean =
      cacheErrors

    def value: Option[Try[A]] =
      state.get match {
        case null => None
        case (p: Promise[_], _) =>
          p.asInstanceOf[Promise[A]].future.value
        case result: Try[_] =>
          Some(result.asInstanceOf[Try[A]])
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
          cb(result.asInstanceOf[Try[A]])
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


    override def toString: String =
      s"Task.MemoizeSuspend(${state.get})"
  }

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
  def unsafeStartAsync[A](source: Task[A], context: Context, cb: Callback[A]): Unit =
    TaskRunLoop.restartAsync(source, context, cb, null, null)

  /** Unsafe utility - starts the execution of a Task with a guaranteed
    * [[monix.execution.schedulers.TrampolinedRunnable trampolined asynchronous boundary]],
    * by providing the needed [[monix.execution.Scheduler Scheduler]],
    * [[monix.execution.cancelables.StackedCancelable StackedCancelable]]
    * and [[Callback]].
    *
    * DO NOT use directly, as it is UNSAFE to use, unless you know
    * what you're doing. Prefer [[Task.runAsync(cb* Task.runAsync]]
    * and `Task.fork`.
    */
  def unsafeStartTrampolined[A](source: Task[A], context: Context, cb: Callback[A]): Unit =
    context.scheduler.executeTrampolined { () =>
      TaskRunLoop.startWithCallback(source, context, cb, null, null, context.frameRef())
    }

  /** Unsafe utility - starts the execution of a Task, by providing
    * the needed [[monix.execution.Scheduler Scheduler]],
    * [[monix.execution.cancelables.StackedCancelable StackedCancelable]]
    * and [[Callback]].
    *
    * DO NOT use directly, as it is UNSAFE to use, unless you know
    * what you're doing. Prefer [[Task.runAsync(cb* Task.runAsync]].
    */
  def unsafeStartNow[A](source: Task[A], context: Context, cb: Callback[A]): Unit =
    TaskRunLoop.startWithCallback(source, context, cb, null, null, context.frameRef())

  private[this] final val neverRef: Async[Nothing] =
    Async((_,_) => ())

  /** Reusable task instance used in `Task#asyncBoundary` and `Task#fork` */
  private final val forkedUnit =
    Async[Unit] { (context, cb) =>
      context.scheduler.executeAsync(() => cb.onSuccess(()))
    }

  /** Used as optimization by [[Task.attempt]]. */
  private object AttemptTask extends Transformation[Any, Task[Either[Throwable, Any]]] {
    override def success(a: Any): Task[Either[Throwable, Any]] =
      Task.now(Right(a))
    override def error(e: Throwable): Task[Either[Throwable, Any]] =
      Task.now(Left(e))
  }

  /** Used as optimization by [[Task.materialize]]. */
  private object MaterializeTask extends Transformation[Any, Task[Try[Any]]] {
    override def success(a: Any): Task[Try[Any]] =
      Task.now(Success(a))
    override def error(e: Throwable): Task[Try[Any]] =
      Task.now(Failure(e))
  }
}

private[eval] trait TaskInstances extends TaskInstances2 {
  /** Type class instances of [[Task]] for Cats.
    *
    * $strategyNote
    *
    * @param as $strategyParamDesc
    */
  @inline implicit def catsAsync
    (implicit as: ApplicativeStrategy[Task]): CatsAsyncInstances[Task] = {

    as match {
      case ApplicativeStrategy.Sequential =>
        CatsAsyncInstances.ForTask
      case ApplicativeStrategy.Parallel =>
        CatsAsyncInstances.ForParallelTask
    }
  }
}

/** @define strategyNote In order to determine the returned instance to do
  *         parallel processing in `cats.Applicative` then import
  *         [[Task.nondeterminism]] in scope:
  *
  *         $nondeterminismSample
  *
  * @define strategyParamDesc is the applicative strategy to use when calling
  *         `map2`, `ap` or `product`, specifying whether the returned
  *         instance should do sequential or parallel processing.
  */
private[eval] trait TaskInstances2 extends TaskInstances1 {
  /** Type class instances of [[Task]] for `cats.effect.Effect`.
    *
    * $strategyNote
    *
    * @param as $strategyParamDesc
    * @param s is the `Scheduler` to use when executing `Effect#runAsync`
    */
  @inline implicit def catsEffect
    (implicit as: ApplicativeStrategy[Task], s: Scheduler): CatsEffectInstances[Task] = {

    as match {
      case ApplicativeStrategy.Sequential =>
        new CatsEffectInstances.ForTask()(s)
      case ApplicativeStrategy.Parallel =>
        new CatsEffectInstances.ForParallelTask()(s)
    }
  }
}

/** @define nondeterminismSample
  *         {{{
  *           import monix.eval.Task.nondeterminism
  *
  *           // The Task's Applicative will now do parallel processing
  *           // if the given tasks are forking (logical) threads
  *           // (e.g. will use `Task.mapBoth`)
  *           val ap = implicitly[cats.Applicative[Task]]
  *           ap.map2(task1, task2) { (a, b) => a + b }
  *
  *           // Or using the "applicative builder" syntax:
  *           import cats.syntax.all._
  *           (task1 |@| task2).map { (a, b) => a + b }
  *         }}}
  */
private[eval] trait TaskInstances1 extends TaskInstances0 {
  /** An [[monix.eval.instances.ApplicativeStrategy ApplicativeStrategy]]
    * instance that will determine the generated type class instances
    * for `Task` to do sequential processing in their applicative
    * (and thus to order both results and effects).
    *
    * This is the default strategy that `Task` uses.
    *
    * @see [[Task.nondeterminism]] for picking a strategy for applicative
    *      that does unordered effects and thus capable of parallelism
    */
  @inline implicit def determinism: ApplicativeStrategy[Task] =
    ApplicativeStrategy.Sequential
}


/** @define nondeterminismSample
  *         {{{
  *           import monix.eval.Task.nondeterminism
  *
  *           // The Task's Applicative will now do parallel processing
  *           // if the given tasks are forking (logical) threads
  *           // (e.g. will use `Task.mapBoth`)
  *           val ap = implicitly[cats.Applicative[Task]]
  *           ap.map2(task1, task2) { (a, b) => a + b }
  *
  *           // Or using the "applicative builder" syntax:
  *           import cats.syntax.all._
  *           (task1 |@| task2).map { (a, b) => a + b }
  *         }}}
  */
private[eval] trait TaskInstances0 {
  /** An [[monix.eval.instances.ApplicativeStrategy ApplicativeStrategy]] 
    * instance that, when imported in scope, will determine the
    * generated type class instances for `Task` to do parallel
    * processing in their applicative.
    *
    * Importing this in scope overrides the default
    * [[Task.determinism]] strategy.
    *
    * $nondeterminismSample
    */
  @inline implicit def nondeterminism: ApplicativeStrategy[Task] =
    ApplicativeStrategy.Parallel
}
