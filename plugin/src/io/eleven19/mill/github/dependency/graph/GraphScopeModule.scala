package io.eleven19.mill.github.dependency.graph

import mill._
import mill.javalib.JavaModule

/** Mix into a `JavaModule` to give it its own dependency-graph scope.
  *
  * {{{
  * object server extends ScalaModule with GraphScopeModule {
  *   override def dependencyGraphScope = Task { GraphScope.All }
  * }
  * }}}
  *
  * A `--scope` passed to `Graph/generate` or `Graph/submit` overrides this.
  * Modules that do not mix this in resolve at [[GraphScope.Runtime]].
  */
trait GraphScopeModule extends JavaModule {

  /** The scope this module's dependency graph is resolved at. */
  def dependencyGraphScope: T[GraphScope] = Task { GraphScope.Runtime }
}
