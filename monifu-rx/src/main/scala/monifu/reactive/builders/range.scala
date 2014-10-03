package monifu.reactive.builders

import monifu.concurrent.Scheduler
import monifu.reactive.Ack.Continue
import monifu.reactive.Observable

import scala.annotation.tailrec

object range {
  /**
   * Creates an Observable that emits items in the given range.
   *
   * <img src="https://raw.githubusercontent.com/wiki/monifu/monifu/assets/rx-operators/range.png" />
   *
   * @param from the range start
   * @param until the range end
   * @param step increment step, either positive or negative
   */
  def apply(from: Int, until: Int, step: Int = 1)(implicit s: Scheduler): Observable[Int] = {
    require(step != 0, "step must be a number different from zero")

    Observable.create { o =>
      def scheduleLoop(from: Int, until: Int, step: Int): Unit =
        s.execute(new Runnable {
          private[this] def isInRange(x: Int): Boolean = {
            (step > 0 && x < until) || (step < 0 && x > until)
          }

          @tailrec
          def loop(from: Int, until: Int, step: Int): Unit =
            if (isInRange(from)) {
              o.onNext(from) match {
                case sync if sync.isCompleted =>
                  if (sync == Continue || sync.value.get == Continue.IsSuccess)
                    loop(from + step, until, step)
                case async =>
                  if (isInRange(from + step))
                    async.onSuccess {
                      case Continue =>
                        scheduleLoop(from + step, until, step)
                    }
                  else
                    o.onComplete()
              }
            }
            else
              o.onComplete()

          def run(): Unit = {
            loop(from, until, step)
          }
        })

      scheduleLoop(from, until, step)
    }
  }
}
