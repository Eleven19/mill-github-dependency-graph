package io.eleven19.mill.github.dependency.graph

import coursier.core.{Configuration, Dependency, VariantSelector}

/** The dependencies a scope resolves, and the dependencies its trees are
  * rooted at.
  *
  * Both halves live in one function on purpose. Issue #12 was the two
  * disagreeing: the resolution was rooted at `compile` while the tree roots
  * carried no configuration at all, which coursier defaults to
  * `default(runtime)`. The tree then spanned nodes the resolution had never
  * reconciled, and `DependencyTree.Node.dependency` calls `sys.error` on the
  * first one it reaches, which aborted the whole run.
  *
  * Every tree root must be one of the dependencies the resolution walked.
  * Computing both from one `scope` in one place is what holds that.
  *
  * This is a plain function rather than a `Task` because Mill's task macro
  * rejects `someTask()` when the task is chosen by a `val` declared inside
  * the enclosing `Task` block, which is exactly how the scope is chosen.
  */
private[graph] object ScopedRoots {

  /** @param resolution what the coursier resolution is rooted at
    * @param trees the module's own dependencies, reported as direct
    * @param indirectTrees dependencies reached through internal `moduleDeps`,
    *   reported as indirect — the module never declared them, but a consumer
    *   of the module gets them on the classpath
    */
  final case class Roots(
      resolution: Seq[Dependency],
      trees: Seq[Dependency],
      indirectTrees: Seq[Dependency] = Nil
  )

  private val compile =
    VariantSelector.ConfigurationBased(Configuration.compile)
  private val runtime =
    VariantSelector.ConfigurationBased(Configuration.runtime)
  private val provided =
    VariantSelector.ConfigurationBased(Configuration.provided)

  /** Variant-attribute dependencies select by Gradle attributes rather than by
    * configuration, and `coursierProject` leaves those alone, so we do too.
    */
  private def stamp(
      deps: Seq[Dependency],
      selector: VariantSelector
  ): Seq[Dependency] =
    deps.map(dep =>
      if (dep.isVariantAttributesBased) dep
      else dep.withVariantSelector(selector)
    )

  /** @param synthetic `JavaModule.coursierDependencyTask()`, the module's own
    *   coursier dependency. It carries the module's `depManagement` and its
    *   `bomMvnDeps`, which is why resolution is rooted there rather than at a
    *   loose list of dependencies.
    */
  /** @param moduleDepAllMvnDeps the `allMvnDeps` of every module reached
    *   through internal `moduleDeps`, already bound. Same for the other two
    *   `moduleDep*` lists. Empty when module deps are excluded, which is how
    *   the opt-out is expressed — the scope table does not branch on it.
    */
  def apply(
      scope: GraphScope,
      synthetic: Dependency,
      allMvnDeps: Seq[Dependency],
      runMvnDeps: Seq[Dependency],
      compileMvnDeps: Seq[Dependency],
      moduleDepAllMvnDeps: Seq[Dependency] = Nil,
      moduleDepRunMvnDeps: Seq[Dependency] = Nil,
      moduleDepCompileMvnDeps: Seq[Dependency] = Nil
  ): Roots =
    scope match {
      case GraphScope.Compile =>
        Roots(
          resolution = Seq(synthetic.withVariantSelector(compile)),
          trees = stamp(allMvnDeps, compile),
          indirectTrees = stamp(moduleDepAllMvnDeps, compile)
        )

      case GraphScope.Runtime =>
        Roots(
          resolution = Seq(synthetic.withVariantSelector(runtime)),
          trees = stamp(allMvnDeps ++ runMvnDeps, runtime),
          indirectTrees =
            stamp(moduleDepAllMvnDeps ++ moduleDepRunMvnDeps, runtime)
        )

      case GraphScope.All =>
        // Coursier accepts the same module twice as a root at two
        // configurations and reconciles both into one set, so `all` costs one
        // resolution rather than two.
        //
        // `compileMvnDeps` are stamped `compile`, not `provided`:
        // `coursierProject` files them as
        // `(provided, dep.withConfiguration(compile))`, so the variant is
        // provided but the dependency itself is compile.
        Roots(
          resolution = Seq(
            synthetic.withVariantSelector(runtime),
            synthetic.withVariantSelector(provided)
          ),
          trees = stamp(allMvnDeps ++ runMvnDeps, runtime) ++
            stamp(compileMvnDeps, compile)
        )
    }
}
