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

import cats.effect._
import cats.{Monoid, Semigroup}
import monix.eval.instances._
import monix.eval.internal._
import monix.execution.ExecutionModel.{AlwaysAsyncExecution, BatchedExecution, SynchronousExecution}
import monix.execution._
import monix.execution.cancelables.StackedCancelable
import monix.execution.internal.Platform.fusionMaxStackDepth
import monix.execution.internal.{Newtype1, Platform}
import monix.execution.misc.ThreadLocal
import monix.execution.schedulers.{CanBlock, TracingScheduler, TrampolinedRunnable}

import scala.annotation.unchecked.{uncheckedVariance => uV}
import scala.collection.generic.CanBuildFrom
import scala.concurrent.duration.{Duration, FiniteDuration, TimeUnit}
import scala.concurrent.{ExecutionContext, Future, TimeoutException}
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
  * =Getting Started=
  *
  * To build a `Task` from a by-name parameters (thunks), we can use
  * [[monix.eval.Task.eval Task.eval]] or
  * [[monix.eval.Task.apply Task.apply]]:
  *
  * {{{
  *   val hello = Task.eval("Hello ")
  *   val world = Task.evalAsync("World!")
  * }}}
  *
  * Nothing gets executed yet, as `Task` is lazy, nothing executes
  * until you trigger [[Task!.runAsync(implicit* .runAsync]] on it.
  *
  * To combine `Task` values we can use [[Task!.map .map]] and
  * [[Task!.flatMap .flatMap]], which describe sequencing and this time
  * it's in a very real sense because of the laziness involved:
  *
  * {{{
  *   val sayHello = hello
  *     .flatMap(h => world.map(w => h + w))
  *     .map(println)
  * }}}
  *
  * This `Task` reference will trigger a side effect on evaluation, but
  * not yet. To make the above print its message:
  *
  * {{{
  *   import monix.execution.CancelableFuture
  *
  *   val f: CancelableFuture[Unit] = sayHello.runAsync
  *   //=> Hello World!
  * }}}
  *
  * The returned type is a
  * [[monix.execution.CancelableFuture CancelableFuture]] which
  * inherits from Scala's standard [[scala.concurrent.Future Future]],
  * a value that can be completed already or might be completed at
  * some point in the future, once the running asynchronous process
  * finishes. Such a future value can also be canceled, see below.
  *
  * =Laziness=
  *
  * The fact that `Task` is lazy whereas `Future` is not
  * has real consequences. For example with `Task` you can do this:
  *
  * {{{
  *   def retryOnFailure[A](times: Int, source: Task[A]): Task[A] =
  *     source.onErrorRecoverWith { err =>
  *       // No more retries left? Re-throw error:
  *       if (times <= 0) Task.raiseError(err) else {
  *         // Recursive call, yes we can!
  *         retryOnFailure(times - 1, source)
  *           // Adding 500 ms delay for good measure
  *           .delayExecution(500)
  *       }
  *     }
  * }}}
  *
  * `Future` being a strict value-wannabe means that the actual value
  * gets "memoized" (means cached), however `Task` is basically a function
  * that can be repeated for as many times as you want. `Task` can also
  * do memoization of course:
  *
  * {{{
  * task.memoize
  * }}}
  *
  * The difference between this and just calling `runAsync()` is that
  * `memoize()` still returns a `Task` and the actual memoization
  * happens on the first `runAsync()` (with idempotency guarantees of
  * course).
  *
  * But here's something else that the `Future` data type cannot do:
  *
  * {{{
  * task.memoizeOnSuccess
  * }}}
  *
  * This keeps repeating the computation for as long as the result is a
  * failure and caches it only on success. Yes we can!
  *
  * ==Parallelism==
  *
  * Because of laziness, invoking
  * [[monix.eval.Task.sequence Task.sequence]] will not work like
  * it does for `Future.sequence`, the given `Task` values being
  * evaluated one after another, in ''sequence'', not in ''parallel''.
  * If you want parallelism, then you need to use
  * [[monix.eval.Task.gather Task.gather]] and thus be explicit about it.
  *
  * This is great because it gives you the possibility of fine tuning the
  * execution. For example, say you want to execute things in parallel,
  * but with a maximum limit of 30 tasks being executed in parallel.
  * One way of doing that is to process your list in batches:
  *
  * {{{
  *   // Some array of tasks, you come up with something good :-)
  *   val list: Seq[Task[Int]] = ???
  *
  *   // Split our list in chunks of 30 items per chunk,
  *   // this being the maximum parallelism allowed
  *   val chunks = list.sliding(30, 30)
  *
  *   // Specify that each batch should process stuff in parallel
  *   val batchedTasks = chunks.map(chunk => Task.gather(chunk))
  *   // Sequence the batches
  *   val allBatches = Task.sequence(batchedTasks)
  *
  *   // Flatten the result, within the context of Task
  *   val all: Task[Seq[Int]] = allBatches.map(_.flatten)
  * }}}
  *
  * Note that the built `Task` reference is just a specification at
  * this point, or you can view it as a function, as nothing has
  * executed yet, you need to call
  * [[Task!.runAsync(implicit* .runAsync]] explicitly.
  *
  * =Cancellation=
  *
  * The logic described by an `Task` task could be cancelable,
  * depending on how the `Task` gets built.
  *
  * [[monix.execution.CancelableFuture CancelableFuture]] references
  * can also be canceled, in case the described computation can be
  * canceled. When describing `Task` tasks with `Task.eval` nothing
  * can be cancelled, since there's nothing about a plain function
  * that you can cancel, but we can build cancelable tasks with
  * [[monix.eval.Task.cancelableS[A](register* Task.cancelable]].
  *
  * {{{
  *   import scala.concurrent.duration._
  *
  *   val delayedHello = Task.cancelable { (scheduler, callback) =>
  *     val task = scheduler.scheduleOnce(1.second) {
  *       println("Delayed Hello!")
  *       // Signaling successful completion
  *       callback(Success(()))
  *     }
  *
  *     Cancelable { () => {
  *       println("Cancelling!")
  *       task.cancel()
  *     }
  *   }
  * }}}
  *
  * The sample above prints a message with a delay, where the delay
  * itself is scheduled with the injected `Scheduler`. The `Scheduler`
  * is in fact an implicit parameter to `runAsync()`.
  *
  * This action can be cancelled, because it specifies cancellation
  * logic. In case we have no cancelable logic to express, then it's
  * OK if we returned a
  * [[monix.execution.Cancelable.empty Cancelable.empty]] reference,
  * in which case the resulting `Task` would not be cancelable.
  *
  * But the `Task` we just described is cancelable, for one at the
  * edge, due to `runAsync` returning [[monix.execution.Cancelable Cancelable]]
  * and [[monix.execution.CancelableFuture CancelableFuture]] references:
  *
  * {{{
  *   // Triggering execution
  *   val f: CancelableFuture[Unit] = delayedHello.runAsync
  *
  *   // If we change our mind before the timespan has passed:
  *   f.cancel()
  * }}}
  *
  * But also cancellation is described on `Task` as a pure action,
  * which can be used for example in [[monix.eval.Task.race race]] conditions:
  *
  * {{{
  *   import scala.concurrent.duration._
  *
  *   val ta = Task(1 + 1).delayExecution(4.seconds)
  *   
  *   val tb = Task.raiseError(new TimeoutException)
  *     .delayExecution(4.seconds)
  *
  *   Task.racePair(ta, tb).flatMap {
  *     case Left((a, fiberB)) =>
  *       fiberB.cancel.map(_ => a)
  *     case Right((fiberA, b)) =>
  *       fiberA.cancel.map(_ => b)
  *   }
  * }}}
  *
  * The returned type in `racePair` is [[Fiber]], which is a data
  * type that's meant to wrap tasks linked to an active process
  * and that can be [[Fiber.cancel canceled]] or [[Fiber.join joined]].
  *
  * Also, given a task, we can specify actions that need to be
  * triggered in case of cancellation, see
  * [[monix.eval.Task!.doOnCancel doOnCancel]]:
  *
  * {{{
  *   val task = Task.eval(println("Hello!")).executeAsync
  *
  *   task doOnCancel Task.eval {
  *     println("A cancellation attempt was made!")
  *   }
  * }}}
  *
  * Controlling cancellation can be achieved with
  * [[monix.eval.Task!.cancelable cancelable]] and
  * [[monix.eval.Task!.uncancelable uncancelable]].
  *
  * The former activates
  * [[monix.eval.Task.Options.autoCancelableRunLoops auto-cancelable flatMap chains]],
  * whereas the later ensures that a task becomes uncancelable such that
  * it gets executed as an atomic unit (either all or nothing).
  *
  * =Note on the ExecutionModel=
  *
  * `Task` is conservative in how it introduces async boundaries.
  * Transformations like `map` and `flatMap` for example will default
  * to being executed on the current call stack on which the
  * asynchronous computation was started. But one shouldn't make
  * assumptions about how things will end up executed, as ultimately
  * it is the implementation's job to decide on the best execution
  * model. All you are guaranteed (and can assume) is asynchronous
  * execution after executing `runAsync`.
  *
  * Currently the default
  * [[monix.execution.ExecutionModel ExecutionModel]] specifies
  * batched execution by default and `Task` in its evaluation respects
  * the injected `ExecutionModel`. If you want a different behavior,
  * you need to execute the `Task` reference with a different scheduler.
  *
  * @define runAsyncOptDesc Triggers the asynchronous execution,
  *         much like normal `runAsync`, but includes the ability
  *         to specify [[monix.eval.Task.Options Options]] that
  *         can modify the behavior of the run-loop.
  *
  * @define runAsyncDesc Triggers the asynchronous execution.
  *
  *         Without invoking `runAsync` on a `Task`, nothing
  *         gets evaluated, as a `Task` has lazy behavior.
  *
  * @define schedulerDesc is an injected
  *         [[monix.execution.Scheduler Scheduler]] that gets used
  *         whenever asynchronous boundaries are needed when
  *         evaluating the task
  *
  * @define schedulerEvalDesc is the
  *         [[monix.execution.Scheduler Scheduler]] needed in order
  *         to evaluate the source, being required in Task's
  *         [[runAsync(implicit* runAsync]]
  *
  * @define callbackDesc is a callback that will be invoked upon
  *         completion
  *
  * @define cancelableDesc a [[monix.execution.Cancelable Cancelable]]
  *         that can be used to cancel a running task
  *
  * @define optionsDesc a set of [[monix.eval.Task.Options Options]]
  *         that determine the behavior of Task's run-loop.
  *
  * @define startInspiration Inspired by
  *         [[https://github.com/functional-streams-for-scala/fs2 FS2]],
  *         with the difference that this method does not fork
  *         automatically, being consistent with Monix's default
  *         behavior.
  *
  * @define runSyncUnsafeDesc Evaluates the source task synchronously and
  *         returns the result immediately or blocks the underlying thread
  *         until the result is ready.
  *
  *         '''WARNING:''' blocking operations are unsafe and incredibly error
  *         prone on top of the JVM. It's a good practice to not block any threads
  *         and use the asynchronous `runAsync` methods instead.
  *
  *         In general prefer to use the asynchronous
  *         [[monix.eval.Task!.runAsync(implicit* .runAsync]] and to
  *         structure your logic around asynchronous actions in a
  *         non-blocking way. But in case you're blocking only once,
  *         in `main`, at the "edge of the world" so to speak, then
  *         it's OK.
  *
  *         Sample:
  *         {{{
  *           import scala.concurrent.duration._
  *
  *           task.runSyncUnsafe(3.seconds)
  *         }}}
  *
  *         This is equivalent with:
  *         {{{
  *           import scala.concurrent.Await
  *
  *           Await.result(task.runAsync, 3.seconds)
  *         }}}
  *
  *         Some implementation details:
  *
  *          - blocking the underlying thread is done by triggering Scala's
  *            `BlockingContext` (`scala.concurrent.blocking`), just like
  *            Scala's `Await.result`
  *          - the `timeout` is mandatory, just like when using Scala's
  *            `Await.result`, in order to make the caller aware that the
  *            operation is dangerous and that setting a `timeout` is good
  *            practice
  *          - the loop starts in an execution mode that ignores
  *            [[monix.execution.ExecutionModel.BatchedExecution BatchedExecution]] or
  *            [[monix.execution.ExecutionModel.AlwaysAsyncExecution AlwaysAsyncExecution]],
  *            until the first asynchronous boundary. This is because we want to block
  *            the underlying thread for the result, in which case preserving
  *            fairness by forcing (batched) async boundaries doesn't do us any good,
  *            quite the contrary, the underlying thread being stuck until the result
  *            is available or until the timeout exception gets triggered.
  *
  *         Not supported on top of JavaScript engines and trying to use it
  *         with Scala.js will trigger a compile time error.
  *
  *         For optimizations on top of JavaScript you can use [[runSyncMaybe]]
  *         instead.
  *
  * @define runSyncUnsafeTimeout is a duration that specifies the
  *         maximum amount of time that this operation is allowed to block the
  *         underlying thread. If the timeout expires before the result is ready,
  *         a `TimeoutException` gets thrown. Note that you're allowed to
  *         pass an infinite duration (with `Duration.Inf`), but unless
  *         it's `main` that you're blocking and unless you're doing it only
  *         once, then this is definitely not recommended — provide a finite
  *         timeout in order to avoid deadlocks.
  *
  * @define runSyncUnsafePermit is an implicit value that's only available for
  *         the JVM and not for JavaScript, its purpose being to stop usage of
  *         this operation on top of engines that do not support blocking threads.
  *
  * @define runSyncMaybeDesc Tries to execute the source synchronously.
  *
  *         As an alternative to `runAsync`, this method tries to execute
  *         the source task immediately on the current thread and call-stack.
  *
  *         WARNING: This method is a partial function, throwing exceptions
  *         in case errors happen immediately (synchronously).
  *
  *         Usage sample:
  *         {{{
  *           try task.runSyncMaybe match {
  *             case Right(a) => println("Success: " + a)
  *             case Left(future) =>
  *               future.onComplete {
  *                 case Success(a) => println("Async success: " + a)
  *                 case Failure(e) => println("Async error: " + e)
  *               }
  *           } catch {
  *             case NonFatal(e) =>
  *               println("Error: " + e)
  *           }
  *         }}}
  *
  *         Obviously the purpose of this method is to be used for
  *         optimizations.
  *
  *         Also see [[runSyncUnsafe]], the blocking execution mode that can
  *         only work on top of the JVM.
  *
  * @define runSyncMaybeReturn `Right(result)` in case a result was processed,
  *         or `Left(future)` in case an asynchronous boundary
  *         was hit and further async execution is needed
  *
  * @define bracketErrorNote '''NOTE on error handling''': one big
  *         difference versus `try {} finally {}` is that, in case
  *         both the `release` function and the `use` function throws,
  *         the error raised by `use` gets signaled and the error
  *         raised by `release` gets reported with `System.err` for
  *         [[Coeval]] or with
  *         [[monix.execution.Scheduler.reportFailure Scheduler.reportFailure]]
  *         for [[Task]].
  *
  *         For example:
  *
  *         {{{
  *           Task.evalAsync("resource").bracket { _ =>
  *             // use
  *             Task.raiseError(new RuntimeException("Foo"))
  *           } { _ =>
  *             // release
  *             Task.raiseError(new RuntimeException("Bar"))
  *           }
  *         }}}
  *
  *         In this case the error signaled downstream is `"Foo"`,
  *         while the `"Bar"` error gets reported. This is consistent
  *         with the behavior of Haskell's `bracket` operation and NOT
  *         with `try {} finally {}` from Scala, Java or JavaScript.
  *
  * @define unsafeRun '''UNSAFE''' — this operation can trigger the
  *         execution of side effects, which break referential
  *         transparency and is thus not a pure function.
  *
  *         In FP code use with care, suspended in another `Task`
  *         or [[monix.eval.Coeval Coeval]], or at the edge of the
  *         FP program.
  *
  * @define memoizeCancel '''Cancellation''' — a memoized task will mirror
  *         the behavior of the source on cancellation. This means that:
  *
  *          - if the source isn't cancellable, then the resulting memoized
  *            task won't be cancellable either
  *          - if the source is cancellable, then the memoized task can be
  *            cancelled, which can take unprepared users by surprise
  *
  *         Depending on use-case, there are two ways to ensure no surprises:
  *
  *          - usage of [[onCancelRaiseError]], before applying memoization, to
  *            ensure that on cancellation an error is triggered and then noticed
  *            by the memoization logic
  *          - usage of [[uncancelable]], either before or after applying
  *            memoization, to ensure that the memoized task cannot be cancelled
  *
  * @define memoizeUnsafe '''UNSAFE''' — this operation allocates a shared,
  *         mutable reference, which can break in certain cases
  *         referential transparency, even if this operation guarantees
  *         idempotency (i.e. referential transparency implies idempotency,
  *         but idempotency does not imply referential transparency).
  *
  *         The allocation of a mutable reference is known to be a
  *         side effect, thus breaking referential transparency,
  *         even if calling this method does not trigger the evaluation
  *         of side effects suspended by the source.
  *
  *         Use with care. Sometimes it's easier to just keep a shared,
  *         memoized reference to some connection, but keep in mind
  *         it might be better to pass such a reference around as
  *         a parameter.
  */
sealed abstract class Task[+A] extends TaskBinCompat[A] with Serializable {
  import monix.eval.Task._
  import cats.effect.Async

  /** $runAsyncDesc
    *
    * $unsafeRun
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
    TaskRunLoop.startFuture(this, s, defaultOptions)

  /** $runAsyncDesc
    *
    * @param cb $callbackDesc
    * @param s $schedulerDesc
    * @return $cancelableDesc
    */
  def runAsync(cb: Callback[A])(implicit s: Scheduler): Cancelable =
    TaskRunLoop.startLight(this, s, defaultOptions, cb)

  /** $runAsyncOptDesc
    *
    * @param s $schedulerDesc
    * @param opts $optionsDesc
    * @return $cancelableDesc
    */
  def runAsyncOpt(implicit s: Scheduler, opts: Options): CancelableFuture[A] =
    TaskRunLoop.startFuture(this, s, opts)

  /** $runAsyncOptDesc
    *
    * @param cb $callbackDesc
    * @param s $schedulerDesc
    * @param opts $optionsDesc
    * @return $cancelableDesc
    */
  def runAsyncOpt(cb: Callback[A])(implicit s: Scheduler, opts: Options): Cancelable =
    TaskRunLoop.startLight(this, s, opts, cb)

  /** $runSyncMaybeDesc
    *
    * @param s $schedulerDesc
    * @return $runSyncMaybeReturn
    */
  final def runSyncMaybe(implicit s: Scheduler): Either[CancelableFuture[A], A] =
    runSyncMaybeOpt(s, defaultOptions)

  /** $runSyncMaybeDesc
    *
    * @param s $schedulerDesc
    * @param opts $optionsDesc
    * @return $runSyncMaybeReturn
    */
  final def runSyncMaybeOpt(implicit s: Scheduler, opts: Options): Either[CancelableFuture[A], A] = {
    val future = runAsyncOpt(s, opts)
    future.value match {
      case Some(value) =>
        value match {
          case Success(a) => Right(a)
          case Failure(e) => throw e
        }
      case None =>
        Left(future)
    }
  }

  /** $runSyncUnsafeDesc
    *
    * @param timeout $runSyncUnsafeTimeout
    * @param s $schedulerDesc
    * @param permit $runSyncUnsafePermit
    */
  final def runSyncUnsafe(timeout: Duration)
    (implicit s: Scheduler, permit: CanBlock): A =
    /*_*/
    TaskRunSyncUnsafe(this, timeout, s, defaultOptions)
    /*_*/

  /** $runSyncUnsafeDesc
    *
    * @param timeout $runSyncUnsafeTimeout
    * @param s $schedulerDesc
    * @param opts $optionsDesc
    * @param permit $runSyncUnsafePermit
    */
  final def runSyncUnsafeOpt(timeout: Duration)
    (implicit s: Scheduler, opts: Options, permit: CanBlock): A =
    /*_*/
    TaskRunSyncUnsafe(this, timeout, s, opts)
    /*_*/

  /** Similar to Scala's `Future#onComplete`, this method triggers
    * the evaluation of a `Task` and invokes the given callback whenever
    * the result is available.
    *
    * @param f $callbackDesc
    * @param s $schedulerDesc
    * @return $cancelableDesc
    */
  final def runOnComplete(f: Try[A] => Unit)(implicit s: Scheduler): Cancelable =
    runAsync(new Callback[A] {
      def onSuccess(value: A): Unit = f(Success(value))
      def onError(ex: Throwable): Unit = f(Failure(ex))
    })(s)

  /** Memoizes (caches) the result of the source task and reuses it on
    * subsequent invocations of `runAsync`.
    *
    * The resulting task will be idempotent, meaning that
    * evaluating the resulting task multiple times will have the
    * same effect as evaluating it once.
    *
    * $memoizeCancel
    *
    * Example:
    * {{{
    *   import scala.concurrent.CancellationException
    *
    *   val source = Task(1).delayExecution(5.seconds)
    *
    *   // Option 1: trigger error on cancellation
    *   val err = new CancellationException
    *   val cached1 = source.onCancelRaiseError(err).memoize
    *
    *   // Option 2: make it uninterruptible
    *   val cached2 = source.uncancelable.memoize
    * }}}
    *
    * When using [[onCancelRaiseError]] like in the example above, the
    * behavior of `memoize` is to cache the error. If you want the ability
    * to retry errors until a successful value happens, see [[memoizeOnSuccess]].
    *
    * $memoizeUnsafe
    *
    * @see [[memoizeOnSuccess]] for a version that only caches
    *     successful results
    *
    * @return a `Task` that can be used to wait for the memoized value
    */
  final def memoize: Task[A] =
    TaskMemoize(this, cacheErrors = true)

  /** Memoizes (cache) the successful result of the source task
    * and reuses it on subsequent invocations of `runAsync`.
    * Thrown exceptions are not cached.
    *
    * The resulting task will be idempotent, but only if the
    * result is successful.
    *
    * $memoizeCancel
    *
    * Example:
    * {{{
    *   import scala.concurrent.CancellationException
    *
    *   val source = Task(1).delayExecution(5.seconds)
    *
    *   // Option 1: trigger error on cancellation
    *   val err = new CancellationException
    *   val cached1 = source.onCancelRaiseError(err).memoizeOnSuccess
    *
    *   // Option 2: make it uninterruptible
    *   val cached2 = source.uncancelable.memoizeOnSuccess
    * }}}
    *
    * When using [[onCancelRaiseError]] like in the example above, the
    * behavior of `memoizeOnSuccess` is to retry the source on subsequent
    * invocations. Use [[memoize]] if that's not the desired behavior.
    *
    * $memoizeUnsafe
    *
    * @see [[memoize]] for a version that caches both successful
    *     results and failures
    *
    * @return a `Task` that can be used to wait for the memoized value
    */
  final def memoizeOnSuccess: Task[A] =
    TaskMemoize(this, cacheErrors = false)

  /** Creates a new [[Task]] that will expose any triggered error
    * from the source.
    */
  final def attempt: Task[Either[Throwable, A]] =
    FlatMap(this, AttemptTask.asInstanceOf[A => Task[Either[Throwable, A]]])

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
  final def asyncBoundary: Task[A] =
    flatMap(a => Task.shift.map(_ => a))

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
  final def asyncBoundary(s: Scheduler): Task[A] =
    flatMap(a => Task.shift(s).map(_ => a))

  /** Returns a task that treats the source task as the acquisition of a resource,
    * which is then exploited by the `use` function and then `released`.
    *
    * The `bracket` operation is the equivalent of the
    * `try {} catch {} finally {}` statements from mainstream languages.
    *
    * The `bracket` operation installs the necessary exception handler to release
    * the resource in the event of an exception being raised during the computation,
    * or in case of cancellation.
    *
    * If an exception is raised, then `bracket` will re-raise the exception
    * ''after'' performing the `release`. If the resulting task gets cancelled,
    * then `bracket` will still perform the `release`, but the yielded task
    * will be non-terminating (equivalent with [[Task.never]]).
    *
    * Example:
    *
    * {{{
    *   import java.io._
    *
    *   def readFile(file: File): Task[String] = {
    *     // Opening a file handle for reading text
    *     val acquire = Task.eval(new BufferedReader(
    *       new InputStreamReader(new FileInputStream(file), "utf-8")
    *     ))
    *
    *     acquire.bracket { in =>
    *       // Usage part
    *       Task.eval {
    *         // Yes, ugly Java, non-FP loop;
    *         // side-effects are suspended though
    *         var line: String = null
    *         val buff = new StringBuilder()
    *         do {
    *           line = in.readLine()
    *           if (line != null) buff.append(line)
    *         } while (line != null)
    *         buff.toString()
    *       }
    *     } { in =>
    *       // The release part
    *       Task.eval(in.close())
    *     }
    *   }
    * }}}
    *
    * Note that in case of cancellation the underlying implementation cannot
    * guarantee that the computation described by `use` doesn't end up
    * executed concurrently with the computation from `release`. In the example
    * above that ugly Java loop might end up reading from a `BufferedReader`
    * that is already closed due to the task being cancelled, thus triggering
    * an error in the background with nowhere to go but in
    * [[monix.execution.Scheduler.reportFailure Scheduler.reportFailure]].
    *
    * In this particular example, given that we are just reading from a file,
    * it doesn't matter. But in other cases it might matter, as concurrency
    * on top of the JVM when dealing with I/O might lead to corrupted data.
    *
    * For those cases you might want to do synchronization (e.g. usage of
    * locks and semaphores) and you might want to use [[bracketE]], the
    * version that allows you to differentiate between normal termination
    * and cancellation.
    *
    * $bracketErrorNote
    *
    * @see [[bracketCase]] and [[bracketE]]
    *
    * @param use is a function that evaluates the resource yielded by the source,
    *        yielding a result that will get generated by the task returned
    *        by this `bracket` function
    *
    * @param release is a function that gets called after `use` terminates,
    *        either normally or in error, or if it gets cancelled, receiving
    *        as input the resource that needs to be released
    */
  final def bracket[B](use: A => Task[B])(release: A => Task[Unit]): Task[B] =
    bracketE(use)((a, _) => release(a))

  /** Returns a new task that treats the source task as the
    * acquisition of a resource, which is then exploited by the `use`
    * function and then `released`, with the possibility of
    * distinguishing between normal termination and cancelation, such
    * that an appropriate release of resources can be executed.
    *
    * The `bracketCase` operation is the equivalent of
    * `try {} catch {} finally {}` statements from mainstream languages
    * when used for the acquisition and release of resources.
    *
    * The `bracketCase` operation installs the necessary exception handler
    * to release the resource in the event of an exception being raised
    * during the computation, or in case of cancelation.
    *
    * In comparison with the simpler [[bracket]] version, this one
    * allows the caller to differentiate between normal termination,
    * termination in error and cancelation via an `ExitCase`
    * parameter.
    *
    * @see [[bracket]] and [[bracketE]]
    *
    * @param use is a function that evaluates the resource yielded by
    *        the source, yielding a result that will get generated by
    *        this function on evaluation
    *
    * @param release is a function that gets called after `use`
    *        terminates, either normally or in error, or if it gets
    *        canceled, receiving as input the resource that needs that
    *        needs release, along with the result of `use`
    *        (cancelation, error or successful result)
    */
  final def bracketCase[B](use: A => Task[B])(release: (A, ExitCase[Throwable]) => Task[Unit]): Task[B] =
    TaskBracket.exitCase(this, use, release)

  /** Returns a task that treats the source task as the acquisition of a resource,
    * which is then exploited by the `use` function and then `released`, with
    * the possibility of distinguishing between normal termination and cancellation,
    * such that an appropriate release of resources can be executed.
    *
    * The `bracketE` operation is the equivalent of `try {} catch {} finally {}`
    * statements from mainstream languages.
    *
    * The `bracketE` operation installs the necessary exception handler to release
    * the resource in the event of an exception being raised during the computation,
    * or in case of cancellation.
    *
    * In comparison with the simpler [[bracket]] version, this one allows the
    * caller to differentiate between normal termination and cancellation.
    *
    * The `release` function receives as input:
    *
    *  - `Left(None)` in case of cancellation
    *  - `Left(Some(error))` in case `use` terminated with an error
    *  - `Right(b)` in case of success
    *
    * $bracketErrorNote
    *
    * @see [[bracket]] and [[bracketCase]]
    *
    * @param use is a function that evaluates the resource yielded by the source,
    *        yielding a result that will get generated by this function on
    *        evaluation
    *
    * @param release is a function that gets called after `use` terminates,
    *        either normally or in error, or if it gets cancelled, receiving
    *        as input the resource that needs that needs release, along with
    *        the result of `use` (cancellation, error or successful result)
    */
  final def bracketE[B](use: A => Task[B])(release: (A, Either[Option[Throwable], B]) => Task[Unit]): Task[B] =
    TaskBracket.either(this, use, release)

  /** Transforms a [[Task]] into a [[Coeval]] that tries to execute the
    * source synchronously, returning either `Right(value)` in case a
    * value is available immediately, or `Left(future)` in case we
    * have an asynchronous boundary or an error.
    */
  final def coeval(implicit s: Scheduler): Coeval[Either[CancelableFuture[A], A]] =
    Coeval.eval(runSyncMaybe(s))

  /** Returns a task that waits for the specified `timespan` before
    * executing and mirroring the result of the source.
    *
    * In this example we're printing to standard output, but before
    * doing that we're introducing a 3 seconds delay:
    *
    * {{{
    *   Task(println("Hello!"))
    *     .delayExecution(3.seconds)
    * }}}
    *
    * This operation is also equivalent with:
    *
    * {{{
    *   Task.sleep(timespan).flatMap(_ => task)
    * }}}
    *
    * See [[Task.sleep]] for the operation that describes the effect
    * and [[Task.delayResult]] for the version that evaluates the
    * task on time, but delays the signaling of the result.
    *
    * @param timespan is the time span to wait before triggering
    *        the evaluation of the task
    */
  final def delayExecution(timespan: FiniteDuration): Task[A] =
    Task.sleep(timespan).flatMap(_ => this)

  /** Returns a task that executes the source immediately on `runAsync`,
    * but before emitting the `onSuccess` result for the specified
    * duration.
    *
    * Note that if an error happens, then it is streamed immediately
    * with no delay.
    *
    * See [[delayExecution]] for delaying the evaluation of the
    * task with the specified duration. The [[delayResult]] operation
    * is effectively equivalent with:
    *
    * {{{
    *   task.flatMap(a => Task.now(a).delayExecution(timespan))
    * }}}
    *
    * Or if we are to use the [[Task.sleep]] describing just the
    * effect, this operation is equivalent with:
    *
    * {{{
    *   task.flatMap(a => Task.sleep(timespan).map(_ => a))
    * }}}
    *
    * Thus in this example 3 seconds will pass before the result
    * is being generated by the source, plus another 5 seconds
    * before it is finally emitted:
    *
    * {{{
    *   Task.eval(1 + 1)
    *     .delayExecution(3.seconds)
    *     .delayResult(5.seconds)
    * }}}
    *
    * @param timespan is the time span to sleep before signaling
    *        the result, but after the evaluation of the source
    */
  final def delayResult(timespan: FiniteDuration): Task[A] =
    flatMap(a => Task.sleep(timespan).map(_ => a))

  /** Overrides the default [[monix.execution.Scheduler Scheduler]],
    * possibly forcing an asynchronous boundary before execution
    * (if `forceAsync` is set to `true`, the default).
    *
    * When a `Task` is executed with [[Task.runAsync(implicit* .runAsync]],
    * it needs a `Scheduler`, which is going to be injected in all
    * asynchronous tasks processed within the `flatMap` chain,
    * a `Scheduler` that is used to manage asynchronous boundaries
    * and delayed execution.
    *
    * This scheduler passed in `runAsync` is said to be the "default"
    * and `executeOn` overrides that default.
    *
    * {{{
    *   import monix.execution.Scheduler
    *   import java.io.{BufferedReader, FileInputStream, InputStreamReader}
    *
    *   /** Reads the contents of a file using blocking I/O. */
    *   def readFile(path: String): Task[String] = Task.eval {
    *     val in = new BufferedReader(
    *       new InputStreamReader(new FileInputStream(path), "utf-8"))
    *
    *     val buffer = new StringBuffer()
    *     var line: String = null
    *     do {
    *       line = in.readLine()
    *       if (line != null) buffer.append(line)
    *     } while (line != null)
    *
    *     buffer.toString
    *   }
    *
    *   // Building a Scheduler meant for blocking I/O
    *   val io = Scheduler.io()
    *
    *   // Building the Task reference, specifying that `io` should be
    *   // injected as the Scheduler for managing async boundaries
    *   readFile("path/to/file").executeOn(io, forceAsync = true)
    * }}}
    *
    * In this example we are using [[Task.eval]], which executes the
    * given `thunk` immediately (on the current thread and call stack).
    *
    * By calling `executeOn(io)`, we are ensuring that the used
    * `Scheduler` (injected in [[Task.cancelableS[A](register* async tasks]])
    * will be `io`, a `Scheduler` that we intend to use for blocking
    * I/O actions. And we are also forcing an asynchronous boundary
    * right before execution, by passing the `forceAsync` parameter as
    * `true` (which happens to be the default value).
    *
    * Thus, for our described function that reads files using Java's
    * blocking I/O APIs, we are ensuring that execution is entirely
    * managed by an `io` scheduler, executing that logic on a thread
    * pool meant for blocking I/O actions.
    *
    * Note that in case `forceAsync = false`, then the invocation will
    * not introduce any async boundaries of its own and will not
    * ensure that execution will actually happen on the given
    * `Scheduler`, that depending of the implementation of the `Task`.
    * For example:
    *
    * {{{
    *   Task.eval("Hello, " + "World!")
    *     .executeOn(io, forceAsync = false)
    * }}}
    *
    * The evaluation of this task will probably happen immediately
    * (depending on the configured
    * [[monix.execution.ExecutionModel ExecutionModel]]) and the
    * given scheduler will probably not be used at all.
    *
    * However in case we would use [[Task.apply]], which ensures
    * that execution of the provided thunk will be async, then
    * by using `executeOn` we'll indeed get a logical fork on
    * the `io` scheduler:
    *
    * {{{
    *   Task("Hello, " + "World!").executeOn(io, forceAsync = false)
    * }}}
    *
    * Also note that overriding the "default" scheduler can only
    * happen once, because it's only the "default" that can be
    * overridden.
    *
    * Something like this won't have the desired effect:
    *
    * {{{
    *   val io1 = Scheduler.io()
    *   val io2 = Scheduler.io()
    *
    *   task.executeOn(io1).executeOn(io2)
    * }}}
    *
    * In this example the implementation of `task` will receive
    * the reference to `io1` and will use it on evaluation, while
    * the second invocation of `executeOn` will create an unnecessary
    * async boundary (if `forceAsync = true`) or be basically a
    * costly no-op. This might be confusing but consider the
    * equivalence to these functions:
    *
    * {{{
    *   import scala.concurrent.ExecutionContext
    *
    *   val io1 = Scheduler.io()
    *   val io2 = Scheduler.io()
    *
    *   def sayHello(ec: ExecutionContext): Unit =
    *     ec.execute(new Runnable {
    *       def run() = println("Hello!")
    *     })
    *
    *   def sayHello2(ec: ExecutionContext): Unit =
    *     // Overriding the default `ec`!
    *     sayHello(io1)
    *
    *   def sayHello3(ec: ExecutionContext): Unit =
    *     // Overriding the default no longer has the desired effect
    *     // because sayHello2 is ignoring it!
    *     sayHello2(io2)
    * }}}
    *
    * @param s is the [[monix.execution.Scheduler Scheduler]] to use
    *        for overriding the default scheduler and for forcing
    *        an asynchronous boundary if `forceAsync` is `true`
    * @param forceAsync indicates whether an asynchronous boundary
    *        should be forced right before the evaluation of the
    *        `Task`, managed by the provided `Scheduler`
    * @return a new `Task` that mirrors the source on evaluation,
    *         but that uses the provided scheduler for overriding
    *         the default and possibly force an extra asynchronous
    *         boundary on execution
    */
  final def executeOn(s: Scheduler, forceAsync: Boolean = true): Task[A] =
    TaskExecuteOn(this, s, forceAsync)

  /** Mirrors the given source `Task`, but upon execution ensure
    * that evaluation forks into a separate (logical) thread.
    *
    * The [[monix.execution.Scheduler Scheduler]] used will be
    * the one that is used to start the run-loop in
    * [[Task.runAsync(implicit* .runAsync]].
    *
    * This operation is equivalent with:
    *
    * {{{
    *   Task.shift.flatMap(_ => task)
    *
    *   // ... or ...
    *
    *   import cats.syntax.all._
    *
    *   Task.shift.followedBy(task)
    * }}}
    *
    * The [[monix.execution.Scheduler Scheduler]] used for scheduling
    * the async boundary will be the default, meaning the one used to
    * start the run-loop in `runAsync`.
    */
  final def executeAsync: Task[A] =
    Task.shift.flatMap(_ => this)

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
  final def executeWithModel(em: ExecutionModel): Task[A] =
    TaskExecuteWithModel(this, em)

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
  final def executeWithOptions(f: Options => Options): Task[A] =
    TaskExecuteWithOptions(this, f)

  /** Returns a new task that is cancelable.
    *
    * Normally Monix Tasks have these characteristics:
    *
    *  - `flatMap` chains are not cancelable by default
    *  - when creating [[Task.cancelableS[A](register* async tasks]]
    *    the user has to specify explicit cancellation logic
    *
    * This operation returns a task that has [[Task.Options.autoCancelableRunLoops]]
    * enabled upon evaluation, thus being equivalent with:
    * {{{
    *   task.executeWithOptions(_.enableAutoCancelableRunLoops)
    * }}}
    *
    * What this does is two-fold:
    *
    *  - `flatMap` chains become cancelable on async boundaries, which works in
    *    combination with [[monix.execution.ExecutionModel.BatchedExecution BatchedExecution]]
    *    that's enabled by default (injected by [[monix.execution.Scheduler Scheduler]],
    *    but can also be changed with [[executeWithModel]])
    *  - even if the source task cannot be cancelled, upon completion the result
    *    is not allowed to be streamed and the continuation is not allowed to execute
    *
    * For example this is a function that calculates the n-th Fibonacci element:
    * {{{
    *   def fib(n: Int): Task[Long] = {
    *     def loop(n: Int, a: Long, b: Long): Task[Long] =
    *       Task.suspend {
    *         if (n > 0)
    *           loop(n - 1, b, a + b)
    *         else
    *           Task.now(a)
    *       }
    *
    *     loop(n, 0, 1).cancelable
    *   }
    * }}}
    *
    * Normally this isn't cancelable and it might take a long time, but
    * by calling `autoCancelable` on the result, we ensure that when cancellation
    * is observed, at async boundaries, the loop will stop with the task
    * becoming a non-terminating one.
    *
    * This operation represents the opposite of [[uncancelable]]. And note
    * that it works even for tasks that are uncancelable / atomic, because
    * it blocks the rest of the `flatMap` loop from executing, functioning
    * like a sort of cancellation boundary:
    *
    * {{{
    *   Task.evalAsync(println("Hello ..."))
    *     .cancelable
    *     .flatMap(_ => Task.eval(println("World!")))
    * }}}
    *
    * Normally [[Task.apply]] does not yield a cancelable task, but by applying
    * the `autoCancelable` transformation to it, the `println` will execute,
    * but not the subsequent `flatMap` operation.
    */
  def autoCancelable: Task[A] =
    TaskCancellation.makeCancelable(this)

  /** Returns a failed projection of this task.
    *
    * The failed projection is a `Task` holding a value of type `Throwable`,
    * emitting the error yielded by the source, in case the source fails,
    * otherwise if the source succeeds the result will fail with a
    * `NoSuchElementException`.
    */
  final def failed: Task[Throwable] =
    Task.FlatMap(this, Task.Failed)

  /** Creates a new Task by applying a function to the successful result
    * of the source Task, and returns a task equivalent to the result
    * of the function.
    */
  final def flatMap[B](f: A => Task[B]): Task[B] =
    FlatMap(this, f)

  /** Given a source Task that emits another Task, this function
    * flattens the result, returning a Task equivalent to the emitted
    * Task by the source.
    */
  final def flatten[B](implicit ev: A <:< Task[B]): Task[B] =
    flatMap(a => a)

  /** Returns a new task that upon evaluation will execute the given
    * function for the generated element, transforming the source into
    * a `Task[Unit]`.
    *
    * Similar in spirit with normal [[foreach]], but lazy, as
    * obviously nothing gets executed at this point.
    */
  final def foreachL(f: A => Unit): Task[Unit] =
    this.map { a => f(a); () }

  /** Triggers the evaluation of the source, executing the given
    * function for the generated element.
    *
    * The application of this function has strict behavior, as the
    * task is immediately executed.
    *
    * Exceptions in `f` are reported using provided (implicit) Scheduler
    */
  final def foreach(f: A => Unit)(implicit s: Scheduler): Unit =
    runAsync.foreach(f)

  /** Returns a new `Task` that repeatedly executes the source as long
    * as it continues to succeed. It never produces a terminal value.
    *
    * Example:
    *
    * {{{
    *   import scala.concurrent.duration._
    *
    *   Task.eval(println("Tick!"))
    *     .delayExecution(1.second)
    *     .loopForever
    * }}}
    *
    */
  final def loopForever: Task[Nothing] =
    flatMap(_ => this.loopForever)

  /** Start asynchronous execution of the source suspended in the `Task` context.
    *
    * This can be used for non-deterministic / concurrent execution.
    * The following code is more or less equivalent with
    * [[Task.parMap2]] (minus the behavior on error handling and
    * cancellation, plus forced async execution):
    *
    * {{{
    *   def par2[A, B](ta: Task[A], tb: Task[B]): Task[(A, B)] =
    *     for {
    *       fa <- ta.fork
    *       fb <- tb.fork
    *        a <- fa.join
    *        b <- fb.join
    *     } yield (a, b)
    * }}}
    *
    * Note in such a case usage of [[Task.parMap2 parMap2]]
    * (and [[Task.parMap3 parMap3]], etc.) is still recommended
    * because of behavior on error and cancellation — consider that
    * in the example above, if the first task finishes in error,
    * the second task doesn't get cancelled.
    *
    * IMPORTANT — this operation forces an asynchronous boundary before
    * execution, as in general this law holds:
    * {{{
    *   fa.fork <-> fa.executeAsync.start
    * }}}
    *
    * See [[start]] for the equivalent that does not start the task with
    * a forced async boundary.
    */
  final def fork: Task[Fiber[A @uV]] =
    TaskStart.forked(this)

  /** Start asynchronous execution of the source suspended in the `Task` context,
    * running it in the background and discarding the result.
    *
    * Similar to [[fork]] after mapping result to Unit. Below law holds:
    *
    * {{{
    *   task.forkAndForget <-> task.fork.map(_ => ())
    * }}}
    *
    */
  final def forkAndForget: Task[Unit] =
    TaskForkAndForget(this)

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
  final def doOnFinish(f: Option[Throwable] => Task[Unit]): Task[A] =
    Task.FlatMap(this, new Task.DoOnFinish[A](f))

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
  final def doOnCancel(callback: Task[Unit]): Task[A] =
    TaskDoOnCancel(this, callback)

  /** Creates a new [[Task]] that will expose any triggered error from
    * the source.
    */
  final def materialize: Task[Try[A]] =
    FlatMap(this, MaterializeTask.asInstanceOf[A => Task[Try[A]]])

  /** Dematerializes the source's result from a `Try`. */
  final def dematerialize[B](implicit ev: A <:< Try[B]): Task[B] =
    this.asInstanceOf[Task[Try[B]]].flatMap(fromTry)

  /** Returns a new task that mirrors the source task for normal termination,
    * but that triggers the given error on cancellation.
    *
    * Normally tasks that are cancelled become non-terminating.
    * Here's an example of a cancelable task:
    *
    * {{{
    *   val tenSecs = Task.sleep(10)
    *   val task = tenSecs.fork.flatMap { fa =>
    *     // Triggering pure cancellation, then trying to get its result
    *     fa.cancel.flatMap(_ => fa)
    *   }
    *
    *   task.timeout(10.seconds).runAsync
    *   //=> throws TimeoutException
    * }}}
    *
    * In general you can expect cancelable tasks to become non-terminating on
    * cancellation.
    *
    * This `onCancelRaiseError` operator transforms a task that would yield
    * [[Task.never]] on cancellation into one that yields [[Task.raiseError]].
    *
    * Example:
    * {{{
    *   import java.util.concurrent.CancellationException
    *
    *   val tenSecs = Task.sleep(10.seconds)
    *     .onCancelRaiseError(new CancellationException)
    *
    *   val task = tenSecs.fork.flatMap { fa =>
    *     // Triggering pure cancellation, then trying to get its result
    *     fa.cancel.flatMap(_ => fa)
    *   }
    *
    *   task.runAsync
    *   // => CancellationException
    * }}}
    */
  final def onCancelRaiseError(e: Throwable): Task[A] =
    TaskCancellation.raiseError(this, e)

  /** Creates a new task that will try recovering from an error by
    * matching it with another task using the given partial function.
    *
    * See [[onErrorHandleWith]] for the version that takes a total function.
    */
  final def onErrorRecoverWith[B >: A](pf: PartialFunction[Throwable, Task[B]]): Task[B] =
    onErrorHandleWith(ex => pf.applyOrElse(ex, raiseConstructor))

  /** Creates a new task that will handle any matching throwable that
    * this task might emit by executing another task.
    *
    * See [[onErrorRecoverWith]] for the version that takes a partial function.
    */
  final def onErrorHandleWith[B >: A](f: Throwable => Task[B]): Task[B] =
    FlatMap(this, new StackFrame.ErrorHandler(f, nowConstructor))

  /** Creates a new task that in case of error will fallback to the
    * given backup task.
    */
  final def onErrorFallbackTo[B >: A](that: Task[B]): Task[B] =
    onErrorHandleWith(_ => that)

  /** Given a predicate function, keep retrying the
    * task until the function returns true.
    */
  final def restartUntil(p: A => Boolean): Task[A] =
    this.flatMap(a => if (p(a)) now(a) else this.restartUntil(p))

  /** Returns a new `Task` that applies the mapping function to
    * the element emitted by the source.
    *
    * Can be used for specifying a (lazy) transformation to the result
    * of the source.
    *
    * This equivalence with [[flatMap]] always holds:
    *
    * ```scala
    * fa.map(f) <-> fa.flatMap(x => Task.pure(f(x)))
    * ```
    */
  final def map[B](f: A => B): Task[B] =
    this match {
      case Map(source, g, index) =>
        // Allowed to do a fixed number of map operations fused before
        // resetting the counter in order to avoid stack overflows;
        // See `monix.execution.internal.Platform` for details.
        if (index != fusionMaxStackDepth) Map(source, g.andThen(f), index + 1)
        else Map(this, f, 0)
      case _ =>
        Map(this, f, 0)
    }

  /** Creates a new task that in case of error will retry executing the
    * source again and again, until it succeeds.
    *
    * In case of continuous failure the total number of executions
    * will be `maxRetries + 1`.
    */
  final def onErrorRestart(maxRetries: Long): Task[A] =
    this.onErrorHandleWith(ex =>
      if (maxRetries > 0) this.onErrorRestart(maxRetries-1)
      else raiseError(ex))

  /** Creates a new task that in case of error will retry executing the
    * source again and again, until it succeeds, or until the given
    * predicate returns `false`.
    *
    * In this sample we retry for as long as the exception is a `TimeoutException`:
    * {{{
    *   task.onErrorRestartIf {
    *     case _: TimeoutException => true
    *     case _ => false
    *   }
    * }}}
    *
    * @param p is the predicate that is executed if an error is thrown and
    *        that keeps restarting the source for as long as it returns `true`
    */
  final def onErrorRestartIf(p: Throwable => Boolean): Task[A] =
    this.onErrorHandleWith(ex => if (p(ex)) this.onErrorRestartIf(p) else raiseError(ex))

  /** On error restarts the source with a customizable restart loop.
    *
    * This operation keeps an internal `state`, with a start value, an internal
    * state that gets evolved and based on which the next step gets decided,
    * e.g. should it restart, maybe with a delay, or should it give up and
    * re-throw the current error.
    *
    * Example that implements a simple retry policy that retries for a maximum
    * of 10 times before giving up; also introduce a 1 second delay before
    * each retry is executed:
    *
    * {{{
    *   import scala.concurrent.duration._
    *
    *   task.onErrorRestartLoop(10) { (err, maxRetries, retry) =>
    *     if (maxRetries > 0)
    *       // Next retry please; but do a 1 second delay
    *       retry(maxRetries - 1).delayExecution(1.second)
    *     else
    *       // No retries left, rethrow the error
    *       Task.raiseError(err)
    *   }
    * }}}
    *
    * A more complex exponential back-off sample:
    *
    * {{{
    *   import scala.concurrent.duration._
    *
    *   // Keeps the current state, indicating the restart delay and the
    *   // maximum number of retries left
    *   final case class Backoff(maxRetries: Int, delay: FiniteDuration)
    *
    *   // Restarts for a maximum of 10 times, with an initial delay of 1 second,
    *   // a delay that keeps being multiplied by 2
    *   task.onErrorRestartLoop(Backoff(10, 1.second)) { (err, state, retry) =>
    *     val Backoff(maxRetries, delay) = state
    *     if (maxRetries > 0)
    *       retry(Backoff(maxRetries - 1, delay * 2)).delayExecution(delay)
    *     else
    *       // No retries left, rethrow the error
    *       Task.raiseError(err)
    *   }
    * }}}
    *
    * The given function injects the following parameters:
    *
    *  1. `error` reference that was thrown
    *  2. the current `state`, based on which a decision for the retry is made
    *  3. `retry: S => Task[B]` function that schedules the next retry
    *
    * @param initial is the initial state used to determine the next on error
    *        retry cycle
    * @param f is a function that injects the current error, state, a
    *        function that can signal a retry is to be made and returns
    *        the next task
    */
  final def onErrorRestartLoop[S, B >: A](initial: S)(f: (Throwable, S, S => Task[B]) => Task[B]): Task[B] =
    onErrorHandleWith(err => f(err, initial, state => (this : Task[B]).onErrorRestartLoop(state)(f)))

  /** Creates a new task that will handle any matching throwable that
    * this task might emit.
    *
    * See [[onErrorRecover]] for the version that takes a partial function.
    */
  final def onErrorHandle[U >: A](f: Throwable => U): Task[U] =
    onErrorHandleWith(f.andThen(nowConstructor))

  /** Creates a new task that on error will try to map the error
    * to another value using the provided partial function.
    *
    * See [[onErrorHandle]] for the version that takes a total function.
    */
  final def onErrorRecover[U >: A](pf: PartialFunction[Throwable, U]): Task[U] =
    onErrorRecoverWith(pf.andThen(nowConstructor))

  /** Start execution of the source suspended in the `Task` context.
    *
    * This can be used for non-deterministic / concurrent execution.
    * The following code is more or less equivalent with
    * [[Task.parMap2]] (minus the behavior on error handling and
    * cancellation):
    *
    * {{{
    *   def par2[A, B](ta: Task[A], tb: Task[B]): Task[(A, B)] =
    *     for {
    *       fa <- ta.start
    *       fb <- tb.start
    *        a <- fa
    *        b <- fb
    *     } yield (a, b)
    * }}}
    *
    * Note in such a case usage of [[Task.parMap2 parMap2]]
    * (and [[Task.parMap3 parMap3]], etc.) is still recommended
    * because of behavior on error and cancellation — consider that
    * in the example above, if the first task finishes in error,
    * the second task doesn't get cancelled.
    *
    * IMPORTANT — this operation does start with an asynchronous boundary.
    * You can either use [[fork]] as an alternative, or use [[executeAsync]]
    * just before calling `register`, as in general this law holds:
    *
    * {{{
    *   fa.fork <-> fa.executeAsync.start
    * }}}
    *
    * See [[fork]] for the equivalent that does starts the task with
    * a forced async boundary.
    */
  final def start: Task[Fiber[A @uV]] =
    TaskStart.trampolined(this)

  /** Converts the source `Task` to any data type that implements
    * either `cats.effect.Concurrent` or `cats.effect.Async`.
    *
    * This operation discriminates between `Concurrent` and `Async`
    * data types by using their subtyping relationship
    * (`Concurrent <: Async`), therefore:
    *
    *  - in case the `F` data type implements `cats.effect.Concurrent`,
    *    then the resulting value is interruptible if the source task is
    *    (e.g. a conversion to `cats.effect.IO` will preserve Monix's `Task`
    *    cancelability)
    *  - otherwise in case the `F` data type implements just
    *    `cats.effect.Async`, then the conversion is still allowed,
    *    however the source's cancellation logic gets lost
    *
    * Example:
    *
    * {{{
    *   import cats.effect.IO
    *
    *   Task.eval(println("Hello!"))
    *     .delayExecution(5.seconds)
    *     .to[IO]
    * }}}
    *
    * Note a [[monix.execution.Scheduler Scheduler]] is required
    * because converting `Task` to something else means executing
    * it.
    *
    * @param F is the `cats.effect.Async` instance required in order
    *        to perform the conversions; and if this instance
    *        is actually a `cats.effect.Concurrent`, then the
    *        resulting value is also cancelable
    *
    * @param s $schedulerEvalDesc
    */
  final def to[F[_]](implicit F: Async[F], s: Scheduler): F[A @uV] =
    TaskConversions.to[F, A](this)(F, s)

  /** Converts the source to a `cats.effect.IO` value.
    *
    * {{{
    *   val task: Task[Unit] = Task
    *     .eval(println("Hello!"))
    *     .delayExecution(5.seconds)
    *
    *   // Conversion; note the resulting IO is also
    *   // cancelable if the source is
    *   val io: IO[Unit] = task.toIO
    * }}}
    *
    * This is an alias for [[to]], but specialized for `IO`.
    * You can use either with the same result.
    *
    * @param s $schedulerEvalDesc
    */
  final def toIO(implicit s: Scheduler): IO[A @uV] =
    to[IO]

  /** Converts a [[Task]] to an `org.reactivestreams.Publisher` that
    * emits a single item on success, or just the error on failure.
    *
    * See [[http://www.reactive-streams.org/ reactive-streams.org]] for the
    * Reactive Streams specification.
    */
  final def toReactivePublisher(implicit s: Scheduler): org.reactivestreams.Publisher[A @uV] =
    TaskToReactivePublisher[A](this)(s)

  /** Returns a Task that mirrors the source Task but that triggers a
    * `TimeoutException` in case the given duration passes without the
    * task emitting any item.
    */
  final def timeout(after: FiniteDuration): Task[A] =
    timeoutTo(after, raiseError(new TimeoutException(s"Task timed-out after $after of inactivity")))

  /** Returns a Task that mirrors the source Task but switches to the
    * given backup Task in case the given duration passes without the
    * source emitting any item.
    */
  final def timeoutTo[B >: A](after: FiniteDuration, backup: Task[B]): Task[B] =
    Task.race(this, Task.unit.delayExecution(after)).flatMap {
      case Left(a) =>
        Task.now(a)
      case Right(_) =>
        backup
    }

  /** Returns a string representation of this task meant for
    * debugging purposes only.
    */
  override def toString: String = this match {
    case Now(a) => s"Task.Now($a)"
    case Error(e) => s"Task.Error($e)"
    case _ =>
      val n = this.getClass.getName.replaceFirst("^monix\\.eval\\.Task[$.]", "")
      s"Task.$n$$${System.identityHashCode(this)}"
  }

  /** Returns a new value that transforms the result of the source,
    * given the `recover` or `map` functions, which get executed depending
    * on whether the result is successful or if it ends in error.
    *
    * This is an optimization on usage of [[attempt]] and [[map]],
    * this equivalence being true:
    *
    * {{{
    *   task.redeem(recover, map) <-> task.attempt.map(_.fold(recover, map))
    * }}}
    *
    * Usage of `redeem` subsumes `onErrorHandle` because:
    *
    * {{{
    *   task.redeem(fe, id) <-> task.onErrorHandle(fe)
    * }}}
    *
    * @param recover is a function used for error recover in case the
    *        source ends in error
    * @param map is a function used for mapping the result of the source
    *        in case it ends in success
    */
  def redeem[B](recover: Throwable => B, map: A => B): Task[B] =
    Task.FlatMap(this, new Task.Redeem(recover, map))

  /** Returns a new value that transforms the result of the source,
    * given the `recover` or `bind` functions, which get executed depending
    * on whether the result is successful or if it ends in error.
    *
    * This is an optimization on usage of [[attempt]] and [[flatMap]],
    * this equivalence being available:
    *
    * {{{
    *   task.redeemWith(recover, bind) <-> task.attempt.flatMap(_.fold(recover, bind))
    * }}}
    *
    * Usage of `redeemWith` subsumes `onErrorHandleWith` because:
    *
    * {{{
    *   task.redeemWith(fe, F.pure) <-> task.onErrorHandleWith(fe)
    * }}}
    *
    * Usage of `redeemWith` also subsumes [[flatMap]] because:
    *
    * {{{
    *   task.redeemWith(Task.raiseError, fs) <-> task.flatMap(fs)
    * }}}
    *
    * @param recover is the function that gets called to recover the source
    *        in case of error
    * @param bind is the function that gets to transform the source
    *        in case of success
    */
  def redeemWith[B](recover: Throwable => Task[B], bind: A => Task[B]): Task[B] =
    Task.FlatMap(this, new StackFrame.RedeemWith(recover, bind))

  /** Makes the source `Task` uninterruptible such that a `cancel` signal
    * (e.g. [[Fiber.cancel]]) has no effect.
    *
    * {{{
    *   val uncancelable = Task
    *     .eval(println("Hello!"))
    *     .delayExecution(10.seconds)
    *     .uncancelable
    *     .runAsync
    *
    *   // No longer works
    *   uncancelable.cancel()
    *
    *   // After 10 seconds
    *   //=> Hello!
    * }}}
    */
  final def uncancelable: Task[A] =
    TaskCancellation.uncancelable(this)
}

/** Builders for [[Task]].
  *
  * @define registerParamDesc is a function that will be called when
  *         this `Task` is executed, receiving a callback as a
  *         parameter, a callback that the user is supposed to call in
  *         order to signal the desired outcome of this `Task`. This
  *         function also receives a [[monix.execution.Scheduler Scheduler]]
  *         that can be used for managing asynchronous boundaries, a
  *         scheduler being nothing more than an evolved `ExecutionContext`.
  *
  * @define shiftDesc For example we can introduce an
  *         asynchronous boundary in the `flatMap` chain before a
  *         certain task, this being literally the implementation of
  *         [[Task.executeAsync executeAsync]]:
  *
  *         {{{
  *           Task.shift.flatMap(_ => task)
  *         }}}
  *
  *         And this can also be described with `followedBy` from Cats:
  *
  *         {{{
  *           import cats.syntax.all._
  *
  *           Task.shift.followedBy(task)
  *         }}}
  *
  *         Or we can specify an asynchronous boundary ''after''
  *         the evaluation of a certain task, this being literally
  *         the implementation of
  *         [[Task!.asyncBoundary:monix\.eval\.Task[A]* .asyncBoundary]]:
  *
  *         {{{
  *           task.flatMap(a => Task.shift.map(_ => a))
  *         }}}
  *
  *         And again we can also describe this with `forEffect`
  *         from Cats:
  *
  *         {{{
  *           task.forEffect(Task.shift)
  *         }}}
  *
  * @define parallelismNote NOTE: the tasks get forked automatically so there's
  *         no need to force asynchronous execution for immediate tasks,
  *         parallelism being guaranteed when multi-threading is available!
  *
  *         All specified tasks get evaluated in parallel, regardless of their
  *         execution model ([[Task.eval]] vs [[Task.evalAsync]] doesn't matter).
  *         Also the implementation tries to be smart about detecting forked
  *         tasks so it can eliminate extraneous forks for the very obvious
  *         cases.
  *
  * @define parallelismAdvice ADVICE: In a real life scenario the tasks should
  *         be expensive in order to warrant parallel execution. Parallelism
  *         doesn't magically speed up the code - it's usually fine for I/O-bound
  *         tasks, however for CPU-bound tasks it can make things worse.
  *         Performance improvements need to be verified.
  */
object Task extends TaskInstancesLevel1 {
  /** Lifts the given thunk in the `Task` context, processing it synchronously
    * when the task gets evaluated.
    *
    * This is a alias for:
    *
    * {{{
    *   Task.eval(thunk)
    * }}}
    *
    * WARN: behavior of `Task.apply` has changed since 3.0.0-RC2.
    * Before the change (during Monix 2.x series), this operation was forcing
    * a fork, being equivalent to the new [[Task.evalAsync]].
    *
    * Switch to [[Task.evalAsync]] if you wish the old behavior, or combine
    * [[Task.eval]] with [[Task.executeAsync]].
    */
  def apply[A](@deprecatedName('f) a: => A): Task[A] =
    eval(a)

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
    val coeval = Coeval.evalOnce(a)
    Eval(coeval)
  }

  /** Promote a non-strict value, a thunk, to a `Task`, catching exceptions
    * in the process.
    *
    * Note that since `Task` is not memoized or strict, this will recompute the
    * value each time the `Task` is executed, behaving like a function.
    *
    * @param a is the thunk to process on evaluation
    */
  def eval[A](a: => A): Task[A] =
    Eval(a _)

  /** Lifts a non-strict value, a thunk, to a `Task` that will trigger a logical
    * fork before evaluation.
    *
    * Like [[eval]], but the provided `thunk` will not be evaluated immediately.
    * Equivalence:
    *
    * {{{
    *   Task.evalAsync(a) <-> Task.eval(a).executeAsync
    * }}}
    *
    * @param a is the thunk to process on evaluation
    */
  def evalAsync[A](a: => A): Task[A] =
    TaskEvalAsync(a _)

  /** Alias for [[eval]]. */
  def delay[A](a: => A): Task[A] = eval(a)

  /** A [[Task]] instance that upon evaluation will never complete. */
  def never[A]: Task[A] = neverRef

  /** Converts `IO[A]` values into `Task[A]`.
    *
    * Preserves cancelability, if the source `IO` value is cancelable.
    *
    * {{{
    *   import cats.effect._
    *   import cats.syntax.all._
    *   import scala.concurrent.duration._
    *
    *   val io: IO[Unit] =
    *     IO.sleep(5.seconds) *> IO(println("Hello!"))
    *
    *   // Conversion; note the resulting task is also
    *   // cancelable if the source is
    *   val task: Task[Unit] = Task.fromIO(ioa)
    * }}}
    *
    * Also see [[fromEffect]], the more generic conversion utility.
    */
  def fromIO[A](ioa: IO[A]): Task[A] =
    Concurrent.liftIO(ioa)

  /** Builds a [[Task]] instance out of any data type that implements
    * either `cats.effect.ConcurrentEffect` or `cats.effect.Effect`.
    *
    * This method discriminates between `Effect` and `ConcurrentEffect`
    * using their subtype encoding (`ConcurrentEffect <: Effect`),
    * such that:
    *
    *  - if the indicated type has a `ConcurrentEffect` implementation
    *    and if the indicated value is cancelable, then the resulting
    *    task is also cancelable
    *  - otherwise, if the indicated type only implements `Effect`,
    *    then the conversion is still possible, but the resulting task
    *    isn't cancelable
    *
    * Example:
    *
    * {{{
    *   import cats.effect._
    *   import cats.syntax.all._
    *
    *   val io = Timer[IO].sleep(5.seconds) *> IO(println("Hello!"))
    *
    *   // Resulting task is cancelable
    *   val task: Task[Unit] = Task.fromEffect(io)
    * }}}
    *
    * @param F is the `cats.effect.Effect` type class instance necessary
    *        for converting to `Task`; this instance can also be a
    *        `cats.effect.Concurrent`, in which case the resulting
    *        `Task` value is cancelable if the source is
    */
  def fromEffect[F[_], A](fa: F[A])(implicit F: Effect[F]): Task[A] =
    TaskConversions.from(fa)

  /** Builds a [[Task]] instance out of a `cats.Eval`. */
  def fromEval[A](a: cats.Eval[A]): Task[A] =
    Coeval.fromEval(a).task

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
  val unit: Task[Unit] = Now(())

  /** Transforms a [[Coeval]] into a [[Task]]. */
  def coeval[A](a: Coeval[A]): Task[A] =
    a match {
      case Coeval.Now(value) => Task.Now(value)
      case Coeval.Error(e) => Task.Error(e)
      case Coeval.Always(f) => Task.Eval(f)
      case _ => Task.Eval(a)
    }

  /** Create a non-cancelable `Task` from an asynchronous computation,
    * which takes the form of a function with which we can register a
    * callback to execute upon completion.
    *
    * This operation is the implementation for `cats.effect.Async` and
    * is thus yielding non-cancelable tasks, being the simplified
    * version of [[Task.cancelable[A](register* Task.cancelable]].
    * This can be used to translate from a callback-based API to pure
    * `Task` values that cannot be canceled.
    *
    * See the the documentation for
    * [[https://typelevel.org/cats-effect/typeclasses/async.html cats.effect.Async]].
    *
    * For example, in case we wouldn't have [[Task.deferFuture]]
    * already defined, we could do this:
    *
    * {{{
    *   import scala.concurrent.{Future, ExecutionContext}
    *
    *   def deferFuture[A](f: => Future[A])(implicit ec: ExecutionContext): Task[A] =
    *     Task.async { cb =>
    *       // N.B. we could do `f.onComplete(cb)` directly ;-)
    *       f.onComplete {
    *         case Success(a) => cb.onSuccess(a)
    *         case Failure(e) => cb.onError(e)
    *       }
    *     }
    * }}}
    *
    * Note that this function needs an explicit `ExecutionContext` in order
    * to trigger `Future#complete`, however Monix's `Task` can inject
    * a [[monix.execution.Scheduler Scheduler]] for you, thus allowing you
    * to get rid of these pesky execution contexts being passed around explicitly.
    * See [[Task.asyncS]].
    *
    * CONTRACT for `register`:
    *
    *  - the provided function is executed when the `Task` will be evaluated
    *    (via `runAsync` or when its turn comes in the `flatMap` chain, not before)
    *  - the injected [[Callback]] can be called at most once, either with a
    *    successful result, or with an error; calling it more than once is
    *    a contract violation
    *  - it can be assumed that the callback provides no protection when called
    *    multiple times, the behavior being undefined
    *
    * @see [[Task.asyncS]] for a variant that also injects a
    *      [[monix.execution.Scheduler Scheduler]] into the provided callback,
    *      useful for forking, or delaying tasks or managing async boundaries
    *
    * @see [[Task.cancelable[A](register* Task.cancelable]] and [[Task.cancelableS]]
    *      for creating cancelable tasks
    *
    * @see [[Task.create]] for the builder that does it all
    */
  def async[A](register: Callback[A] => Unit): Task[A] =
    TaskCreate.async(register)

  /** Create a non-cancelable `Task` from an asynchronous computation,
    * which takes the form of a function with which we can register a
    * callback to execute upon completion, a function that also injects a
    * [[monix.execution.Scheduler Scheduler]] for managing async boundaries.
    *
    * This operation is the implementation for `cats.effect.Async` and
    * is thus yielding non-cancelable tasks, being the simplified
    * version of [[Task.cancelableS]]. It can be used to translate from a
    * callback-based API to pure `Task` values that cannot be canceled.
    *
    * See the the documentation for
    * [[https://typelevel.org/cats-effect/typeclasses/async.html cats.effect.Async]].
    *
    * For example, in case we wouldn't have [[Task.deferFuture]]
    * already defined, we could do this:
    *
    * {{{
    *   import scala.concurrent.Future
    *
    *   def deferFuture[A](f: => Future[A]): Task[A] =
    *     Task.asyncS { (scheduler, cb) =>
    *       // We are being given an ExecutionContext ;-)
    *       implicit val ec = scheduler
    *
    *       // N.B. we could do `f.onComplete(cb)` directly ;-)
    *       f.onComplete {
    *         case Success(a) => cb.onSuccess(a)
    *         case Failure(e) => cb.onError(e)
    *       }
    *     }
    * }}}
    *
    * Note that this function doesn't need an implicit `ExecutionContext`.
    * Compared with usage of [[Task.async[A](register* Task.async]], this
    * function injects a [[monix.execution.Scheduler Scheduler]] for us to
    * use for managing async boundaries.
    *
    * CONTRACT for `register`:
    *
    *  - the provided function is executed when the `Task` will be evaluated
    *    (via `runAsync` or when its turn comes in the `flatMap` chain, not before)
    *  - the injected [[Callback]] can be called at most once, either with a
    *    successful result, or with an error; calling it more than once is
    *    a contract violation
    *  - it can be assumed that the callback provides no protection when called
    *    multiple times, the behavior being undefined
    *
    * NOTES on the naming:
    *
    *  - `async` comes from `cats.effect.Async#async`
    *  - the `S` suffix comes from [[monix.execution.Scheduler Scheduler]]
    *
    * @see [[Task.async]] for a simpler variant that doesn't inject a
    *      `Scheduler`, in case you don't need one
    *
    * @see [[Task.cancelable[A](register* Task.cancelable]] and [[Task.cancelableS]]
    *      for creating cancelable tasks
    *
    * @see [[Task.create]] for the builder that does it all
    */
  def asyncS[A](register: (Scheduler, Callback[A]) => Unit): Task[A] =
    TaskCreate.asyncS(register)

  /** Create a cancelable `Task` from an asynchronous computation that
    * can be canceled, taking the form of a function with which we can
    * register a callback to execute upon completion.
    *
    * This operation is the implementation for
    * `cats.effect.Concurrent#cancelable` and is thus yielding
    * cancelable tasks. It can be used to translate from a callback-based
    * API to pure `Task` values that can be canceled.
    *
    * See the the documentation for
    * [[https://typelevel.org/cats-effect/typeclasses/concurrent.html cats.effect.Concurrent]].
    *
    * For example, in case we wouldn't have [[Task.delayExecution]]
    * already defined and we wanted to delay evaluation using a Java
    * [[https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ScheduledExecutorService.html ScheduledExecutorService]]
    * (no need for that because we've got [[monix.execution.Scheduler Scheduler]],
    * but lets say for didactic purposes):
    *
    * {{{
    *   import monix.execution.Cancelable
    *   import scala.concurrent.ExecutionContext
    *   import scala.concurrent.duration._
    *   import java.util.concurrent.ScheduledExecutorService
    *
    *   def delayed[A](sc: ScheduledExecutorService, timespan: FiniteDuration)
    *     (thunk: => A)
    *     (implicit ec: ExecutionContext): Task[A] = {
    *
    *     Task.cancelable { (scheduler, cb) =>
    *       val future = sc.schedule(
    *         () => ec.execute(() => {
    *           try
    *             cb.onSuccess(thunk)
    *           catch { case NonFatal(e) =>
    *               cb.onError(e)
    *           }
    *         }),
    *         timespan.length,
    *         timespan.unit)
    *
    *       // Returning the cancelation token that is able to cancel the
    *       // scheduling in case the active computation hasn't finished yet
    *       Cancelable(() => future.cancel(false))
    *     }
    *   }
    * }}}
    *
    * Note in this sample we are passing an implicit `ExecutionContext`
    * in order to do the actual processing, the `ScheduledExecutorService`
    * being in charge just of scheduling. We don't need to do that, as `Task`
    * affords to have a [[monix.execution.Scheduler Scheduler]] injected
    * instead via [[Task.cancelableS]].
    *
    * CONTRACT for `register`:
    *
    *  - the provided function is executed when the `Task` will be evaluated
    *    (via `runAsync` or when its turn comes in the `flatMap` chain, not before)
    *  - the injected [[Callback]] can be called at most once, either with a
    *    successful result, or with an error; calling it more than once is
    *    a contract violation
    *  - it can be assumed that the callback provides no protection when called
    *    multiple times, the behavior being undefined
    *
    * @see [[Task.cancelableS]] for the version that also injects a
    *      [[monix.execution.Scheduler Scheduler]] in that callback
    *
    * @see [[Task.asyncS]] and [[Task.async[A](register* Task.async]] for the
    *      simpler versions of this builder that create non-cancelable tasks
    *      from callback-based APIs
    *
    * @see [[Task.create]] for the builder that does it all
    *
    * @param register $registerParamDesc
    */
  def cancelable[A](register: Callback[A] => Cancelable): Task[A] =
    cancelableS((_, cb) => register(cb))

  /** Create a cancelable `Task` from an asynchronous computation,
    * which takes the form of a function with which we can register a
    * callback to execute upon completion, a function that also injects a
    * [[monix.execution.Scheduler Scheduler]] for managing async boundaries.
    *
    * This operation is the implementation for
    * `cats.effect.Concurrent#cancelable` and is thus yielding
    * cancelable tasks. It can be used to translate from a callback-based API
    * to pure `Task` values that can be canceled.
    *
    * See the the documentation for
    * [[https://typelevel.org/cats-effect/typeclasses/concurrent.html cats.effect.Concurrent]].
    *
    * For example, in case we wouldn't have [[Task.delayExecution]]
    * already defined and we wanted to delay evaluation using a Java
    * [[https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ScheduledExecutorService.html ScheduledExecutorService]]
    * (no need for that because we've got [[monix.execution.Scheduler Scheduler]],
    * but lets say for didactic purposes):
    *
    * {{{
    *   import monix.execution.Cancelable
    *   import scala.concurrent.duration._
    *   import java.util.concurrent.ScheduledExecutorService
    *
    *   def delayed[A](sc: ScheduledExecutorService, timespan: FiniteDuration)
    *     (thunk: => A): Task[A] = {
    *
    *     Task.cancelableS { (scheduler, cb) =>
    *       val future = sc.schedule(
    *         () => scheduler.execute(() => {
    *           try
    *             cb.onSuccess(thunk)
    *           catch { case NonFatal(e) =>
    *               cb.onError(e)
    *           }
    *         }),
    *         timespan.length,
    *         timespan.unit)
    *
    *       // Returning the cancelation token that is able to cancel the
    *       // scheduling in case the active computation hasn't finished yet
    *       Cancelable(() => future.cancel(false))
    *     }
    *   }
    * }}}
    *
    * As can be seen, the passed function needs to pass a
    * [[monix.execution.Cancelable Cancelable]] in order to specify cancelation
    * logic.
    *
    * This is a sample given for didactic purposes. Our `cancelableS` is
    * being injected a [[monix.execution.Scheduler Scheduler]] and it is
    * perfectly capable of doing such delayed execution without help from
    * Java's standard library:
    *
    * {{{
    *   def delayed[A](timespan: FiniteDuration)(thunk: => A): Task[A] =
    *     Task.cancelableS { (scheduler, cb) =>
    *       // N.B. this already returns the Cancelable that we need!
    *       scheduler.scheduleOnce(timespan) {
    *         try cb.onSuccess(thunk)
    *         catch { case NonFatal(e) => cb.onError(e) }
    *       }
    *     }
    * }}}
    *
    * CONTRACT for `register`:
    *
    *  - the provided function is executed when the `Task` will be evaluated
    *    (via `runAsync` or when its turn comes in the `flatMap` chain, not before)
    *  - the injected [[Callback]] can be called at most once, either with a
    *    successful result, or with an error; calling it more than once is
    *    a contract violation
    *  - it can be assumed that the callback provides no protection when called
    *    multiple times, the behavior being undefined
    *
    * NOTES on the naming:
    *
    *  - `cancelable` comes from `cats.effect.Concurrent#cancelable`
    *  - the `S` suffix comes from [[monix.execution.Scheduler Scheduler]]
    *
    * @see [[Task.cancelable[A](register* Task.cancelable]] for the simpler
    *      variant that doesn't inject the `Scheduler` in that callback
    *
    * @see [[Task.asyncS]] and [[Task.async[A](register* Task.async]] for the
    *      simpler versions of this builder that create non-cancelable tasks
    *      from callback-based APIs
    *
    * @see [[Task.create]] for the builder that does it all
    *
    * @param register $registerParamDesc
    */
  def cancelableS[A](register: (Scheduler, Callback[A]) => Cancelable): Task[A] =
    TaskCreate.cancelableS(register)

  /** Polymorphic `Task` builder that is able to describe asynchronous
    * tasks depending on the type of the given callback.
    *
    * Note that this function uses the
    * [[https://typelevel.org/cats/guidelines.html#partially-applied-type-params Partially-Applied Type technique]].
    *
    * Calling `create` with a callback that returns `Unit` is
    * equivalent with [[Task.asyncS]]:
    *
    * {{{
    *   Task.simple(f) <-> Task.create(f)
    * }}}
    *
    * Example:
    *
    * {{{
    *   import scala.concurrent.Future
    *
    *   def deferFuture[A](f: => Future[A]): Task[A] =
    *     Task.create { (scheduler, cb) =>
    *       f.onComplete(cb)(scheduler)
    *     }
    * }}}
    *
    * We could return a [[monix.execution.Cancelable Cancelable]]
    * reference and thus make a cancelable task, thus for an `f` that
    * can be passed to [[Task.cancelableS]] this equivalence holds:
    *
    * {{{
    *   Task.cancelable(f) <-> Task.create(f)
    * }}}
    *
    * Example:
    *
    * {{{
    *   import monix.execution.Cancelable
    *
    *   def delayResult[A](timespan: FiniteDuration)(thunk: => A): Task[A] =
    *     Task.create { (scheduler, cb) =>
    *       val c = scheduler.scheduleOnce(timespan)(cb(Try(thunk)))
    *       // We can simply return `c`, but doing this for didactic purposes!
    *       Cancelable(() => c.cancel())
    *     }
    * }}}
    *
    * Passed function can also return `IO[Unit]` as a task that
    * describes a cancelation action:
    *
    * {{{
    *   import cats.effect.IO
    *
    *   def delayResult[A](timespan: FiniteDuration)(thunk: => A): Task[A] =
    *     Task.create { (scheduler, cb) =>
    *       val c = scheduler.scheduleOnce(timespan)(cb(Try(thunk)))
    *       // We can simply return `c`, but doing this for didactic purposes!
    *       IO(c.cancel())
    *     }
    * }}}
    *
    * Passed function can also return `Task[Unit]` as a task that
    * describes a cancelation action:
    *
    * {{{
    *   def delayResult[A](timespan: FiniteDuration)(thunk: => A): Task[A] =
    *     Task.create { (scheduler, cb) =>
    *       val c = scheduler.scheduleOnce(timespan)(cb(Try(thunk)))
    *       // We can simply return `c`, but doing this for didactic purposes!
    *       Task.evalAsync(c.cancel())
    *     }
    * }}}
    *
    * Passed function can also return `Coeval[Unit]` as a task that
    * describes a cancelation action:
    *
    * {{{
    *   def delayResult[A](timespan: FiniteDuration)(thunk: => A): Task[A] =
    *     Task.create { (scheduler, cb) =>
    *       val c = scheduler.scheduleOnce(timespan)(cb(Try(thunk)))
    *       // We can simply return `c`, but doing this for didactic purposes!
    *       Coeval(c.cancel())
    *     }
    * }}}
    *
    * The supported types for the cancelation tokens are:
    *
    *  - `Unit`, yielding non-cancelable tasks
    *  - [[monix.execution.Cancelable Cancelable]], the Monix standard
    *  - [[monix.eval.Task Task[Unit]]]
    *  - [[monix.eval.Coeval Coeval[Unit]]]
    *  - `cats.effect.IO[Unit]`, see
    *    [[https://typelevel.org/cats-effect/datatypes/io.html IO docs]]
    *
    * Support for more might be added in the future.
    */
  def create[A]: AsyncBuilder.CreatePartiallyApplied[A] = new AsyncBuilder.CreatePartiallyApplied[A]

  /** Converts the given Scala `Future` into a `Task`.
    *
    * NOTE: if you want to defer the creation of the future, use
    * in combination with [[defer]].
    */
  def fromFuture[A](f: Future[A]): Task[A] =
    TaskFromFuture.strict(f)

  /** Run two `Task` actions concurrently, and return the first to
    * finish, either in success or error. The loser of the race is
    * cancelled.
    *
    * The two tasks are executed in parallel, the winner being the
    * first that signals a result.
    *
    * As an example, this would be equivalent with [[Task.timeout]]:
    * {{{
    *   import scala.concurrent.duration._
    *
    *   val timeoutError = Task
    *     .raiseError(new TimeoutException)
    *     .delayExecution(5.seconds)
    *
    *   Task.race(myTask, timeoutError)
    * }}}
    *
    * Similarly [[Task.timeoutTo]] is expressed in terms of `race`.
    *
    * $parallelismNote
    *
    * @see [[racePair]] for a version that does not cancel
    *     the loser automatically on successful results and [[raceMany]]
    *     for a version that races a whole list of tasks.
    */
  def race[A, B](fa: Task[A], fb: Task[B]): Task[Either[A, B]] =
    TaskRace(fa, fb)

  /** Runs multiple `Task` actions concurrently, returning the
    * first to finish, either in success or error. All losers of the
    * race get cancelled.
    *
    * The tasks get executed in parallel, the winner being the first
    * that signals a result.
    *
    * {{{
    *   val list: List[Task[Int]] = List(t1, t2, t3, ???)
    *
    *   val winner: Task[Int] = Task.raceMany(list)
    * }}}
    *
    * $parallelismNote
    *
    * @see [[race]] or [[racePair]] for racing two tasks, for more
    *      control.
    */
  def raceMany[A](tasks: TraversableOnce[Task[A]]): Task[A] =
    TaskRaceList(tasks)

  /** Run two `Task` actions concurrently, and returns a pair
    * containing both the winner's successful value and the loser
    * represented as a still-unfinished task.
    *
    * If the first task completes in error, then the result will
    * complete in error, the other task being cancelled.
    *
    * On usage the user has the option of cancelling the losing task,
    * this being equivalent with plain [[race]]:
    *
    * {{{
    *   val ta: Task[A] = ???
    *   val tb: Task[B] = ???
    *
    *   Task.racePair(ta, tb).flatMap {
    *     case Left((a, taskB)) =>
    *       taskB.cancel.map(_ => a)
    *     case Right((taskA, b)) =>
    *       taskA.cancel.map(_ => b)
    *   }
    * }}}
    *
    * $parallelismNote
    *
    * @see [[race]] for a simpler version that cancels the loser
    *      immediately or [[raceMany]] that races collections of tasks.
    */
  def racePair[A,B](fa: Task[A], fb: Task[B]): Task[Either[(A, Fiber[B]), (Fiber[A], B)]] =
    TaskRacePair(fa, fb)

  /** Asynchronous boundary described as an effectful `Task` that
    * can be used in `flatMap` chains to "shift" the continuation
    * of the run-loop to another thread or call stack, managed by
    * the default [[monix.execution.Scheduler Scheduler]].
    *
    * This is the equivalent of `IO.shift`, except that Monix's `Task`
    * gets executed with an injected `Scheduler` in
    * [[Task.runAsync(implicit* .runAsync]] and that's going to be
    * the `Scheduler` responsible for the "shift".
    *
    * $shiftDesc
    */
  val shift: Task[Unit] =
    shift(null)

  /** Asynchronous boundary described as an effectful `Task` that
    * can be used in `flatMap` chains to "shift" the continuation
    * of the run-loop to another call stack or thread, managed by
    * the given execution context.
    *
    * This is the equivalent of `IO.shift`.
    *
    * $shiftDesc
    */
  def shift(ec: ExecutionContext): Task[Unit] =
    TaskShift(ec)

  /** Creates a new `Task` that will sleep for the given duration,
    * emitting a tick when that time span is over.
    *
    * As an example on evaluation this will print "Hello!" after
    * 3 seconds:
    *
    * {{{
    *   import scala.concurrent.duration._
    *
    *   Task.sleep(3.seconds).flatMap { _ =>
    *     Task.eval(println("Hello!"))
    *   }
    * }}}
    *
    * See [[Task.delayExecution]] for this operation described as
    * a method on `Task` references or [[Task.delayResult]] for the
    * helper that triggers the evaluation of the source on time, but
    * then delays the result.
    */
  def sleep(timespan: FiniteDuration): Task[Unit] =
    TaskSleep.apply(timespan)

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
    * apply the function to each element of the collection and gather their
    * results in the same collection.
    *
    *  It's a generalized version of [[sequence]].
    */
  def traverse[A, B, M[X] <: TraversableOnce[X]](in: M[A])(f: A => Task[B])
    (implicit cbf: CanBuildFrom[M[A], B, M[B]]): Task[M[B]] =
    TaskSequence.traverse(in, f)(cbf)

  /** Executes the given sequence of tasks in parallel, non-deterministically
    * gathering their results, returning a task that will signal the sequence
    * of results once all tasks are finished.
    *
    * This function is the nondeterministic analogue of `sequence` and should
    * behave identically to `sequence` so long as there is no interaction between
    * the effects being gathered. However, unlike `sequence`, which decides on
    * a total order of effects, the effects in a `gather` are unordered with
    * respect to each other, the tasks being execute in parallel, not in sequence.
    *
    * Although the effects are unordered, we ensure the order of results
    * matches the order of the input sequence. Also see [[gatherUnordered]]
    * for the more efficient alternative.
    *
    * Example:
    * {{{
    *   val tasks = List(Task(1 + 1), Task(2 + 2), Task(3 + 3))
    *
    *   // Yields 2, 4, 6
    *   Task.gather(tasks)
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    */
  def gather[A, M[X] <: TraversableOnce[X]](in: M[Task[A]])
    (implicit cbf: CanBuildFrom[M[Task[A]], A, M[A]]): Task[M[A]] =
    TaskGather[A, M](in, () => cbf(in))

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
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    */
  def wander[A, B, M[X] <: TraversableOnce[X]](in: M[A])(f: A => Task[B])
    (implicit cbf: CanBuildFrom[M[A], B, M[B]]): Task[M[B]] =
    Task.eval(in.map(f)).flatMap(col => TaskGather[B, M](col, () => cbf(in)))

  /** Processes the given collection of tasks in parallel and
    * nondeterministically gather the results without keeping the original
    * ordering of the given tasks.
    *
    * This function is similar to [[gather]], but neither the effects nor the
    * results will be ordered. Useful when you don't need ordering because:
    *
    *  - it has non-blocking behavior (but not wait-free)
    *  - it can be more efficient (compared with [[gather]]), but not
    *    necessarily (if you care about performance, then test)
    *
    * Example:
    * {{{
    *   val tasks = List(Task(1 + 1), Task(2 + 2), Task(3 + 3))
    *
    *   // Yields 2, 4, 6 (but order is NOT guaranteed)
    *   Task.gatherUnordered(tasks)
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
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
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    */
  def wanderUnordered[A, B, M[X] <: TraversableOnce[X]](in: M[A])(f: A => Task[B]): Task[List[B]] =
    Task.eval(in.map(f)).flatMap(gatherUnordered)

  /** Yields a task that on evaluation will process the given tasks
    * in parallel, then apply the given mapping function on their results.
    *
    * Example:
    * {{{
    *   val task1 = Task(1 + 1)
    *   val task2 = Task(2 + 2)
    *
    *   // Yields 6
    *   Task.mapBoth(task1, task2)((a, b) => a + b)
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    */
  def mapBoth[A1,A2,R](fa1: Task[A1], fa2: Task[A2])(f: (A1,A2) => R): Task[R] =
    TaskMapBoth(fa1, fa2)(f)

  /** Pairs 2 `Task` values, applying the given mapping function.
    *
    * Returns a new `Task` reference that completes with the result
    * of mapping that function to their successful results, or in
    * failure in case either of them fails.
    *
    * This is a specialized [[Task.sequence]] operation and as such
    * the tasks are evaluated in order, one after another, the
    * operation being described in terms of [[Task.flatMap .flatMap]].
    *
    * {{{
    *   val fa1 = Task(1)
    *   val fa2 = Task(2)
    *
    *   // Yields Success(3)
    *   Task.map2(fa1, fa2) { (a, b) =>
    *     a + b
    *   }
    *
    *   // Yields Failure(e), because the second arg is a failure
    *   Task.map2(fa1, Task.raiseError(e)) { (a, b) =>
    *     a + b
    *   }
    * }}}
    *
    * See [[Task.parMap2]] for parallel processing.
    */
  def map2[A1, A2, R](fa1: Task[A1], fa2: Task[A2])(f: (A1, A2) => R): Task[R] =
    for (a1 <- fa1; a2 <- fa2)
      yield f(a1, a2)

  /** Pairs 3 `Task` values, applying the given mapping function.
    *
    * Returns a new `Task` reference that completes with the result
    * of mapping that function to their successful results, or in
    * failure in case either of them fails.
    *
    * This is a specialized [[Task.sequence]] operation and as such
    * the tasks are evaluated in order, one after another, the
    * operation being described in terms of [[Task.flatMap .flatMap]].
    *
    * {{{
    *   val fa1 = Task(1)
    *   val fa2 = Task(2)
    *   val fa3 = Task(3)
    *
    *   // Yields Success(6)
    *   Task.map3(fa1, fa2, fa3) { (a, b, c) =>
    *     a + b + c
    *   }
    *
    *   // Yields Failure(e), because the second arg is a failure
    *   Task.map3(fa1, Task.raiseError(e), fa3) { (a, b, c) =>
    *     a + b + c
    *   }
    * }}}
    *
    * See [[Task.parMap3]] for parallel processing.
    */
  def map3[A1, A2, A3, R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3])
    (f: (A1, A2, A3) => R): Task[R] = {

    for (a1 <- fa1; a2 <- fa2; a3 <- fa3)
      yield f(a1, a2, a3)
  }

  /** Pairs 4 `Task` values, applying the given mapping function.
    *
    * Returns a new `Task` reference that completes with the result
    * of mapping that function to their successful results, or in
    * failure in case either of them fails.
    *
    * This is a specialized [[Task.sequence]] operation and as such
    * the tasks are evaluated in order, one after another, the
    * operation being described in terms of [[Task.flatMap .flatMap]].
    *
    * {{{
    *   val fa1 = Task(1)
    *   val fa2 = Task(2)
    *   val fa3 = Task(3)
    *   val fa4 = Task(4)
    *
    *   // Yields Success(10)
    *   Task.map4(fa1, fa2, fa3, fa4) { (a, b, c, d) =>
    *     a + b + c + d
    *   }
    *
    *   // Yields Failure(e), because the second arg is a failure
    *   Task.map4(fa1, Task.raiseError(e), fa3, fa4) {
    *     (a, b, c, d) => a + b + c + d
    *   }
    * }}}
    *
    * See [[Task.parMap4]] for parallel processing.
    */
  def map4[A1, A2, A3, A4, R]
    (fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4])
    (f: (A1, A2, A3, A4) => R): Task[R] = {

    for (a1 <- fa1; a2 <- fa2; a3 <- fa3; a4 <- fa4)
      yield f(a1, a2, a3, a4)
  }

  /** Pairs 5 `Task` values, applying the given mapping function.
    *
    * Returns a new `Task` reference that completes with the result
    * of mapping that function to their successful results, or in
    * failure in case either of them fails.
    *
    * This is a specialized [[Task.sequence]] operation and as such
    * the tasks are evaluated in order, one after another, the
    * operation being described in terms of [[Task.flatMap .flatMap]].
    *
    * {{{
    *   val fa1 = Task(1)
    *   val fa2 = Task(2)
    *   val fa3 = Task(3)
    *   val fa4 = Task(4)
    *   val fa5 = Task(5)
    *
    *   // Yields Success(15)
    *   Task.map5(fa1, fa2, fa3, fa4, fa5) { (a, b, c, d, e) =>
    *     a + b + c + d + e
    *   }
    *
    *   // Yields Failure(e), because the second arg is a failure
    *   Task.map5(fa1, Task.raiseError(e), fa3, fa4, fa5) {
    *     (a, b, c, d, e) => a + b + c + d + e
    *   }
    * }}}
    *
    * See [[Task.parMap5]] for parallel processing.
    */
  def map5[A1, A2, A3, A4, A5, R]
    (fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4], fa5: Task[A5])
    (f: (A1, A2, A3, A4, A5) => R): Task[R] = {

    for (a1 <- fa1; a2 <- fa2; a3 <- fa3; a4 <- fa4; a5 <- fa5)
      yield f(a1, a2, a3, a4, a5)
  }

  /** Pairs 6 `Task` values, applying the given mapping function.
    *
    * Returns a new `Task` reference that completes with the result
    * of mapping that function to their successful results, or in
    * failure in case either of them fails.
    *
    * This is a specialized [[Task.sequence]] operation and as such
    * the tasks are evaluated in order, one after another, the
    * operation being described in terms of [[Task.flatMap .flatMap]].
    *
    * {{{
    *   val fa1 = Task(1)
    *   val fa2 = Task(2)
    *   val fa3 = Task(3)
    *   val fa4 = Task(4)
    *   val fa5 = Task(5)
    *   val fa6 = Task(6)
    *
    *   // Yields Success(21)
    *   Task.map6(fa1, fa2, fa3, fa4, fa5, fa6) { (a, b, c, d, e, f) =>
    *     a + b + c + d + e + f
    *   }
    *
    *   // Yields Failure(e), because the second arg is a failure
    *   Task.map6(fa1, Task.raiseError(e), fa3, fa4, fa5, fa6) {
    *     (a, b, c, d, e, f) => a + b + c + d + e + f
    *   }
    * }}}
    *
    * See [[Task.parMap6]] for parallel processing.
    */
  def map6[A1, A2, A3, A4, A5, A6, R]
    (fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4], fa5: Task[A5], fa6: Task[A6])
    (f: (A1, A2, A3, A4, A5, A6) => R): Task[R] = {

    for (a1 <- fa1; a2 <- fa2; a3 <- fa3; a4 <- fa4; a5 <- fa5; a6 <- fa6)
      yield f(a1, a2, a3, a4, a5, a6)
  }

  /** Pairs 2 `Task` values, applying the given mapping function,
    * ordering the results, but not the side effects, the evaluation
    * being done in parallel.
    *
    * This is a specialized [[Task.gather]] operation and as such
    * the tasks are evaluated in parallel, ordering the results.
    * In case one of the tasks fails, then all other tasks get
    * cancelled and the final result will be a failure.
    *
    * {{{
    *   val fa1 = Task(1)
    *   val fa2 = Task(2)
    *
    *   // Yields Success(3)
    *   Task.parMap2(fa1, fa2) { (a, b) =>
    *     a + b
    *   }
    *
    *   // Yields Failure(e), because the second arg is a failure
    *   Task.parMap2(fa1, Task.raiseError(e)) { (a, b) =>
    *     a + b
    *   }
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    *
    * See [[Task.map2]] for sequential processing.
    */
  def parMap2[A1,A2,R](fa1: Task[A1], fa2: Task[A2])(f: (A1,A2) => R): Task[R] =
    Task.mapBoth(fa1, fa2)(f)

  /** Pairs 3 `Task` values, applying the given mapping function,
    * ordering the results, but not the side effects, the evaluation
    * being done in parallel.
    *
    * This is a specialized [[Task.gather]] operation and as such
    * the tasks are evaluated in parallel, ordering the results.
    * In case one of the tasks fails, then all other tasks get
    * cancelled and the final result will be a failure.
    *
    * {{{
    *   val fa1 = Task(1)
    *   val fa2 = Task(2)
    *   val fa3 = Task(3)
    *
    *   // Yields Success(6)
    *   Task.parMap3(fa1, fa2, fa3) { (a, b, c) =>
    *     a + b + c
    *   }
    *
    *   // Yields Failure(e), because the second arg is a failure
    *   Task.parMap3(fa1, Task.raiseError(e), fa3) { (a, b, c) =>
    *     a + b + c
    *   }
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    *
    * See [[Task.map3]] for sequential processing.
    */
  def parMap3[A1,A2,A3,R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3])(f: (A1,A2,A3) => R): Task[R] = {
    val fa12 = parZip2(fa1, fa2)
    parMap2(fa12, fa3) { case ((a1,a2), a3) => f(a1,a2,a3) }
  }

  /** Pairs 4 `Task` values, applying the given mapping function,
    * ordering the results, but not the side effects, the evaluation
    * being done in parallel if the tasks are async.
    *
    * This is a specialized [[Task.gather]] operation and as such
    * the tasks are evaluated in parallel, ordering the results.
    * In case one of the tasks fails, then all other tasks get
    * cancelled and the final result will be a failure.
    *
    * {{{
    *   val fa1 = Task(1)
    *   val fa2 = Task(2)
    *   val fa3 = Task(3)
    *   val fa4 = Task(4)
    *
    *   // Yields Success(10)
    *   Task.parMap4(fa1, fa2, fa3, fa4) { (a, b, c, d) =>
    *     a + b + c + d
    *   }
    *
    *   // Yields Failure(e), because the second arg is a failure
    *   Task.parMap4(fa1, Task.raiseError(e), fa3, fa4) {
    *     (a, b, c, d) => a + b + c + d
    *   }
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    *
    * See [[Task.map4]] for sequential processing.
    */
  def parMap4[A1,A2,A3,A4,R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4])(f: (A1,A2,A3,A4) => R): Task[R] = {
    val fa123 = parZip3(fa1, fa2, fa3)
    parMap2(fa123, fa4) { case ((a1,a2,a3), a4) => f(a1,a2,a3,a4) }
  }

  /** Pairs 5 `Task` values, applying the given mapping function,
    * ordering the results, but not the side effects, the evaluation
    * being done in parallel if the tasks are async.
    *
    * This is a specialized [[Task.gather]] operation and as such
    * the tasks are evaluated in parallel, ordering the results.
    * In case one of the tasks fails, then all other tasks get
    * cancelled and the final result will be a failure.
    *
    * {{{
    *   val fa1 = Task(1)
    *   val fa2 = Task(2)
    *   val fa3 = Task(3)
    *   val fa4 = Task(4)
    *   val fa5 = Task(5)
    *
    *   // Yields Success(15)
    *   Task.parMap5(fa1, fa2, fa3, fa4, fa5) { (a, b, c, d, e) =>
    *     a + b + c + d + e
    *   }
    *
    *   // Yields Failure(e), because the second arg is a failure
    *   Task.parMap5(fa1, Task.raiseError(e), fa3, fa4, fa5) {
    *     (a, b, c, d, e) => a + b + c + d + e
    *   }
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    *
    * See [[Task.map5]] for sequential processing.
    */
  def parMap5[A1,A2,A3,A4,A5,R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4], fa5: Task[A5])(f: (A1,A2,A3,A4,A5) => R): Task[R] = {
    val fa1234 = parZip4(fa1, fa2, fa3, fa4)
    parMap2(fa1234, fa5) { case ((a1,a2,a3,a4), a5) => f(a1,a2,a3,a4,a5) }
  }

  /** Pairs 6 `Task` values, applying the given mapping function,
    * ordering the results, but not the side effects, the evaluation
    * being done in parallel if the tasks are async.
    *
    * This is a specialized [[Task.gather]] operation and as such
    * the tasks are evaluated in parallel, ordering the results.
    * In case one of the tasks fails, then all other tasks get
    * cancelled and the final result will be a failure.
    *
    * {{{
    *   val fa1 = Task(1)
    *   val fa2 = Task(2)
    *   val fa3 = Task(3)
    *   val fa4 = Task(4)
    *   val fa5 = Task(5)
    *   val fa6 = Task(6)
    *
    *   // Yields Success(21)
    *   Task.parMap6(fa1, fa2, fa3, fa4, fa5, fa6) { (a, b, c, d, e, f) =>
    *     a + b + c + d + e + f
    *   }
    *
    *   // Yields Failure(e), because the second arg is a failure
    *   Task.parMap6(fa1, Task.raiseError(e), fa3, fa4, fa5, fa6) {
    *     (a, b, c, d, e, f) => a + b + c + d + e + f
    *   }
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    *
    * See [[Task.map6]] for sequential processing.
    */
  def parMap6[A1,A2,A3,A4,A5,A6,R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4], fa5: Task[A5], fa6: Task[A6])(f: (A1,A2,A3,A4,A5,A6) => R): Task[R] = {
    val fa12345 = parZip5(fa1, fa2, fa3, fa4, fa5)
    parMap2(fa12345, fa6) { case ((a1,a2,a3,a4,a5), a6) => f(a1,a2,a3,a4,a5,a6) }
  }

  /** Pairs two [[Task]] instances using [[parMap2]]. */
  def parZip2[A1,A2,R](fa1: Task[A1], fa2: Task[A2]): Task[(A1,A2)] =
    Task.mapBoth(fa1, fa2)((_,_))

  /** Pairs three [[Task]] instances using [[parMap3]]. */
  def parZip3[A1,A2,A3](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3]): Task[(A1,A2,A3)] =
    parMap3(fa1,fa2,fa3)((a1,a2,a3) => (a1,a2,a3))

  /** Pairs four [[Task]] instances using [[parMap4]]. */
  def parZip4[A1,A2,A3,A4](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4]): Task[(A1,A2,A3,A4)] =
    parMap4(fa1,fa2,fa3,fa4)((a1,a2,a3,a4) => (a1,a2,a3,a4))

  /** Pairs five [[Task]] instances using [[parMap5]]. */
  def parZip5[A1,A2,A3,A4,A5](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4], fa5: Task[A5]): Task[(A1,A2,A3,A4,A5)] =
    parMap5(fa1,fa2,fa3,fa4,fa5)((a1,a2,a3,a4,a5) => (a1,a2,a3,a4,a5))

  /** Pairs six [[Task]] instances using [[parMap6]]. */
  def parZip6[A1,A2,A3,A4,A5,A6](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4], fa5: Task[A5], fa6: Task[A6]): Task[(A1,A2,A3,A4,A5,A6)] =
    parMap6(fa1,fa2,fa3,fa4,fa5,fa6)((a1,a2,a3,a4,a5,a6) => (a1,a2,a3,a4,a5,a6))

  /** Returns the current [[Task.Options]] configuration, which determine the
    * task's run-loop behavior.
    *
    * @see [[Task.executeWithOptions]]
    */
  val readOptions: Task[Options] =
    Task.Async(
      (ctx, cb) => cb.onSuccess(ctx.options),
      trampolineBefore = false,
      trampolineAfter = true)

  /** Set of options for customizing the task's behavior.
    *
    * See [[Task.defaultOptions]] for the default `Options` instance
    * used by [[Task!.runAsync(implicit* .runAsync]].
    *
    * @param autoCancelableRunLoops should be set to `true` in
    *        case you want `flatMap` driven loops to be
    *        auto-cancelable. Defaults to `false`.
    *
    * @param localContextPropagation should be set to `true` in
    *        case you want the [[monix.execution.misc.Local Local]]
    *        variables to be propagated on async boundaries.
    *        Defaults to `false`.
    */
  final case class Options(
    autoCancelableRunLoops: Boolean,
    localContextPropagation: Boolean) {

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

    /** Creates a new set of options from the source, but with
      * the [[localContextPropagation]] value set to `true`.
      */
    def enableLocalContextPropagation: Options =
      copy(localContextPropagation = true)

    /** Creates a new set of options from the source, but with
      * the [[localContextPropagation]] value set to `false`.
      */
    def disableLocalContextPropagation: Options =
      copy(localContextPropagation = false)
  }

  /** Default [[Options]] to use for [[Task]] evaluation,
    * thus:
    *
    *  - `autoCancelableRunLoops` is `false` by default
    *  - `localContextPropagation` is `false` by default
    *
    * On top of the JVM the default can be overridden by
    * setting the following system properties:
    *
    *  - `monix.environment.autoCancelableRunLoops`
    *    (`true`, `yes` or `1` for enabling)
    *
    *  - `monix.environment.localContextPropagation`
    *    (`true`, `yes` or `1` for enabling)
    *
    * @see [[Task.Options]]
    */
  val defaultOptions: Options =
    Options(
      autoCancelableRunLoops = Platform.autoCancelableRunLoops,
      localContextPropagation = Platform.localContextPropagation
    )

  /** The `AsyncBuilder` is a type used by the [[Task.create]] builder,
    * in order to change its behavior based on the type of the
    * cancelation token.
    *
    * In combination with the
    * [[https://typelevel.org/cats/guidelines.html#partially-applied-type-params Partially-Applied Type technique]],
    * this ends up providing a polymorphic [[Task.create]] that can
    * support multiple cancelation tokens optimally, i.e. without
    * implicit conversions and that can be optimized depending on
    * the `CancelToken` used - for example if `Unit` is returned,
    * then the yielded task will not be cancelable and the internal
    * implementation will not have to worry about managing it, thus
    * increasing performance.
    */
  abstract class AsyncBuilder[CancelToken] {
    def create[A](register: (Scheduler, Callback[A]) => CancelToken): Task[A]
  }

  object AsyncBuilder extends AsyncBuilder0 {
    /** Returns the implicit `AsyncBuilder` available in scope for the
      * given `CancelToken` type.
      */
    def apply[CancelToken](implicit ref: AsyncBuilder[CancelToken]): AsyncBuilder[CancelToken] = ref

    /** For partial application of type parameters in [[Task.create]].
      *
      * Read about the
      * [[https://typelevel.org/cats/guidelines.html#partially-applied-type-params Partially-Applied Type Technique]].
      */
    private[eval] final class CreatePartiallyApplied[A](val dummy: Boolean = true)
      extends AnyVal {

      def apply[CancelToken](register: (Scheduler, Callback[A]) => CancelToken)
        (implicit B: AsyncBuilder[CancelToken]): Task[A] =
        B.create(register)
    }

    /** Implicit `AsyncBuilder` for non-cancelable tasks. */
    implicit val forUnit: AsyncBuilder[Unit] =
      new AsyncBuilder[Unit] {
        def create[A](register: (Scheduler, Callback[A]) => Unit): Task[A] =
          TaskCreate.asyncS(register)
      }

    /** Implicit `AsyncBuilder` for non-cancelable tasks built by a function
      * returning a [[monix.execution.Cancelable.Empty Cancelable.Empty]].
      *
      * This is a case of applying a compile-time optimization trick,
      * completely ignoring the provided cancelable value, since we've got
      * a guarantee that it doesn't do anything.
      */
    implicit val forCancelableDummy: AsyncBuilder[Cancelable.Empty] =
      new AsyncBuilder[Cancelable.Empty] {
        def create[A](register: (Scheduler, Callback[A]) => Cancelable.Empty): Task[A] =
          TaskCreate.asyncS(register)
      }

    /** Implicit `AsyncBuilder` for cancelable tasks, using
      * `cats.effect.IO` values for specifying cancelation actions,
      * see [[https://typelevel.org/cats-effect/ Cats Effect]].
      */
    implicit val forIO: AsyncBuilder[IO[Unit]] =
      new AsyncBuilder[IO[Unit]] {
        def create[A](register: (Scheduler, Callback[A]) => IO[Unit]): Task[A] =
          TaskCreate.cancelableIO(register)
      }

    /** Implicit `AsyncBuilder` for cancelable tasks, using
      * [[Task]] values for specifying cancelation actions.
      */
    implicit val forTask: AsyncBuilder[Task[Unit]] =
      new AsyncBuilder[Task[Unit]] {
        def create[A](register: (Scheduler, Callback[A]) => Task[Unit]): Task[A] =
          TaskCreate.cancelableTask(register)
      }

    /** Implicit `AsyncBuilder` for cancelable tasks, using
      * [[Coeval]] values for specifying cancelation actions.
      */
    implicit val forCoeval: AsyncBuilder[Coeval[Unit]] =
      new AsyncBuilder[Coeval[Unit]] {
        def create[A](register: (Scheduler, Callback[A]) => Coeval[Unit]): Task[A] =
          TaskCreate.cancelableCoeval(register)
      }
  }

  private[Task] abstract class AsyncBuilder0 {
    /** Implicit `AsyncBuilder` for cancelable tasks, using
      * [[monix.execution.Cancelable Cancelable]] values for
      * specifying cancelation actions.
      */
    implicit val forCancelable: AsyncBuilder[Cancelable] =
      new AsyncBuilder[Cancelable] {
        def create[A](register: (Scheduler, Callback[A]) => Cancelable): Task[A] =
          TaskCreate.cancelableS(register)
      }
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

  /** Internal API — A reference that boxes a [[FrameIndex]] possibly
    * using a thread-local.
    *
    * This definition is of interest only when creating
    * tasks with `Task.unsafeCreate`, which exposes internals,
    * is considered unsafe to use and is now deprecated.
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
    * `Task.unsafeCreate` (which is now deprecated).
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

  /** Internal API — The `Context` under which [[Task]] is supposed to
    * be executed.
    *
    * This definition is of interest only when creating
    * tasks with `Task.unsafeCreate`, which exposes internals,
    * is considered unsafe to use and is now deprecated.
    *
    * @param schedulerRef is the [[monix.execution.Scheduler Scheduler]]
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
    @deprecatedName('scheduler)
    private val schedulerRef: Scheduler,
    options: Options,
    connection: StackedCancelable,
    frameRef: FrameIndexRef) {


    /** The [[monix.execution.Scheduler Scheduler]] in charge of triggering
      * async boundaries, on the evaluation that happens via `runAsync`.
      */
    val scheduler: Scheduler =
      if (options.localContextPropagation)
        TracingScheduler(schedulerRef)
      else
        schedulerRef

    /** Helper that returns `true` if the current `Task` run-loop
      * should be canceled or `false` otherwise.
      */
    def shouldCancel: Boolean =
      options.autoCancelableRunLoops &&
      connection.isCanceled

    /** Returns the context's [[monix.execution.ExecutionModel ExecutionModel]]. */
    def executionModel: ExecutionModel =
      schedulerRef.executionModel

    /** Returns the index of the starting frame, to be used in case of a
      * context switch.
      */
    private[monix] def startFrame(currentFrame: FrameIndex = frameRef()): FrameIndex = {
      val em = schedulerRef.executionModel
      em match {
        case BatchedExecution(_) =>
          currentFrame
        case AlwaysAsyncExecution | SynchronousExecution =>
          em.nextFrameIndex(0)
      }
    }

    def withScheduler(s: Scheduler): Context =
      new Context(s, options, connection, frameRef)

    def withExecutionModel(em: ExecutionModel): Context =
      new Context(schedulerRef.withExecutionModel(em), options, connection, frameRef)

    def withOptions(opts: Options): Context =
      new Context(schedulerRef, opts, connection, frameRef)

    def withConnection(conn: StackedCancelable): Context =
      new Context(schedulerRef, options, conn, frameRef)
  }

  object Context {
    /** Initialize fresh [[Context]] reference. */
    def apply(scheduler: Scheduler, options: Options): Context =
      apply(scheduler, options, StackedCancelable())

    /** Initialize fresh [[Context]] reference. */
    def apply(scheduler: Scheduler, options: Options, connection: StackedCancelable): Context = {
      val em = scheduler.executionModel
      val frameRef = FrameIndexRef(em)
      new Context(scheduler, options, connection, frameRef)
    }
  }

  /** [[Task]] state describing an immediate synchronous value. */
  private[eval] final case class Now[A](value: A) extends Task[A] {
    // Optimizations to avoid the run-loop
    override def runAsync(cb: Callback[A])(implicit s: Scheduler): Cancelable = {
      if (s.executionModel != AlwaysAsyncExecution) cb.onSuccess(value)
      else s.executeAsync(() => cb.onSuccess(value))
      Cancelable.empty
    }
    override def runAsync(implicit s: Scheduler): CancelableFuture[A] =
      CancelableFuture.successful(value)
    override def runAsyncOpt(implicit s: Scheduler, opts: Options): CancelableFuture[A] =
      runAsync(s)
    override def runAsyncOpt(cb: Callback[A])(implicit s: Scheduler, opts: Options): Cancelable =
      runAsync(cb)(s)
  }

  /** [[Task]] state describing an immediate exception. */
  private[eval] final case class Error[A](ex: Throwable) extends Task[A] {
    // Optimizations to avoid the run-loop
    override def runAsync(cb: Callback[A])(implicit s: Scheduler): Cancelable = {
      if (s.executionModel != AlwaysAsyncExecution) cb.onError(ex)
      else s.executeAsync(() => cb.onError(ex))
      Cancelable.empty
    }
    override def runAsync(implicit s: Scheduler): CancelableFuture[A] =
      CancelableFuture.failed(ex)
    override def runAsyncOpt(implicit s: Scheduler, opts: Options): CancelableFuture[A] =
      runAsync(s)
    override def runAsyncOpt(cb: Callback[A])(implicit s: Scheduler, opts: Options): Cancelable =
      runAsync(cb)(s)
  }

  /** [[Task]] state describing an immediate synchronous value. */
  private[eval] final case class Eval[A](thunk: () => A)
    extends Task[A]

  /** Internal state, the result of [[Task.defer]] */
  private[eval] final case class Suspend[+A](thunk: () => Task[A])
    extends Task[A]

  /** Internal [[Task]] state that is the result of applying `flatMap`. */
  private[eval] final case class FlatMap[A, B](source: Task[A], f: A => Task[B])
    extends Task[B]

  /** Internal [[Coeval]] state that is the result of applying `map`. */
  private[eval] final case class Map[S, +A](source: Task[S], f: S => A, index: Int)
    extends Task[A] with (S => Task[A]) {

    def apply(value: S): Task[A] =
      new Now(f(value))
    override def toString: String =
      super[Task].toString
  }

  /** Constructs a lazy [[Task]] instance whose result will
    * be computed asynchronously.
    *
    * Unsafe to build directly, only use if you know what you're doing.
    * For building `Async` instances safely, see [[cancelableS]].
    *
    * @param register is the side-effecting, callback-enabled function
    *        that starts the asynchronous computation and registers
    *        the callback to be called on completion
    *        
    * @param trampolineBefore is an optimization that instructs the 
    *        run-loop to insert a trampolined async boundary before
    *        evaluating the `register` function
    */
  private[monix] final case class Async[+A](
    register: (Context, Callback[A]) => Unit,
    trampolineBefore: Boolean = false,
    trampolineAfter: Boolean = true,
    restoreLocals: Boolean = true)
    extends Task[A]

  /** Internal API — starts the execution of a Task with a guaranteed
    * asynchronous boundary, by providing
    * the needed [[monix.execution.Scheduler Scheduler]],
    * [[monix.execution.cancelables.StackedCancelable StackedCancelable]]
    * and [[Callback]].
    *
    * DO NOT use directly, as it is UNSAFE to use, unless you know
    * what you're doing. Prefer [[Task.runAsync(cb* Task.runAsync]]
    * and [[Task.executeAsync .executeAsync]].
    */
  private[monix] def unsafeStartAsync[A](source: Task[A], context: Context, cb: Callback[A]): Unit =
    TaskRunLoop.restartAsync(source, context, cb, null, null, null)

  /** Internal API — a variant of [[unsafeStartAsync]] that tries to detect
    * if the `source` is known to fork and in such a case it avoids creating
    * an extraneous async boundary.
    */
  private[monix] def unsafeStartEnsureAsync[A](source: Task[A], context: Context, cb: Callback[A]): Unit = {
    if (ForkedRegister.detect(source))
      unsafeStartNow(source, context, cb)
    else
      unsafeStartAsync(source, context, cb)
  }

  /** Internal API — starts the execution of a Task with a guaranteed
    * [[monix.execution.schedulers.TrampolinedRunnable trampolined asynchronous boundary]],
    * by providing the needed [[monix.execution.Scheduler Scheduler]],
    * [[monix.execution.cancelables.StackedCancelable StackedCancelable]]
    * and [[Callback]].
    *
    * DO NOT use directly, as it is UNSAFE to use, unless you know
    * what you're doing. Prefer [[Task.runAsync(cb* Task.runAsync]]
    * and [[Task.executeAsync .executeAsync]].
    */
  private[monix] def unsafeStartTrampolined[A](source: Task[A], context: Context, cb: Callback[A]): Unit =
    context.scheduler.execute(new TrampolinedRunnable {
      def run(): Unit =
        TaskRunLoop.startFull(source, context, cb, null, null, null, context.frameRef())
    })

  /** Unsafe utility - starts the execution of a Task, by providing
    * the needed [[monix.execution.Scheduler Scheduler]],
    * [[monix.execution.cancelables.StackedCancelable StackedCancelable]]
    * and [[Callback]].
    *
    * DO NOT use directly, as it is UNSAFE to use, unless you know
    * what you're doing. Prefer [[Task.runAsync(cb* Task.runAsync]].
    */
  private[monix] def unsafeStartNow[A](source: Task[A], context: Context, cb: Callback[A]): Unit =
    TaskRunLoop.startFull(source, context, cb, null, null, null, context.frameRef())

  /** Internal, reusable reference. */
  private[this] val neverRef: Async[Nothing] =
    Async((_,_) => (), trampolineBefore = false, trampolineAfter = false)

  /** Internal, reusable reference. */
  private val nowConstructor: Any => Task[Nothing] =
    ((a: Any) => new Now(a)).asInstanceOf[Any => Task[Nothing]]

  /** Internal, reusable reference. */
  private val raiseConstructor: Throwable => Task[Nothing] =
    e => new Error(e)

  /** Used as optimization by [[Task.failed]]. */
  private object Failed extends StackFrame[Any, Task[Throwable]] {
    def apply(a: Any): Task[Throwable] =
      Error(new NoSuchElementException("failed"))
    def recover(e: Throwable): Task[Throwable] =
      Now(e)
  }

  /** Used as optimization by [[Task.doOnFinish]]. */
  private final class DoOnFinish[A](f: Option[Throwable] => Task[Unit])
    extends StackFrame[A, Task[A]] {

    def apply(a: A): Task[A] =
      f(None).map(_ => a)
    def recover(e: Throwable): Task[A] =
      f(Some(e)).flatMap(_ => Task.Error(e))
  }

  /** Used as optimization by [[Task.redeem]]. */
  private final class Redeem[A, B](fe: Throwable => B, fs: A => B)
    extends StackFrame[A, Task[B]] {

    def apply(a: A): Task[B] = new Now(fs(a))
    def recover(e: Throwable): Task[B] = new Now(fe(e))
  }

  /** Used as optimization by [[Task.attempt]]. */
  private object AttemptTask extends StackFrame[Any, Task[Either[Throwable, Any]]] {
    override def apply(a: Any): Task[Either[Throwable, Any]] =
      new Now(new Right(a))
    override def recover(e: Throwable): Task[Either[Throwable, Any]] =
      new Now(new Left(e))
  }

  /** Used as optimization by [[Task.materialize]]. */
  private object MaterializeTask extends StackFrame[Any, Task[Try[Any]]] {
    override def apply(a: Any): Task[Try[Any]] =
      new Now(new Success(a))
    override def recover(e: Throwable): Task[Try[Any]] =
      new Now(new Failure(e))
  }
}

private[eval] abstract class TaskInstancesLevel1 extends TaskInstancesLevel0 {
  /** Global instance for `cats.effect.Async` and for `cats.effect.Concurrent`.
    *
    * Implied are also `cats.CoflatMap`, `cats.Applicative`, `cats.Monad`,
    * `cats.MonadError` and `cats.effect.Sync`.
    *
    * As trivia, it's named "catsAsync" and not "catsConcurrent" because
    * it represents the `cats.effect.Async` lineage, up until
    * `cats.effect.Effect`, which imposes extra restrictions, in our case
    * the need for a `Scheduler` to be in scope (see [[Task.catsEffect]]).
    * So by naming the lineage, not the concrete sub-type implemented, we avoid
    * breaking compatibility whenever a new type class (that we can implement)
    * gets added into Cats.
    *
    * Seek more info about Cats, the standard library for FP, at:
    *
    *  - [[https://typelevel.org/cats/ typelevel/cats]]
    *  - [[https://github.com/typelevel/cats-effect typelevel/cats-effect]]
    */
  implicit def catsAsync: CatsConcurrentForTask =
    CatsConcurrentForTask

  /** Global instance for `cats.Parallel`.
    *
    * The `Parallel` type class is useful for processing
    * things in parallel in a generic way, usable with
    * Cats' utils and syntax:
    *
    * {{{
    *   import cats.syntax.all._
    *
    *   (taskA, taskB, taskC).parMap { (a, b, c) =>
    *     a + b + c
    *   }
    * }}}
    *
    * Seek more info about Cats, the standard library for FP, at:
    *
    *  - [[https://typelevel.org/cats/ typelevel/cats]]
    *  - [[https://github.com/typelevel/cats-effect typelevel/cats-effect]]
    */
  implicit def catsParallel: CatsParallelForTask =
    CatsParallelForTask

  /** Given an `A` type that has a `cats.Monoid[A]` implementation,
    * then this provides the evidence that `Task[A]` also has
    * a `Monoid[ Task[A] ]` implementation.
    */
  implicit def catsMonoid[A](implicit A: Monoid[A]): Monoid[Task[A]] =
    new CatsMonadToMonoid[Task, A]()(CatsConcurrentForTask, A)
}

private[eval] abstract class TaskInstancesLevel0 extends TaskParallelNewtype {
  /** Global instance for `cats.effect.Effect` and for
    * `cats.effect.ConcurrentEffect`.
    *
    * Implied are `cats.CoflatMap`, `cats.Applicative`, `cats.Monad`,
    * `cats.MonadError`, `cats.effect.Sync` and `cats.effect.Async`.
    *
    * Note this is different from
    * [[monix.eval.Task.catsAsync Task.catsAsync]] because we need an
    * implicit [[monix.execution.Scheduler Scheduler]] in scope in
    * order to trigger the execution of a `Task`. It's also lower
    * priority in order to not trigger conflicts, because
    * `Effect <: Async` and `ConcurrentEffect <: Concurrent with Effect`.
    *
    * As trivia, it's named "catsEffect" and not "catsConcurrentEffect"
    * because it represents the `cats.effect.Effect` lineage, as in the
    * minimum that this value will support in the future. So by naming the
    * lineage, not the concrete sub-type implemented, we avoid breaking
    * compatibility whenever a new type class (that we can implement)
    * gets added into Cats.
    *
    * Seek more info about Cats, the standard library for FP, at:
    *
    *  - [[https://typelevel.org/cats/ typelevel/cats]]
    *  - [[https://github.com/typelevel/cats-effect typelevel/cats-effect]]
    *
    * @param s is a [[monix.execution.Scheduler Scheduler]] that needs
    *        to be available in scope
    */
  implicit def catsEffect(implicit s: Scheduler, opts: Task.Options = Task.defaultOptions): CatsConcurrentEffectForTask =
    new CatsConcurrentEffectForTask

  /** Given an `A` type that has a `cats.Semigroup[A]` implementation,
    * then this provides the evidence that `Task[A]` also has
    * a `Semigroup[ Task[A] ]` implementation.
    *
    * This has a lower-level priority than [[Task.catsMonoid]]
    * in order to avoid conflicts.
    */
  implicit def catsSemigroup[A](implicit A: Semigroup[A]): Semigroup[Task[A]] =
    new CatsMonadToSemigroup[Task, A]()(CatsConcurrentForTask, A)
}

private[eval] abstract class TaskParallelNewtype extends TaskTimers {
  /** Newtype encoding for an `Task` data type that has a [[cats.Applicative]]
    * capable of doing parallel processing in `ap` and `map2`, needed
    * for implementing [[cats.Parallel]].
    *
    * Helpers are provided for converting back and forth in `Par.apply`
    * for wrapping any `Task` value and `Par.unwrap` for unwrapping.
    *
    * The encoding is based on the "newtypes" project by
    * Alexander Konovalov, chosen because it's devoid of boxing issues and
    * a good choice until opaque types will land in Scala.
    */
  type Par[+A] = Par.Type[A]

  /** Newtype encoding, see the [[Task.Par]] type alias
    * for more details.
    */
  object Par extends Newtype1[Task]
}

private[eval] abstract class TaskTimers extends TaskBinCompatCompanion {
  /**
    * Default, pure, globally visible `cats.effect.Timer`
    * implementation that defers the evaluation to `Task`'s default
    * [[monix.execution.Scheduler Scheduler]]
    * (that's being injected in [[Task.runAsync(implicit* runAsync]]).
    */
  implicit val timer: Timer[Task] =
    new Timer[Task] {
      override def clockRealTime(unit: TimeUnit): Task[Long] =
        Task.deferAction(sc => Task.now(sc.clockRealTime(unit)))
      override def clockMonotonic(unit: TimeUnit): Task[Long] =
        Task.deferAction(sc => Task.now(sc.clockMonotonic(unit)))
      override def shift: Task[Unit] =
        Task.shift
      override def sleep(duration: FiniteDuration): Task[Unit] =
        Task.sleep(duration)
    }

  /** Builds a `cats.effect.Timer` instance, given a
    * [[monix.execution.Scheduler Scheduler]] reference.
    */
  def timer(s: Scheduler): Timer[Task] =
    new Timer[Task] {
      override def clockRealTime(unit: TimeUnit): Task[Long] =
        Task.eval(s.clockRealTime(unit))
      override def clockMonotonic(unit: TimeUnit): Task[Long] =
        Task.eval(s.clockMonotonic(unit))
      override val shift: Task[Unit] =
        Task.shift(s)
      override def sleep(duration: FiniteDuration): Task[Unit] =
        Task.sleep(duration).executeOn(s)
    }
}
