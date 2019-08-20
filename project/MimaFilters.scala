import com.typesafe.tools.mima.core.ProblemFilters.exclude
import com.typesafe.tools.mima.core._

object MimaFilters {
  lazy val changesFor_3_0_0: Seq[ProblemFilter] = Seq(
    // https://github.com/monix/monix/pull/971
    // Should not be a problem, but I'm not absolutely sure
    exclude[MissingTypesProblem]("monix.execution.exceptions.APIContractViolationException"),
    // Breaking changes for https://github.com/monix/monix/pull/960
    // Should only be a problem for Scala 2.11
    exclude[ReversedMissingMethodProblem]("monix.execution.Scheduler.features"),
    // Internals
    exclude[MissingClassProblem]("monix.eval.Task$DoOnFinish"),
    exclude[MissingClassProblem]("monix.eval.internal.TaskConnection$TrampolinedWithConn"),
    exclude[DirectMissingMethodProblem]("monix.eval.internal.TaskConnection.trampolineCallback"),
    exclude[FinalMethodProblem]("monix.execution.Callback#Base.run"),
    exclude[MissingClassProblem]("monix.eval.internal.TaskForkAndForget$"),
    exclude[MissingClassProblem]("monix.eval.internal.TaskForkAndForget")
  )
}
