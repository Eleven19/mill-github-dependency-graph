package io.eleven19.mill.github.dependency.graph

import coursier.graph.DependencyTree
import mill._
import mill.api.Evaluator
import mill.github.dependency.graph.internal.EvaluatorBridge
import mill.javalib.JavaModule
import mill.javalib.Lib

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
        val deps = javaModule.allMvnDeps()
        val bindDep = javaModule.bindDependency()
        val boundDeps = deps.map(bindDep)
        val repos = javaModule.repositoriesTask()
        val mapDeps = javaModule.mapDependencies()
        val custom = javaModule.resolutionCustomizer()

        Lib
          .resolveDependenciesMetadataSafe(
            repositories = repos,
            deps = boundDeps,
            mapDependencies = Some(mapDeps),
            customizer = custom,
            ctx = Some(Task.ctx())
          )
          .map { resolution =>
            val trees =
              DependencyTree(
                resolution = resolution,
                roots = boundDeps.map(_.dep).toSeq
              )

            ModuleTrees(
              javaModule,
              trees
            )
          }
      }
    }

    val results = EvaluatorBridge.executeApi(evaluator, tasks)
    results.values.get
  }

  private[graph] def computeModules(ev: Evaluator): Seq[JavaModule] =
    EvaluatorBridge.computeModules(ev)
}
