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
    * @return A collection of ModuleTrees
    */
  private[graph] def resolveModuleTrees(
      evaluator: Evaluator,
      javaModules: Seq[JavaModule]
  ): Seq[ModuleTrees] = {
    val tasks = javaModules.map { javaModule =>
      Task.Anon {
        val bindDep = javaModule.bindDependency()

        // The direct dependencies of the module, which become the roots of the
        // trees and therefore the "direct" relationships GitHub is shown. These
        // may carry no version of their own when the module gets it from
        // `depManagement` or a BOM.
        val roots = javaModule.allMvnDeps().map(bindDep).map(_.dep).toSeq

        // Resolve the module's own synthetic coursier dependency rather than
        // its list of dependencies.
        //
        // `JavaModule.coursierProject` carries the module's `depManagement`
        // (as the project's `dependencyManagement`) and its `bomMvnDeps` (as
        // `import`-scoped dependencies). Dependency management applies to the
        // project being resolved, not to a loose set of root dependencies, so
        // it only takes effect when the module itself is the root. That
        // project is served by an internal repository which `millResolver`
        // includes and `defaultResolver` does not, hence `millResolver` here.
        //
        // Resolving the loose `allMvnDeps()` against `repositoriesTask()`
        // instead, as this used to, reaches coursier with no version at all for
        // any managed coordinate and fails with "No version available in (,)".
        val resolution = javaModule
          .millResolver()
          .resolution(Seq(javaModule.coursierDependencyTask()))

        // The roots are children of the synthetic module dependency, not the
        // resolution's own root, so they are passed explicitly to keep the
        // direct/indirect split the manifest reports. `DependencyTree` looks
        // their versions up in the resolution by module, so versionless roots
        // still report the version resolution settled on.
        val trees = DependencyTree(resolution = resolution, roots = roots)

        ModuleTrees(javaModule, trees)
      }
    }

    val results = EvaluatorBridge.executeApi(evaluator, tasks)
    results.values.get
  }

  private[graph] def computeModules(ev: Evaluator): Seq[JavaModule] =
    EvaluatorBridge.computeModules(ev)
}
