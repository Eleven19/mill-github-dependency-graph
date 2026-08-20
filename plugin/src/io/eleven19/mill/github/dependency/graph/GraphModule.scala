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
      output: Option[String] = None,
      noModuleDeps: mainargs.Flag = mainargs.Flag()
  ): Task.Command[Unit] =
    Task.Command(exclusive = true) {
      val manifests = generate(
        ev,
        scope = scope,
        modules = modules,
        excludeModules = excludeModules,
        output = output,
        noModuleDeps = noModuleDeps
      )()
      val snapshot = Github.snapshot(manifests)
      Github.submit(snapshot)
    }

  /** Resolves an `--output` value to an absolute path, rejecting a directory.
    *
    * Shared by all three commands so they cannot disagree, and called *before*
    * any resolution work: dependency resolution takes minutes and hits the
    * network, and failing an argument check afterwards wastes a whole CI job
    * for a mistake that was checkable in microseconds.
    */
  private def resolveOutput(path: String): os.Path = {
    val destination = os.Path(path, BuildCtx.workspaceRoot)
    if (os.isDir(destination))
      throw new IllegalArgumentException(
        s"--output names an existing directory: $destination. " +
          "Give it a file path instead."
      )
    destination
  }

  /** @param scope `compile`, `runtime` or `all`. Omitted, each module uses its
    *   own `GraphScopeModule.dependencyGraphScope`, defaulting to `runtime`.
    * @param modules Mill selectors naming the modules to cover. Omitted, every
    *   module is covered.
    * @param excludeModules Mill selectors naming modules to leave out, applied
    *   after `modules`.
    * @param noModuleDeps Leave out dependencies reached through internal
    *   `moduleDeps`. Off by default: omitting them understates what a module
    *   depends on. Pass it when the repetition costs more than the accuracy.
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
      output: Option[String] = None,
      noModuleDeps: mainargs.Flag = mainargs.Flag()
  ): Task.Command[Map[String, domain.Manifest]] =
    Task.Command(exclusive = true) {
      // Both argument checks run before any work. See `resolveOutput`.
      val destination = output.map(resolveOutput)

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
      // so the count has to be stated. "covering", not "submitting": this
      // runs inside `generate`, which `report` also delegates to, and neither
      // of those two commands submits anything.
      if (selected.size != discovered.size)
        Task.log.info(
          s"covering ${selected.size} of ${discovered.size} modules, " +
            s"${discovered.size - selected.size} excluded by selector"
        )

      // The flag can only turn inclusion off, never force it on over a
      // module that opted out: it exists as an escape hatch for payload size,
      // and there is no reason to overrule a build that asked for less.
      val moduleTrees = Resolver.resolveModuleTrees(
        ev,
        selected,
        parsedScope,
        includeModuleDeps = Option.when(noModuleDeps.value)(false)
      )

      val manifests =
        moduleTrees.map(mt => (mt.module.toString(), mt.toManifest())).toMap

      destination.foreach { path =>
        os.write.over(
          path,
          upickle.default.write(manifests, indent = 2),
          createFolders = true
        )
        Task.log.info(s"wrote $path")
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
      output: Option[String] = None,
      noModuleDeps: mainargs.Flag = mainargs.Flag()
  ): Task.Command[String] =
    Task.Command(exclusive = true) {
      // Before `generate`, which resolves every module: an existing-directory
      // `--output` should fail in microseconds, not after minutes of work.
      val chosen = output.map(resolveOutput)

      val manifests = generate(
        ev,
        scope = scope,
        modules = modules,
        excludeModules = excludeModules,
        noModuleDeps = noModuleDeps
      )()

      val destination = chosen.getOrElse(Task.dest / "graph-report.html")

      // Mirrors `generate`'s own "covering N of M modules" wording, so the
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
