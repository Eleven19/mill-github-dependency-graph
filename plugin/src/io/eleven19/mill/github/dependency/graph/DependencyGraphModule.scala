package io.eleven19.mill.github.dependency.graph

import mill._
import mill.javalib.JavaModule

/** Mix into a `JavaModule` to give it its own dependency-graph settings.
  *
  * {{{
  * object server extends ScalaModule with DependencyGraphModule {
  *   override def dependencyGraphScope = Task { GraphScope.All }
  * }
  * }}}
  *
  * Command-line flags override these: `--scope` beats
  * [[dependencyGraphScope]], and `--no-module-deps` beats
  * [[includeModuleDeps]]. Modules that do not mix this in get the defaults
  * below.
  */
trait DependencyGraphModule extends JavaModule {

  /** The scope this module's dependency graph is resolved at. */
  def dependencyGraphScope: T[GraphScope] = Task { GraphScope.Runtime }

  /** Whether this module's manifest reports the dependencies it picks up
    * through its internal `moduleDeps`.
    *
    * On by default, because leaving them out understates what the module
    * actually depends on: a consumer of this module gets its `moduleDeps`'
    * dependencies on the classpath too, so a manifest that omits them will
    * tell you a library is absent when it is present.
    *
    * Turn it off when the repetition costs more than the accuracy is worth.
    * Every module's dependencies are repeated in the manifest of every module
    * that depends on it, so a deep internal graph multiplies the submitted
    * payload.
    */
  def includeModuleDeps: T[Boolean] = Task { true }
}

@deprecated(
  "Use DependencyGraphModule, which also carries includeModuleDeps",
  "0.3.0"
)
trait GraphScopeModule extends DependencyGraphModule
