package io.eleven19.mill.github.dependency.graph

/** Which Maven scope a module's dependency graph is resolved at.
  *
  * `Runtime` contains `Compile` in Mill's scope map, so the three values are
  * ordered by breadth: `Compile` is the narrowest and `All` the widest.
  * `test` is deliberately absent — Mill maps it to the same set as `runtime`,
  * so it would name a setting that already has a name.
  */
sealed abstract class GraphScope(val name: String) {
  override def toString: String = name
}

object GraphScope {

  /** What the module compiles against. */
  case object Compile extends GraphScope("compile")

  /** What the module runs against. Contains [[Compile]]. */
  case object Runtime extends GraphScope("runtime")

  /** [[Runtime]] plus provided-scope dependencies, which is where
    * `compileMvnDeps` live.
    */
  case object All extends GraphScope("all")

  val values: Seq[GraphScope] = Seq(Compile, Runtime, All)

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
