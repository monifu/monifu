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

package monix.execution

import monix.execution.misc.{HygieneUtilMacros, InlineMacros}
import scala.concurrent.duration.Duration
import scala.concurrent.{Promise, CanAwait, ExecutionContext, Future}
import scala.reflect.macros.whitebox
import scala.util.{Failure, Success, Try}
import scala.language.experimental.macros

/** Represents an acknowledgement of processing that a consumer
  * sends back upstream. Useful to implement back-pressure.
  */
sealed abstract class Ack extends Future[Ack] with Serializable

object Ack {
  /** Acknowledgement of processing that signals upstream that the
    * consumer is interested in receiving more events.
    */
  sealed abstract class Continue extends Ack

  case object Continue extends Continue with Future[Continue] { self =>
    final val AsSuccess = Success(Continue)
    final val value = Some(AsSuccess)
    final val isCompleted = true

    final def ready(atMost: Duration)(implicit permit: CanAwait) = self
    final def result(atMost: Duration)(implicit permit: CanAwait) = Continue

    final def onComplete[U](func: Try[Continue] => U)(implicit executor: ExecutionContext): Unit =
      executor.execute(new Runnable {
        def run(): Unit = func(AsSuccess)
      })
  }

  /** Acknowledgement of processing that signals to upstream that the
    * consumer is no longer interested in receiving events.
    */
  sealed abstract class Stop extends Ack

  case object Stop extends Stop with Future[Stop] { self =>
    final val AsSuccess = Success(Stop)
    final val value = Some(AsSuccess)
    final val isCompleted = true

    final def ready(atMost: Duration)(implicit permit: CanAwait) = self
    final def result(atMost: Duration)(implicit permit: CanAwait) = Stop

    final def onComplete[U](func: Try[Stop] => U)(implicit executor: ExecutionContext): Unit =
      executor.execute(new Runnable {
        def run(): Unit = func(AsSuccess)
      })
  }

  /** Type-alias for [[Stop]] provided in order to lessen the migration curve. */
  @deprecated("Use Ack.Stop", "2.0") type Cancel = Stop
  /** Type-alias for [[Stop]] provided in order to lessen the migration curve. */
  @deprecated("Use Ack.Stop", "2.0") def Cancel: Stop = Stop

  /** Helpers for dealing with synchronous `Future[Ack]` results,
    * powered by macros.
    */
  implicit class AckExtensions[Self <: Future[Ack]](val source: Self) extends AnyVal {
    /** Returns `true` if self is a direct reference to
      * `Continue` or `Stop`, `false` otherwise.
      */
    def isSynchronous: Boolean =
       macro Macros.isSynchronous[Self]

    /** Executes the given `callback` on `Continue`.
      *
      * Execution will happen synchronously if the `source` value is
      * a direct reference to `Continue` or `Stop`, or asynchronously
      * otherwise.
      */
    def syncOnContinue(callback: => Unit)(implicit s: Scheduler): Self =
      macro Macros.syncOnContinue[Self]

    /** Executes the given `callback` on `Stop` or on `Failure(ex)`.
      *
      * Execution will happen synchronously if the `source` value is
      * a direct reference to `Continue` or `Stop`, or asynchronously
      * otherwise.
      */
    def syncOnStopOrFailure(callback: => Unit)(implicit s: Scheduler): Self =
      macro Macros.syncOnStopOrFailure[Self]

    /** Given a mapping function, returns a new future reference that
      * is the result of a `map` operation applied to the source.
      *
      * Execution will happen synchronously if the `source` value is
      * a direct reference to `Continue` or `Stop`, or asynchronously
      * otherwise.
      */
    def syncMap(f: Ack => Ack)(implicit s: Scheduler): Future[Ack] =
      macro Macros.syncMap[Self]

    /** Given a mapping function, returns a new future reference that
      * is the result of a `flatMap` operation applied to the source.
      *
      * Execution will happen synchronously if the `source` value is
      * a direct reference to `Continue` or `Stop`, or asynchronously
      * otherwise.
      */
    def syncFlatMap(f: Ack => Future[Ack])(implicit s: Scheduler): Future[Ack] =
      macro Macros.syncFlatMap[Self]

    /** When the source future is completed, either through an exception,
      * or a value, apply the provided function.
      *
      * Execution will happen synchronously if the `source` value is
      * a direct reference to `Continue` or `Stop`, or asynchronously
      * otherwise.
      */
    def syncOnComplete(f: Try[Ack] => Unit)(implicit s: Scheduler): Unit =
      macro Macros.syncOnComplete[Self]

    /** If the source completes with a `Stop`, then complete the given
      * promise with a value.
      */
    def syncOnContinueFollow[T](p: Promise[T], value: T)(implicit s: Scheduler): Self = {
      if (source eq Continue)
        p.trySuccess(value)
      else if (source ne Stop)
        source.onComplete { r =>
          if (r.isSuccess && (r.get eq Continue))
            p.trySuccess(value)
        }

      source
    }

    /** If the source completes with a `Stop`, then complete the given
      * promise with a value.
      */
    def syncOnStopFollow[T](p: Promise[T], value: T)(implicit s: Scheduler): Self = {
      if (source eq Stop)
        p.trySuccess(value)
      else if (source ne Continue)
        source.onComplete { r =>
          if (r.isSuccess && (r.get eq Stop))
            p.trySuccess(value)
        }

      source
    }

    /** Tries converting an already completed `Future[Ack]` into a direct
      * reference to `Continue` or `Stop`. Useful for collapsing async
      * pipelines.
      */
    def syncTryFlatten(implicit r: UncaughtExceptionReporter): Future[Ack] =
      if (source == Continue || source == Stop) source else {
        if (source.isCompleted)
          source.value.get match {
            case Success(ack) => ack
            case Failure(ex) =>
              r.reportFailure(ex)
              Stop
          }
        else
          source
      }
  }

  /** Macro implementations for [[AckExtensions]]. */
  @macrocompat.bundle private[monix]
  class Macros(override val c: whitebox.Context) extends InlineMacros with HygieneUtilMacros {
    import c.universe._

    def isSynchronous[Self <: Future[Ack] : c.WeakTypeTag]: c.Expr[Boolean] = {
      val selfExpr = sourceFrom[Self](c.prefix.tree)
      val self = util.name("source")
      val ContinueSymbol = symbolOf[Continue].companion
      val StopSymbol = symbolOf[Stop].companion

      val tree =
        if (util.isClean(selfExpr))
          q"""($selfExpr eq $ContinueSymbol) || ($selfExpr eq $StopSymbol)"""
        else
          q"""
          val $self = $selfExpr
          ($self eq $ContinueSymbol) || ($self eq $StopSymbol)
          """

      inlineAndReset[Boolean](tree)
    }

    def syncOnContinue[Self <: Future[Ack] : c.WeakTypeTag](callback: Tree)(s: Tree): Tree = {
      val selfExpr = sourceFrom[Self](c.prefix.tree)
      val self = util.name("source")
      val scheduler = c.Expr[Scheduler](s)

      val execute = c.Expr[Unit](callback)
      val ContinueSymbol = symbolOf[Continue].companion
      val StopSymbol = symbolOf[Stop].companion
      val AckSymbol = symbolOf[Ack]
      val FutureSymbol = symbolOf[Future[_]]

      val tree =
        q"""
        val $self = $selfExpr
        if ($self eq $ContinueSymbol)
          try { $execute } catch {
            case ex: Throwable =>
              if (_root_.scala.util.control.NonFatal(ex))
                $scheduler.reportFailure(ex)
              else
                throw ex
          }
        else if (($self : $FutureSymbol[$AckSymbol]) != $StopSymbol) {
          $self.onComplete { result =>
            if (result.isSuccess && (result.get eq $ContinueSymbol)) { $execute }
          }($scheduler)
        }

        $self
        """

      inlineAndResetTree(tree)
    }

    def syncOnStopOrFailure[Self <: Future[Ack] : c.WeakTypeTag](callback: Tree)(s: Tree): Tree = {
      val selfExpr = sourceFrom[Self](c.prefix.tree)
      val self = util.name("source")
      val scheduler = c.Expr[Scheduler](s)

      val execute = c.Expr[Unit](callback)
      val ContinueSymbol = symbolOf[Continue].companion
      val StopSymbol = symbolOf[Stop].companion
      val AckSymbol = symbolOf[Ack]
      val FutureSymbol = symbolOf[Future[_]]

      val tree =
        q"""
        val $self = $selfExpr
        if ($self eq $StopSymbol)
          try { $execute } catch {
            case ex: Throwable =>
              if (_root_.scala.util.control.NonFatal(ex))
                $scheduler.reportFailure(ex)
              else
                throw ex
          }
        else if (($self : $FutureSymbol[$AckSymbol]) != $ContinueSymbol) {
          $self.onComplete { result =>
            if (result.isFailure || (result.get eq $StopSymbol)) { $execute }
          }($scheduler)
        }

        $self
        """

      inlineAndResetTree(tree)
    }

    def syncMap[Self <: Future[Ack] : c.WeakTypeTag](f: c.Expr[Ack => Ack])(s: c.Expr[Scheduler]): c.Expr[Future[Ack]] = {
      val selfExpr = sourceFrom[Self](c.prefix.tree)
      val schedulerExpr = s
      val self = util.name("source")
      val fn = util.name("fn")

      val ContinueSymbol = symbolOf[Continue].companion
      val StopSymbol = symbolOf[Stop].companion
      val AckSymbol = symbolOf[Ack]

      val tree =
        if (util.isClean(f)) {
          q"""
          val $self = $selfExpr

          if (($self eq $ContinueSymbol) || ($self eq $StopSymbol)) {
            try {
              $f($self.asInstanceOf[$AckSymbol]) : $AckSymbol
            } catch {
              case ex: _root_.java.lang.Throwable =>
                if (_root_.scala.util.control.NonFatal(ex)) {
                  $schedulerExpr.reportFailure(ex)
                  $StopSymbol
                } else {
                  throw ex
                }
            }
          } else {
            $self.map($f)
          }
          """
        } else {
          q"""
          val $self = $selfExpr
          val $fn: _root_.scala.Function1[$AckSymbol,$AckSymbol] = $f

          if (($self eq $ContinueSymbol) || ($self eq $StopSymbol))
            try {
              $fn($self.asInstanceOf[$AckSymbol]) : $AckSymbol
            } catch {
              case ex: Throwable =>
                if (_root_.scala.util.control.NonFatal(ex)) {
                  $schedulerExpr.reportFailure(ex)
                  $StopSymbol
                } else {
                  throw ex
                }
            }
          else {
            $self.map($fn)
          }
          """
        }

      inlineAndReset[Future[Ack]](tree)
    }

    def syncFlatMap[Self <: Future[Ack] : c.WeakTypeTag](f: c.Expr[Ack => Future[Ack]])(s: c.Expr[Scheduler]): c.Expr[Future[Ack]] = {
      val selfExpr = sourceFrom[Self](c.prefix.tree)
      val schedulerExpr = s
      val self = util.name("source")

      val ContinueSymbol = symbolOf[Continue].companion
      val StopSymbol = symbolOf[Stop].companion
      val AckSymbol = symbolOf[Ack]
      val FutureSymbol = symbolOf[Future[_]]

      val tree =
        if (util.isClean(f))
          q"""
          val $self = $selfExpr

          if (($self eq $ContinueSymbol) || ($self eq $StopSymbol))
            try {
              $f($self.asInstanceOf[$AckSymbol]) : $FutureSymbol[$AckSymbol]
            } catch {
              case ex: Throwable =>
                if (_root_.scala.util.control.NonFatal(ex)) {
                  $schedulerExpr.reportFailure(ex)
                  $StopSymbol
                } else {
                  throw ex
                }
            }
          else {
            $self.flatMap($f)
          }
          """
        else {
          val fn = util.name("fn")
          q"""
          val $self = $selfExpr
          val $fn: _root_.scala.Function1[$AckSymbol,$AckSymbol] = $f

          if (($self eq $ContinueSymbol) || ($self eq $StopSymbol))
            try {
              $fn($self.asInstanceOf[$AckSymbol]) : $FutureSymbol[$AckSymbol]
            } catch {
              case ex: Throwable =>
                if (_root_.scala.util.control.NonFatal(ex)) {
                  $schedulerExpr.reportFailure(ex)
                  $StopSymbol
                } else {
                  throw ex
                }
            }
          else {
            $self.flatMap($fn)
          }
          """
        }

      inlineAndReset[Future[Ack]](tree)
    }

    def syncOnComplete[Self <: Future[Ack] : c.WeakTypeTag](f: c.Expr[scala.util.Try[Ack] => Unit])
      (s: c.Expr[Scheduler]): c.Expr[Unit] = {

      val selfExpr = sourceFrom[Self](c.prefix.tree)
      val schedulerExpr = s
      val self = util.name("source")

      val SuccessObject = symbolOf[scala.util.Success[_]].companion
      val ContinueObject = symbolOf[Continue].companion
      val StopObject = symbolOf[Stop].companion
      val AckSymbol = symbolOf[Ack]
      val TrySymbol = symbolOf[scala.util.Try[_]]
      val UnitSymbol = symbolOf[Unit]
      val FutureSymbol = symbolOf[Future[_]]

      val tree =
        if (util.isClean(f))
          q"""
          val $self: $FutureSymbol[$AckSymbol] = $selfExpr

          if (($self eq $ContinueObject) || ($self eq $StopObject))
            try {
              $f($SuccessObject($self.asInstanceOf[$AckSymbol]) : $TrySymbol[$AckSymbol])
              ()
            } catch {
              case ex: Throwable =>
                if (_root_.scala.util.control.NonFatal(ex)) {
                  $schedulerExpr.reportFailure(ex)
                } else {
                  throw ex
                }
            }
          else {
            $self.onComplete($f)
          }
          """
        else {
          val fn = util.name("fn")
          q"""
          val $self: $FutureSymbol[$AckSymbol]  = $selfExpr
          val $fn: _root_.scala.Function1[$TrySymbol[$AckSymbol],$UnitSymbol] = $f

          if (($self eq $ContinueObject) || ($self eq $StopObject))
            try {
              $fn($SuccessObject($self.asInstanceOf[$AckSymbol]))
              ()
            } catch {
              case ex: Throwable =>
                if (_root_.scala.util.control.NonFatal(ex)) {
                  $schedulerExpr.reportFailure(ex)
                } else {
                  throw ex
                }
            }
          else {
            $self.onComplete($fn)
          }
          """
        }

      inlineAndReset[Unit](tree)
    }

    private[monix] def sourceFrom[Source : c.WeakTypeTag](tree: Tree): c.Expr[Source] = {
      val ackExtensions = symbolOf[AckExtensions[_]].name.toTermName
      tree match {
        case Apply(TypeApply(Select(_, `ackExtensions`), _), List(expr)) =>
          c.Expr[Source](expr)
        case _ =>
          c.warning(tree.pos, "Could not infer the implicit class source")
          c.Expr[Source](q"$tree.source")
      }
    }
  }
}

