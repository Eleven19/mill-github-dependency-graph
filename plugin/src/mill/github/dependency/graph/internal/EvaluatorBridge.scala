package mill.github.dependency.graph.internal

import mill.api.Evaluator
import mill.api.daemon.internal.EvaluatorApi
import mill.javalib.JavaModule

/** Bridge to access Mill-private APIs from external plugin code.
  * This must live in a mill.* package to access private[mill] methods.
  */
object EvaluatorBridge {

  def computeModules(ev: Evaluator): Seq[JavaModule] =
    ev.rootModule.moduleInternal.modules.collect { case j: JavaModule => j }

  def executeApi[T](
      ev: Evaluator,
      tasks: Seq[mill.api.Task[T]]
  ): EvaluatorApi.Result[T] =
    ev.executeApi(tasks)

  def evaluatorTokenReader: mainargs.TokensReader[Evaluator] =
    new mill.util.EvaluatorTokenReader()
}
