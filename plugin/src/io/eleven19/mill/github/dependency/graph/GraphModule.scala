package io.eleven19.mill.github.dependency.graph

import io.eleven19.github.dependency.graph.domain
import mill._
import mill.api.ExternalModule
import mill.api.Evaluator
import mill.github.dependency.graph.internal.EvaluatorBridge

trait GraphModule extends ExternalModule {

  import Writers._

  // Both `submit` and `generate` must be exclusive commands because they
  // access `Evaluator.rootModule` and `Evaluator.executeApi` via
  // `EvaluatorBridge`. In Mill 1.x, non-exclusive Task.Command bodies receive
  // an `EvaluatorProxy` that throws "No evaluator available here; Evaluator
  // is only available in exclusive commands" on those accesses.
  def submit(
      ev: Evaluator,
      scope: Option[String] = None,
      modules: Seq[String] = Nil,
      excludeModules: Seq[String] = Nil
  ): Task.Command[Unit] =
    Task.Command(exclusive = true) {
      val manifests = generate(ev, scope, modules, excludeModules)()
      val snapshot = Github.snapshot(manifests)
      Github.submit(snapshot)
    }

  /** @param scope `compile`, `runtime` or `all`. Omitted, each module uses its
    *   own `GraphScopeModule.dependencyGraphScope`, defaulting to `runtime`.
    * @param modules Mill selectors naming the modules to cover. Omitted, every
    *   module is covered.
    * @param excludeModules Mill selectors naming modules to leave out, applied
    *   after `modules`.
    */
  def generate(
      ev: Evaluator,
      scope: Option[String] = None,
      modules: Seq[String] = Nil,
      excludeModules: Seq[String] = Nil
  ): Task.Command[Map[String, domain.Manifest]] =
    Task.Command(exclusive = true) {
      val parsedScope = scope.map { value =>
        GraphScope.fromString(value) match {
          case Right(parsed) => parsed
          case Left(message) => throw new IllegalArgumentException(message)
        }
      }

      val discovered = Resolver.computeModules(ev)

      val include = Option.when(modules.nonEmpty) {
        val owning = ModuleSelection.owningModules(
          discovered,
          EvaluatorBridge.resolveSegments(ev, modules)
        )

        // `owningModules` can legitimately come back empty: the selectors
        // resolved to real Mill tasks (`resolveSegments` already guards
        // against selectors that match nothing), but none of those tasks
        // belong to a `JavaModule`. Left unchecked, `include` becomes
        // `Some(Set.empty)`, `ModuleSelection.select` honours it faithfully,
        // and every module is filtered out. The command would then submit an
        // empty graph, which reads in the GitHub UI exactly like a project
        // with no dependencies.
        if (owning.isEmpty)
          throw new IllegalArgumentException(
            s"--modules ${modules.mkString(", ")} named no JavaModule."
          )

        owning
      }
      val exclude =
        if (excludeModules.isEmpty) Set.empty[List[String]]
        else
          ModuleSelection.owningModules(
            discovered,
            EvaluatorBridge.resolveSegments(ev, excludeModules)
          )

      val selected = ModuleSelection.select(discovered, include, exclude)

      // Never let a filter shrink the graph quietly. A short manifest list
      // reads in the GitHub UI exactly like a project with few dependencies,
      // so the count has to be stated.
      if (selected.size != discovered.size)
        Task.log.info(
          s"submitting ${selected.size} of ${discovered.size} modules, " +
            s"${discovered.size - selected.size} excluded by selector"
        )

      val moduleTrees =
        Resolver.resolveModuleTrees(ev, selected, parsedScope)

      moduleTrees.map(mt => (mt.module.toString(), mt.toManifest())).toMap
    }
}
