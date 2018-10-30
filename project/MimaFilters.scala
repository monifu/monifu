import com.typesafe.tools.mima.core._
import com.typesafe.tools.mima.core.ProblemFilters.exclude

object MimaFilters {

  lazy val changesFor_3_0_0_RC2 = Seq(
    //
    // BREAKING CHANGES: AsyncQueue
    //
    exclude[DirectMissingMethodProblem]("monix.reactive.observers.buffers.ConcurrentQueue#FromMessagePassingQueue.drain"),
    exclude[DirectMissingMethodProblem]("monix.reactive.observers.buffers.ConcurrentQueue.drain"),
    exclude[ReversedMissingMethodProblem]("monix.reactive.observers.buffers.ConcurrentQueue.drainToBuffer"),
    exclude[DirectMissingMethodProblem]("monix.reactive.observers.buffers.ConcurrentQueue#FromAbstractQueue.drain"),
    exclude[MissingClassProblem]("monix.execution.misc.AsyncQueue$State$"),
    exclude[MissingClassProblem]("monix.execution.misc.AsyncQueue$"),
    exclude[MissingClassProblem]("monix.execution.misc.AsyncQueue$State"),
    exclude[MissingClassProblem]("monix.execution.misc.AsyncQueue"),
    //
    // BREAKING CHANGES: Semaphore
    //
    exclude[MissingClassProblem]("monix.execution.misc.AsyncSemaphore$CancelAcquisition"),
    exclude[MissingClassProblem]("monix.execution.misc.AsyncSemaphore$State"),
    exclude[MissingClassProblem]("monix.execution.misc.AsyncSemaphore$State$"),
    exclude[MissingClassProblem]("monix.execution.misc.AsyncSemaphore"),
    exclude[MissingClassProblem]("monix.execution.misc.AsyncSemaphore$"),
    exclude[MissingClassProblem]("monix.eval.TaskSemaphore$"),
    exclude[MissingClassProblem]("monix.eval.TaskSemaphore"),
    //
    // BREAKING CHANGES: MVar
    //
    exclude[MissingClassProblem]("monix.eval.MVar"),
    exclude[MissingClassProblem]("monix.eval.MVar$"),
    exclude[MissingClassProblem]("monix.eval.MVar$AsyncMVarImpl"),
    exclude[MissingClassProblem]("monix.execution.misc.AsyncVar$State"),
    exclude[MissingClassProblem]("monix.execution.misc.AsyncVar$WaitForTake"),
    exclude[MissingClassProblem]("monix.execution.misc.AsyncVar$Empty$"),
    exclude[MissingClassProblem]("monix.execution.misc.AsyncVar"),
    exclude[MissingClassProblem]("monix.execution.misc.AsyncVar$WaitForPut$"),
    exclude[MissingClassProblem]("monix.execution.misc.AsyncVar$WaitForTake$"),
    exclude[MissingClassProblem]("monix.execution.misc.AsyncVar$WaitForPut"),
    exclude[MissingClassProblem]("monix.execution.misc.AsyncVar$Empty"),
    exclude[MissingClassProblem]("monix.execution.misc.AsyncVar$"),
    exclude[MissingClassProblem]("monix.execution.misc.AsyncVar$State$"),
    //
    // BREAKING CHANGES: https://github.com/monix/monix/pull/729
    //
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Observable.fromLinesReader"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.fromCharsReader"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Observable.fromCharsReader"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.fromInputStream"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Observable.fromInputStream"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.mapFuture"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.doOnErrorEval"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.doOnTerminateEval"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.mapEval"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.doOnCompleteEval"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.headF"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.doOnErrorTask"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.minF"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.findF"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.isEmptyF"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.runAsyncGetLast"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.doOnTerminateTask"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.lastF"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.scanEval"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Observable.doAfterSubscribe"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.doOnNextEval"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.mapTask"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Observable.doOnSubscribe"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.doOnNextAckEval"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.forAllF"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Observable.doOnComplete"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.maxF"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.existsF"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.foldLeftF"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.doOnCompleteTask"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.forAllL"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Observable.doOnEarlyStop"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.doAfterTerminateEval"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.maxByF"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.scanTask"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.doOnEarlyStopEval"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.doOnNextTask"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.sumF"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.doOnNextAckTask"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.minByF"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.doAfterTerminate"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.nonEmptyF"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.foldWhileLeftF"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.headOrElseF"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.doOnTerminate"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.firstOrElseF"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.foldF"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.doAfterTerminateTask"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.runAsyncGetFirst"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.doOnEarlyStopTask"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Observable.doOnSubscriptionCancel"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.countF"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.operators.DoOnSubscribeObservable#After.this"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.operators.DoOnCompleteOperator.this"),
    exclude[MissingClassProblem]("monix.reactive.internal.operators.EvalOnNextAckOperator"),
    exclude[MissingClassProblem]("monix.reactive.internal.operators.EvalOnTerminateOperator"),
    exclude[MissingClassProblem]("monix.reactive.internal.operators.EvalOnErrorOperator"),
    exclude[DirectMissingMethodProblem]("monix.reactive.internal.operators.ConcatMapObservable.this"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.operators.DoOnEarlyStopOperator.this"),
    exclude[MissingClassProblem]("monix.reactive.internal.operators.EvalOnCompleteOperator"),
    exclude[MissingClassProblem]("monix.reactive.internal.operators.EvalOnEarlyStopOperator"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.operators.DoOnSubscribeObservable#Before.this"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.operators.DoOnSubscriptionCancelObservable.this"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable#DeprecatedExtensions.executeWithFork$extension"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task.transformWith"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task.runOnComplete"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task.runSyncMaybeOpt"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task.transform"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task.zipMap"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task.cancelable"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task.coeval"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task.zip"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task.runSyncMaybe"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task.fork"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task#DeprecatedExtensions.executeWithFork$extension"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task#DeprecatedExtensions.delayExecutionWith$extension"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task#DeprecatedExtensions.delayResultBySelector$extension"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Observable.fromIterator"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.fromIterator"),
    exclude[DirectMissingMethodProblem]("monix.reactive.internal.builders.IteratorAsObservable.this"),
    //
    // Breackage - Listener/Callback refactoring
    exclude[MissingClassProblem]("monix.execution.Listener$"),
    exclude[MissingClassProblem]("monix.execution.Listener"),
    exclude[IncompatibleMethTypeProblem]("monix.execution.misc.AsyncVar.unsafePut"),
    exclude[IncompatibleMethTypeProblem]("monix.execution.misc.AsyncVar.unsafeRead"),
    exclude[IncompatibleMethTypeProblem]("monix.execution.misc.AsyncVar.unsafeTake"),
    exclude[IncompatibleMethTypeProblem]("monix.execution.misc.AsyncVar#WaitForPut.apply"),
    exclude[IncompatibleMethTypeProblem]("monix.execution.misc.AsyncVar#WaitForPut.copy"),
    exclude[IncompatibleResultTypeProblem]("monix.execution.misc.AsyncVar#WaitForPut.copy$default$1"),
    exclude[IncompatibleResultTypeProblem]("monix.execution.misc.AsyncVar#WaitForPut.first"),
    exclude[IncompatibleMethTypeProblem]("monix.execution.misc.AsyncVar#WaitForPut.this"),
    exclude[MissingClassProblem]("monix.eval.Callback$SafeCallback"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task#Error.runAsyncOpt"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.Task#Error.runAsyncOpt"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.Task#Error.runAsync"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task#Error.runAsync"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task#Error.ex"),
    exclude[MissingClassProblem]("monix.eval.Callback"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.Task.unsafeStartAsync"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.Task.unsafeStartNow"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.Task.unsafeStartTrampolined"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task.runAsyncOpt"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.Task.runAsyncOpt"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task.runAsync"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.Task.runAsync"),
    exclude[MissingClassProblem]("monix.eval.Callback$Extensions"),
    exclude[MissingClassProblem]("monix.eval.Callback$"),
    exclude[MissingClassProblem]("monix.eval.Callback$Extensions$"),
    exclude[MissingClassProblem]("monix.eval.Callback$EmptyCallback"),
    exclude[MissingClassProblem]("monix.eval.Callback$ContramapCallback"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task#Now.runAsyncOpt"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.Task#Now.runAsyncOpt"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.Task#Now.runAsync"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task#Now.runAsync"),
    exclude[MissingTypesProblem]("monix.eval.internal.TaskRunSyncUnsafe$BlockingCallback"),
    exclude[MissingTypesProblem]("monix.eval.internal.TaskCancellation$RaiseCallback"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.internal.TaskMemoize#Register.apply"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.internal.TaskSleep#SleepRunnable.this"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Consumer#Sync.createSubscriber"),
    exclude[ReversedMissingMethodProblem]("monix.reactive.Consumer#Sync.createSubscriber"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Consumer.createSubscriber"),
    exclude[ReversedMissingMethodProblem]("monix.reactive.Consumer.createSubscriber"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.subscribers.ForeachSubscriber.this"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.consumers.HeadOptionConsumer.createSubscriber"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.consumers.CompleteConsumer.createSubscriber"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.consumers.FromObserverConsumer.createSubscriber"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.consumers.CancelledConsumer.createSubscriber"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.consumers.HeadConsumer.createSubscriber"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.consumers.MapTaskConsumer.createSubscriber"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.consumers.CreateConsumer.createSubscriber"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.consumers.TransformInputConsumer.createSubscriber"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.consumers.FoldLeftTaskConsumer.createSubscriber"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.consumers.LoadBalanceConsumer.createSubscriber"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.consumers.ContraMapConsumer.createSubscriber"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.consumers.ForeachConsumer.createSubscriber"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.consumers.FoldLeftConsumer.createSubscriber"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.consumers.RaiseErrorConsumer.createSubscriber"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.consumers.FirstNotificationConsumer.createSubscriber"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.consumers.ForeachAsyncConsumer.createSubscriber"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.internal.consumers.MapConsumer.createSubscriber"),
    //
    // Breakage - monix.catnap.CircuitBreaker
    exclude[MissingClassProblem]("monix.eval.TaskCircuitBreaker"),
    exclude[MissingClassProblem]("monix.eval.TaskCircuitBreaker$Closed$"),
    exclude[MissingClassProblem]("monix.eval.TaskCircuitBreaker$HalfOpen"),
    exclude[MissingClassProblem]("monix.eval.TaskCircuitBreaker$"),
    exclude[MissingClassProblem]("monix.eval.TaskCircuitBreaker$Closed"),
    exclude[MissingClassProblem]("monix.eval.TaskCircuitBreaker$Open$"),
    exclude[MissingClassProblem]("monix.eval.TaskCircuitBreaker$State"),
    exclude[MissingClassProblem]("monix.eval.TaskCircuitBreaker$Open"),
    exclude[MissingClassProblem]("monix.eval.TaskCircuitBreaker$HalfOpen$"),
    exclude[IncompatibleMethTypeProblem]("monix.execution.Scheduler#Extensions.timer$extension"),
    exclude[IncompatibleMethTypeProblem]("monix.execution.Scheduler#Extensions.timer"),
    exclude[DirectMissingMethodProblem]("monix.execution.internal.AttemptCallback.tick"),
    //
    // Breackage - the ++ operator now has a lazy argument
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Observable.++"),
    //
    // Breakage — relaxed requirement
    exclude[IncompatibleMethTypeProblem]("monix.execution.schedulers.TracingScheduler.apply"),
    // Breakage — changed Task#foreach signature
    exclude[IncompatibleResultTypeProblem]("monix.eval.Task.foreach"),
    // Breakage - changed type
    exclude[IncompatibleResultTypeProblem]("monix.execution.Cancelable.empty"),
    // Breackage — made CompositeException final
    exclude[FinalClassProblem]("monix.execution.exceptions.CompositeException"),
    // Breakage — extra implicit param
    exclude[DirectMissingMethodProblem]("monix.eval.TaskInstancesLevel0.catsEffect"),
    exclude[DirectMissingMethodProblem]("monix.eval.instances.CatsConcurrentEffectForTask.this"),
    exclude[DirectMissingMethodProblem]("monix.eval.instances.CatsEffectForTask.this"),
    // Breakage - moved deprecated methods back into Task's class for better compatibility
    exclude[DirectMissingMethodProblem]("monix.eval.Task.DeprecatedExtensions"),
    exclude[MissingClassProblem]("monix.eval.Task$DeprecatedExtensions"),
    exclude[MissingClassProblem]("monix.eval.Task$DeprecatedExtensions$"),
    // Breakage - PR #675: switch to standard NonFatal
    exclude[MissingClassProblem]("monix.execution.misc.NonFatal$"),
    exclude[MissingClassProblem]("monix.execution.misc.NonFatal"),
    // Semi-Breakage - new method in sealed class
    exclude[ReversedMissingMethodProblem]("monix.execution.cancelables.StackedCancelable.tryReactivate"),
    // Cats-Effect RC2 Upgrade
    exclude[DirectMissingMethodProblem]("monix.eval.instances.CatsConcurrentEffectForTask.onCancelRaiseError"),
    exclude[DirectMissingMethodProblem]("monix.eval.instances.CatsEffectForTask.shift"),
    exclude[DirectMissingMethodProblem]("monix.eval.instances.CatsAsyncForTask.shift"),
    exclude[DirectMissingMethodProblem]("monix.eval.instances.CatsConcurrentForTask.onCancelRaiseError"),
    // TaskLocal changes
    exclude[IncompatibleMethTypeProblem]("monix.eval.TaskLocal.this"),
    // Hide Task.Context, change conversions (Cats-Effect RC2 upgrade, part 2)
    exclude[IncompatibleResultTypeProblem]("monix.eval.Task#Context.frameRef"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.Task#Context.copy"),
    exclude[IncompatibleResultTypeProblem]("monix.eval.Task#Context.copy$default$4"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.Task#Context.this"),
    exclude[MissingClassProblem]("monix.eval.Task$FrameIndexRef$Local"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.Task.toIO"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task.to"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.Task#Context.apply"),
    exclude[MissingClassProblem]("monix.eval.Task$FrameIndexRef"),
    exclude[MissingClassProblem]("monix.eval.Task$FrameIndexRef$Dummy$"),
    exclude[MissingClassProblem]("monix.eval.Task$FrameIndexRef$"),
    // Change TaskApp
    exclude[DirectMissingMethodProblem]("monix.eval.TaskApp.runl"),
    exclude[DirectMissingMethodProblem]("monix.eval.TaskApp.runc"),
    exclude[DirectMissingMethodProblem]("monix.eval.TaskApp.run"),
    exclude[ReversedMissingMethodProblem]("monix.eval.TaskApp.catsEffect"),
    exclude[ReversedMissingMethodProblem]("monix.eval.TaskApp.run"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.TaskApp.run"),
    exclude[IncompatibleResultTypeProblem]("monix.eval.TaskApp.options"),
    exclude[IncompatibleResultTypeProblem]("monix.eval.TaskApp.scheduler"),
    exclude[ReversedMissingMethodProblem]("monix.eval.TaskApp.options"),
    exclude[ReversedMissingMethodProblem]("monix.eval.TaskApp.scheduler"),
    // Switched to TaskLike instead of Effect, in the implementation of observable
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Observable.repeatEvalF"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Consumer.mapEval"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Observable.doOnErrorEval"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Observable.doOnTerminateEval"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Observable.mapEval"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Observable.doOnCompleteEval"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Observable.scanEval"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Observable.doOnNextEval"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Observable.doOnNextAckEval"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Observable.doAfterTerminateEval"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Observable.doOnEarlyStopEval"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Consumer.foreachEval"),
    exclude[IncompatibleMethTypeProblem]("monix.reactive.Consumer.foldLeftEval"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.fromEffect"),
    // Breakage - PR #700: renamed methods
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.delaySubscriptionWith"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.delaySubscription"),
    // Breakage — PR 724: https://github.com/monix/monix/pull/724
    exclude[MissingClassProblem]("monix.eval.Fiber$Impl"),
    exclude[DirectMissingMethodProblem]("monix.eval.Fiber.apply"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.TaskFromFuture.lightBuild"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.TaskCancellation.signal"),
    // Breakage - PR 739: https://github.com/monix/monix/pull/739
    exclude[DirectMissingMethodProblem]("monix.reactive.internal.operators.BufferWithSelectorObservable.this"),
    exclude[DirectMissingMethodProblem]("monix.reactive.Observable.bufferTimedWithPressure"),
    // Changing internal Task cancelation model to back-pressure finalizers
    exclude[DirectMissingMethodProblem]("monix.execution.Cancelable#Extensions.cancelIO$extension"),
    exclude[DirectMissingMethodProblem]("monix.execution.Cancelable#Extensions.cancelIO"),
    exclude[IncompatibleResultTypeProblem]("monix.eval.Task#Context.connection"),
    exclude[IncompatibleResultTypeProblem]("monix.eval.Task#Context.copy$default$3"),
    exclude[FinalMethodProblem]("monix.eval.Task.runAsync"),
    exclude[FinalMethodProblem]("monix.eval.Task.runAsync"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.Callback.apply"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.Task.async"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task.unsafeCreate"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task.create"),
    exclude[FinalMethodProblem]("monix.eval.Task.runAsync"),
    exclude[FinalMethodProblem]("monix.eval.Task.runAsync"),
    exclude[FinalMethodProblem]("monix.eval.Task.runAsync"),
    exclude[FinalMethodProblem]("monix.eval.Task.runAsync"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.internal.TaskCancellation#RaiseCallback.this"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.TaskRunLoop.startLight"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.internal.TaskConversions#CreateCallback.this"),
    exclude[MissingClassProblem]("monix.eval.internal.TaskCancellation$RaiseCancelable"),
    // Internals ...
    exclude[DirectMissingMethodProblem]("monix.eval.Task#MaterializeTask.recover"),
    exclude[DirectMissingMethodProblem]("monix.eval.Coeval#MaterializeCoeval.recover"),
    exclude[DirectMissingMethodProblem]("monix.eval.Coeval#AttemptCoeval.recover"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task#AttemptTask.recover"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.StackFrame.recover"),
    exclude[ReversedMissingMethodProblem]("monix.eval.internal.StackFrame.recover"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.StackFrame.errorHandler"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.StackFrame.fold"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.TaskBracket#ReleaseRecover.recover"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.TaskBracket#ReleaseRecover.this"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.CoevalBracket#ReleaseRecover.recover"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.CoevalBracket#ReleaseRecover.this"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.TaskBracket.apply"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.TaskEffect.runAsync"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.TaskEffect.runCancelable"),
    exclude[MissingClassProblem]("monix.eval.internal.CoevalBracket$ReleaseFrame"),
    exclude[MissingClassProblem]("monix.eval.internal.TaskBracket$ReleaseFrame"),
    exclude[MissingClassProblem]("monix.eval.internal.StackFrame$Fold"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.StackFrame#ErrorHandler.recover"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.CoevalBracket.apply"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.TaskCreate.apply"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task#Async.apply"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task#Async.copy"),
    exclude[DirectMissingMethodProblem]("monix.eval.Task#Async.this"),
    exclude[MissingClassProblem]("monix.eval.internal.TaskRunLoop$RestartCallback"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.internal.TaskRunLoop.executeAsyncTask"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.internal.TaskRunLoop.restartAsync"),
    exclude[IncompatibleMethTypeProblem]("monix.eval.internal.TaskRunLoop.startFull"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.TaskEffect.cancelable"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.TaskEffect.async"),
    exclude[DirectMissingMethodProblem]("monix.execution.internal.collection.ArrayStack.currentCapacity"),
    exclude[DirectMissingMethodProblem]("monix.execution.internal.collection.ArrayStack.minimumCapacity"),
    exclude[DirectMissingMethodProblem]("monix.execution.internal.collection.ArrayStack.size"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.TaskStart.apply"),
    exclude[MissingClassProblem]("monix.eval.internal.TaskEffect$CreateCallback"),
    exclude[IncompatibleResultTypeProblem]("monix.execution.internal.collection.ArrayStack.clone"),
    exclude[MissingTypesProblem]("monix.execution.internal.collection.ArrayStack"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.TaskCancellation#RaiseCancelable.this"),
    exclude[MissingClassProblem]("monix.eval.internal.TaskBracket$ReleaseRecover"),
    exclude[MissingClassProblem]("monix.eval.instances.ParallelApplicative$"),
    exclude[MissingClassProblem]("monix.eval.instances.ParallelApplicative"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.TaskConversions.from"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.TaskConversions.to"),
    exclude[IncompatibleResultTypeProblem]("monix.eval.instances.CatsConcurrentEffectForTask.runCancelable"),
    exclude[IncompatibleResultTypeProblem]("monix.eval.instances.CatsEffectForTask.runAsync"),
    exclude[MissingClassProblem]("monix.eval.instances.ParallelApplicative"),
    exclude[MissingClassProblem]("monix.reactive.internal.operators.DelaySubscriptionByTimespanObservable"),
    exclude[MissingClassProblem]("monix.reactive.internal.operators.DelaySubscriptionWithTriggerObservable")
  )
}
