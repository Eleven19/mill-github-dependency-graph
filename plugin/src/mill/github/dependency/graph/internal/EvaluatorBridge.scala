package mill.github.dependency.graph.internal

import mill.api.Evaluator
import mill.api.SelectMode
import mill.api.daemon.internal.EvaluatorApi
import mill.javalib.JavaModule
import mill.resolve.Resolve

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

  /** Resolves Mill selectors such as `__.test` to task segments.
    *
    * `Resolve.Segments` needs a `RootModule0`, and `Evaluator.rootModule` is
    * `private[mill]`, which is why this lives here rather than in the plugin
    * package proper.
    *
    * `Result#get` throws with the resolver's own message, so a selector that
    * matches nothing fails the command rather than silently selecting no
    * modules.
    */
  def resolveSegments(
      ev: Evaluator,
      selectors: Seq[String]
  ): Seq[List[String]] =
    Resolve.Segments
      .resolve(
        rootModule = ev.rootModule,
        scriptArgs = selectors,
        selectMode = SelectMode.Multi,
        scriptModuleResolver = _ => Nil
      )
      .get
      .map(_.parts)
}
