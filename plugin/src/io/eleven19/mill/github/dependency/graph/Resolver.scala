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
      scope: Option[GraphScope]
  ): Seq[ModuleTrees] = {
    val tasks = javaModules.map { javaModule =>
      // Built outside the `Task.Anon` below on purpose: Mill's task macro
      // rejects `someTask()` when the task is chosen by a `val` declared
      // inside the block.
      val scopeTask: Task[GraphScope] = scope match {
        case Some(passed) => Task.Anon(passed)
        case None =>
          javaModule match {
            case scoped: GraphScopeModule => scoped.dependencyGraphScope
            case _                        => Task.Anon(GraphScope.Runtime)
          }
      }

      Task.Anon {
        val bindDep = javaModule.bindDependency()
        def bound(deps: Seq[mill.javalib.Dep]) =
          deps.map(bindDep).map(_.dep).toSeq

        val roots = ScopedRoots(
          scope = scopeTask(),
          synthetic = javaModule.coursierDependencyTask(),
          allMvnDeps = bound(javaModule.allMvnDeps()),
          runMvnDeps = bound(javaModule.runMvnDeps()),
          compileMvnDeps = bound(javaModule.compileMvnDeps())
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
        val trees =
          DependencyTree(resolution = resolution, roots = roots.trees)

        ModuleTrees(javaModule, trees)
      }
    }

    val results = EvaluatorBridge.executeApi(evaluator, tasks)
    results.values.get
  }

  private[graph] def computeModules(ev: Evaluator): Seq[JavaModule] =
    EvaluatorBridge.computeModules(ev)
}
