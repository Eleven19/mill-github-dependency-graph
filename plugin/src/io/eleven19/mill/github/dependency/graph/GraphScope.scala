package io.eleven19.mill.github.dependency.graph

/** Which Maven scope a module's dependency graph is resolved at.
  *
  * `Runtime` contains `Compile` in Mill's scope map, so the three values are
  * ordered by breadth: `Compile` is the narrowest and `All` the widest.
  * `test` is deliberately absent — Mill maps it to the same set as `runtime`,
  * so it would name a setting that already has a name.
  *
  * What each scope resolves, in terms of `JavaModule.coursierDependencyTask()`
  * (`synthetic`, which carries the module's `depManagement` and `bomMvnDeps`,
  * which is why resolution is rooted there rather than at a loose dependency
  * list):
  *
  *   - `Compile`: resolves `synthetic@compile`; tree roots are
  *     `allMvnDeps@compile`.
  *   - `Runtime`: resolves `synthetic@runtime`; tree roots are
  *     `(allMvnDeps ++ runMvnDeps)@runtime`. `runMvnDeps` are roots only from
  *     here on — `coursierProject` files them under the runtime
  *     configuration alone, so at `Compile` they are not in the resolution
  *     and must not be roots either.
  *   - `All`: resolves `synthetic` at both `runtime` and `provided` (coursier
  *     accepts the same module twice as a root at two configurations and
  *     reconciles both into one set, so this costs one resolution, not two);
  *     tree roots add `compileMvnDeps@compile` — stamped `compile`, not
  *     `provided`, because `coursierProject` files them as
  *     `(provided, dep.withConfiguration(compile))`: the *variant* is
  *     provided, but the dependency itself is compile, and root stamping has
  *     to match the dependency.
  *
  * The invariant every scope must hold: every tree root must be one of the
  * dependencies the resolution walked. Issue #12 was exactly this breaking —
  * the resolution was rooted at `compile` while the tree roots carried no
  * configuration, which coursier defaults to `default(runtime)`, so the tree
  * spanned nodes the resolution had never reconciled and
  * `DependencyTree.Node.dependency` aborted the run on the first one. See
  * [[ScopedRoots]], which computes both halves from one scope in one place so
  * they cannot drift apart as scopes are added.
  */
enum GraphScope(val name: String) {

  /** What the module compiles against. */
  case Compile extends GraphScope("compile")

  /** What the module runs against. Contains [[Compile]]. */
  case Runtime extends GraphScope("runtime")

  /** [[Runtime]] plus provided-scope dependencies, which is where
    * `compileMvnDeps` live.
    */
  case All extends GraphScope("all")

  override def toString: String = name
}

object GraphScope {

  // `values` itself -- an `Array[GraphScope]` in breadth order -- is
  // compiler-derived from the enum's cases, so a fourth case is automatically
  // included and nothing here goes stale. An `Array`'s `==` is reference
  // equality though, so a call site that compares two derivations of
  // `values` needs `.toSeq` first; see GraphScopeTests and ResolverTests.

  /** Parses a scope, naming every valid value when it cannot.
    *
    * A rejected value has to say what the valid ones are. The alternative is
    * a typo that resolves to nothing and submits an empty graph, which reads
    * in the GitHub UI exactly like a project with no dependencies.
    */
  def fromString(value: String): Either[String, GraphScope] =
    values
      .find(_.name == value)
      .toRight(
        s"Unknown scope '$value'. Expected one of: " +
          values.map(_.name).mkString(", ") + "."
      )

  /** Lets `--scope` be typed rather than validated by hand.
    *
    * It lives here, in the companion, so it is found without an import
    * wherever `GraphScope` appears in a command signature. With it, mainargs
    * reports a bad value as an argument error and lists the valid ones in
    * `--help`; without it the command body had to throw, which reached the
    * user as a Java stack trace.
    */
  implicit val tokensReader: mainargs.TokensReader.Simple[GraphScope] =
    new mainargs.TokensReader.Simple[GraphScope] {
      def shortName: String = "scope"
      def read(strs: Seq[String]): Either[String, GraphScope] =
        fromString(strs.last)
    }

  /** `dependencyGraphScope` is a `Task`, and Mill caches task values as JSON. */
  implicit val rw: upickle.default.ReadWriter[GraphScope] =
    upickle.default
      .readwriter[String]
      .bimap[GraphScope](
        _.name,
        value =>
          fromString(value).fold(
            message => throw new IllegalArgumentException(message),
            identity
          )
      )
}
