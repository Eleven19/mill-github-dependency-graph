package io.eleven19.mill.github.dependency.graph

import io.eleven19.github.dependency.graph.domain
import io.eleven19.github.dependency.graph.report.{GraphSummary, HtmlReport}
import mill._
import mill.api.BuildCtx
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
      excludeModules: Seq[String] = Nil,
      output: Option[String] = None
  ): Task.Command[Unit] =
    Task.Command(exclusive = true) {
      val manifests = generate(
        ev,
        scope = scope,
        modules = modules,
        excludeModules = excludeModules,
        output = output
      )()
      val snapshot = Github.snapshot(manifests)
      Github.submit(snapshot)
    }

  /** @param scope `compile`, `runtime` or `all`. Omitted, each module uses its
    *   own `GraphScopeModule.dependencyGraphScope`, defaulting to `runtime`.
    * @param modules Mill selectors naming the modules to cover. Omitted, every
    *   module is covered.
    * @param excludeModules Mill selectors naming modules to leave out, applied
    *   after `modules`.
    * @param output Where to also write the manifests, as the same JSON `mill
    *   show` prints. Omitted, nothing extra is written. Mill's own
    *   `generate.json` metadata file is written either way; this adds a copy,
    *   it does not relocate it.
    */
  def generate(
      ev: Evaluator,
      scope: Option[String] = None,
      modules: Seq[String] = Nil,
      excludeModules: Seq[String] = Nil,
      output: Option[String] = None
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
        ModuleSelection.owningModules(
          discovered,
          EvaluatorBridge.resolveSegments(ev, modules)
        )
      }
      val exclude =
        if (excludeModules.isEmpty) Set.empty[List[String]]
        else
          ModuleSelection.owningModules(
            discovered,
            EvaluatorBridge.resolveSegments(ev, excludeModules)
          )

      val selected = ModuleSelection.select(discovered, include, exclude)

      // Never let a filter, in either direction, empty the graph out from
      // under the caller. `--modules` naming no JavaModule and
      // `--exclude-modules` naming every module both land here: `include`
      // or `exclude` end up excluding everything, and `select` honours that
      // faithfully. GitHub's Dependency Submission API keys the snapshot on
      // a correlator that stays stable per workflow/job, so an empty
      // submission is not ignored — it REPLACES the repository's previously
      // submitted dependency graph with nothing. Only an `info` log line
      // would otherwise signal it.
      if (selected.isEmpty && discovered.nonEmpty)
        throw new IllegalArgumentException(
          "The selectors given left no modules to report. " +
            s"--modules ${
                if (modules.isEmpty) "(none)" else modules.mkString(", ")
              }; " +
            s"--exclude-modules ${
                if (excludeModules.isEmpty) "(none)"
                else excludeModules.mkString(", ")
              }."
        )

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

      val manifests =
        moduleTrees.map(mt => (mt.module.toString(), mt.toManifest())).toMap

      output.foreach { path =>
        val destination = os.Path(path, BuildCtx.workspaceRoot)
        if (os.isDir(destination))
          throw new IllegalArgumentException(
            s"--output names an existing directory: $destination. " +
              "Give it a file path instead."
          )
        os.write.over(
          destination,
          upickle.default.write(manifests, indent = 2),
          createFolders = true
        )
        Task.log.info(s"wrote $destination")
      }

      manifests
    }

  /** Renders the dependency graph as a self-contained HTML report.
    *
    * Reuses `generate`, so the scope and selection flags behave identically
    * and the two commands cannot disagree about what the graph contains.
    *
    * @return the absolute path written
    */
  def report(
      ev: Evaluator,
      scope: Option[String] = None,
      modules: Seq[String] = Nil,
      excludeModules: Seq[String] = Nil,
      output: Option[String] = None
  ): Task.Command[String] =
    Task.Command(exclusive = true) {
      val manifests = generate(
        ev,
        scope = scope,
        modules = modules,
        excludeModules = excludeModules
      )()

      val destination = output match {
        case Some(path) =>
          val chosen = os.Path(path, BuildCtx.workspaceRoot)
          if (os.isDir(chosen))
            throw new IllegalArgumentException(
              s"--output names an existing directory: $chosen. " +
                "Give it a file path instead."
            )
          chosen
        case None => Task.dest / "graph-report.html"
      }

      // Mirrors `generate`'s own "submitting N of M modules" wording, so the
      // report's header states the same fact rather than a plain module
      // count that omits what a selector left out.
      val discoveredCount = Resolver.computeModules(ev).size
      val selection =
        if (manifests.size == discoveredCount)
          s"all $discoveredCount modules"
        else
          s"${manifests.size} of $discoveredCount modules"

      val html = HtmlReport.render(
        summary = GraphSummary.from(manifests),
        scope = scope.getOrElse("per-module"),
        selection = selection,
        manifests = manifests
      )

      os.write.over(destination, html, createFolders = true)
      Task.log.info(s"wrote $destination")
      destination.toString
    }
}
