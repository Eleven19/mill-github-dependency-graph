package io.eleven19.mill.github.dependency.graph

import coursier.core.{Configuration, Dependency, VariantSelector}
import io.eleven19.github.dependency.graph.domain.DependencyScope

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
    * @param roots each tree root, paired with what the nodes reached from it
    *   inherit. One list rather than one per category: see [[NodeFacts]].
    */
  final case class Roots(
      resolution: Seq[Dependency],
      roots: Seq[(Dependency, NodeFacts)]
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
    * @param isTestModule whether this module is a `TestModule`. Everything in
    *   a test module's manifest is a development dependency: nothing it pulls
    *   in ships, whatever scope it was declared at.
    */
  def apply(
      scope: GraphScope,
      synthetic: Dependency,
      allMvnDeps: Seq[Dependency],
      runMvnDeps: Seq[Dependency],
      compileMvnDeps: Seq[Dependency],
      moduleDepAllMvnDeps: Seq[Dependency] = Nil,
      moduleDepRunMvnDeps: Seq[Dependency] = Nil,
      moduleDepCompileMvnDeps: Seq[Dependency] = Nil,
      isTestModule: Boolean = false
  ): Roots = {

    // What a module's ordinary dependencies count as. `compileMvnDeps` are
    // development wherever they appear — they are needed to build and never
    // ship — so they do not consult this.
    val ships =
      if (isTestModule) DependencyScope.development
      else DependencyScope.runtime

    val builds = DependencyScope.development

    def rootsOf(
        deps: Seq[Dependency],
        selector: VariantSelector,
        facts: NodeFacts
    ): Seq[(Dependency, NodeFacts)] =
      stamp(deps, selector).map(_ -> facts)

    scope match {
      case GraphScope.Compile =>
        Roots(
          resolution = Seq(synthetic.withVariantSelector(compile)),
          roots = rootsOf(allMvnDeps, compile, NodeFacts.direct(ships)) ++
            rootsOf(
              moduleDepAllMvnDeps,
              compile,
              NodeFacts.indirect(ships)
            )
        )

      case GraphScope.Runtime =>
        Roots(
          resolution = Seq(synthetic.withVariantSelector(runtime)),
          roots = rootsOf(
            allMvnDeps ++ runMvnDeps,
            runtime,
            NodeFacts.direct(ships)
          ) ++
            rootsOf(
              moduleDepAllMvnDeps ++ moduleDepRunMvnDeps,
              runtime,
              NodeFacts.indirect(ships)
            )
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
          roots = rootsOf(
            allMvnDeps ++ runMvnDeps,
            runtime,
            NodeFacts.direct(ships)
          ) ++
            rootsOf(compileMvnDeps, compile, NodeFacts.direct(builds)) ++
            rootsOf(
              moduleDepAllMvnDeps ++ moduleDepRunMvnDeps,
              runtime,
              NodeFacts.indirect(ships)
            ) ++
            rootsOf(
              moduleDepCompileMvnDeps,
              compile,
              NodeFacts.indirect(builds)
            )
        )
    }
  }
}
