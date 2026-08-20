package io.eleven19.mill.github.dependency.graph

import coursier.graph.DependencyTree
import mill._
import mill.api.Evaluator
import mill.github.dependency.graph.internal.EvaluatorBridge
import mill.javalib.JavaModule

/** Utils to help find all your modules and resolve their dependencies.
  */
object Resolver {

  /** Given an evaluator and your javaModules, use coursier to resolve all of
    * their dependencies into trees.
    *
    * @param evaluator Evaluator passed in from the command
    * @param javaModules All the JavaModules to resolve dependencies from
    * @param scope The scope every module is resolved at. `None` defers to each
    *   module's own [[GraphScopeModule.dependencyGraphScope]], or
    *   [[GraphScope.Runtime]] for modules that do not declare one.
    * @return A collection of ModuleTrees
    */
  private[graph] def resolveModuleTrees(
      evaluator: Evaluator,
      javaModules: Seq[JavaModule],
      scope: Option[GraphScope],
      includeModuleDeps: Option[Boolean] = None
  ): Seq[ModuleTrees] = {
    val tasks = javaModules.map { javaModule =>
      // Built outside the `Task.Anon` below on purpose: Mill's task macro
      // rejects `someTask()` when the task is chosen by a `val` declared
      // inside the block.
      val scopeTask: Task[GraphScope] = scope match {
        case Some(passed) => Task.Anon(passed)
        case None =>
          javaModule match {
            case settings: DependencyGraphModule =>
              settings.dependencyGraphScope
            case _ => Task.Anon(GraphScope.Runtime)
          }
      }

      val includeModuleDepsTask: Task[Boolean] = includeModuleDeps match {
        case Some(passed) => Task.Anon(passed)
        case None =>
          javaModule match {
            case settings: DependencyGraphModule => settings.includeModuleDeps
            case _                               => Task.Anon(true)
          }
      }

      // Every module reached through internal `moduleDeps`, transitively.
      // Their own dependencies are what a consumer of this module picks up
      // and what its manifest has to report.
      val moduleDeps = javaModule.recursiveModuleDeps
      val moduleDepAllMvnDeps = Task.sequence(moduleDeps.map(_.allMvnDeps))
      val moduleDepRunMvnDeps = Task.sequence(moduleDeps.map(_.runMvnDeps))
      val moduleDepCompileMvnDeps =
        Task.sequence(moduleDeps.map(_.compileMvnDeps))

      Task.Anon {
        val bindDep = javaModule.bindDependency()
        def bound(deps: Seq[mill.javalib.Dep]) =
          deps.map(bindDep).map(_.dep)

        // The opt-out is expressed by handing the scope table empty module-dep
        // lists, so the table itself never branches on it.
        val withModuleDeps = includeModuleDepsTask()
        def fromModuleDeps(deps: Seq[Seq[mill.javalib.Dep]]) =
          if (withModuleDeps) bound(deps.flatten) else Nil

        val roots = ScopedRoots(
          scope = scopeTask(),
          synthetic = javaModule.coursierDependencyTask(),
          allMvnDeps = bound(javaModule.allMvnDeps()),
          runMvnDeps = bound(javaModule.runMvnDeps()),
          compileMvnDeps = bound(javaModule.compileMvnDeps()),
          moduleDepAllMvnDeps = fromModuleDeps(moduleDepAllMvnDeps()),
          moduleDepRunMvnDeps = fromModuleDeps(moduleDepRunMvnDeps()),
          moduleDepCompileMvnDeps = fromModuleDeps(moduleDepCompileMvnDeps()),
          isTestModule = javaModule.isInstanceOf[mill.javalib.TestModule]
        )

        // `millResolver` rather than `defaultResolver`: the module's synthetic
        // coursier project is served by an internal repository that only
        // `millResolver` includes.
        val resolution =
          javaModule.millResolver().resolution(roots.resolution)

        // The roots are children of the synthetic module dependency, not the
        // resolution's own root, so they are passed explicitly to keep the
        // direct/indirect split the manifest reports. `DependencyTree` looks
        // their versions up in the resolution by module, so a root whose
        // version came from `depManagement` or a BOM still reports the version
        // resolution settled on.
        // `DependencyTree` preserves root order, so zipping the facts back on
        // is safe and keeps them attached to the tree they describe.
        val trees = DependencyTree(
          resolution = resolution,
          roots = roots.roots.map(_._1)
        )

        ModuleTrees(javaModule, trees.zip(roots.roots.map(_._2)))
      }
    }

    val results = EvaluatorBridge.executeApi(evaluator, tasks)
    results.values.get
  }

  private[graph] def computeModules(ev: Evaluator): Seq[JavaModule] =
    EvaluatorBridge.computeModules(ev)
}
