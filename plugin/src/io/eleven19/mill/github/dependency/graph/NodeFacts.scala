package io.eleven19.mill.github.dependency.graph

import io.eleven19.github.dependency.graph.domain.DependencyRelationship
import io.eleven19.github.dependency.graph.domain.DependencyScope

/** What a dependency-tree root says about the nodes reached from it.
  *
  * Modelling this as one value rather than as parallel lists of roots is what
  * keeps the root-building from growing combinatorially. Roots differ along
  * two axes already — declared here or reached through a `moduleDeps` edge,
  * and production or development — which as separate lists would be four, and
  * a third axis would be eight. As a record it is two fields, and a third
  * would be a third field.
  */
final case class NodeFacts(
    relationship: DependencyRelationship,
    scope: DependencyScope
) {

  /** Everything below a root is indirect, whatever the root itself was. */
  def asIndirect: NodeFacts =
    copy(relationship = DependencyRelationship.indirect)

  /** Combine two claims about the same coordinate.
    *
    * A coordinate is often reachable more than one way: declared directly and
    * also pulled in through an internal `moduleDeps` edge, or present both at
    * runtime and as a `compileMvnDeps`. This decides what the node ends up
    * saying, and it is deliberately commutative and idempotent — walk order
    * stops being load-bearing, which it previously was, unwritten, in the
    * order the root lists happened to be traversed.
    *
    * Both axes widen rather than narrow:
    *
    *   - `direct` beats `indirect`, because the module really does declare it.
    *   - `runtime` beats `development`, because a library on the runtime
    *     classpath is a production dependency even if a test pulls it too.
    *     Understating what ships is the failure worth avoiding; overstating
    *     it only costs a little noise.
    */
  def merge(other: NodeFacts): NodeFacts =
    NodeFacts(
      relationship =
        if (
          relationship == DependencyRelationship.direct ||
          other.relationship == DependencyRelationship.direct
        ) DependencyRelationship.direct
        else DependencyRelationship.indirect,
      scope =
        if (
          scope == DependencyScope.runtime ||
          other.scope == DependencyScope.runtime
        ) DependencyScope.runtime
        else DependencyScope.development
    )
}

object NodeFacts {

  /** A dependency the module declares itself. */
  def direct(scope: DependencyScope): NodeFacts =
    NodeFacts(DependencyRelationship.direct, scope)

  /** A dependency the module picks up without declaring it. */
  def indirect(scope: DependencyScope): NodeFacts =
    NodeFacts(DependencyRelationship.indirect, scope)
}
