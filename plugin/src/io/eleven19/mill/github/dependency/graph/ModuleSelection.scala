package io.eleven19.mill.github.dependency.graph

import mill.javalib.JavaModule

/** Narrows the set of modules whose graphs get submitted.
  *
  * Mill's resolver answers a selector with *task* segments, so `__.test`
  * comes back as `app.test.compile` and the like. A resolved selector names
  * the module that is its longest known-module prefix, which is what
  * distinguishes `app.test` from its parent `app`.
  */
private[graph] object ModuleSelection {

  private def segmentsOf(module: JavaModule): List[String] =
    module.moduleSegments.parts

  /** The modules named by a set of resolved task segments. */
  def owningModules(
      modules: Seq[JavaModule],
      resolved: Seq[List[String]]
  ): Set[List[String]] = {
    val known = modules.map(segmentsOf).toSet
    // `inits` yields prefixes longest-first, so the first hit is the longest.
    resolved.flatMap(_.inits.find(known.contains)).toSet
  }

  /** @param include `None` keeps every module; `Some` narrows to that set.
    * @param exclude Always subtracted, so an excluded module stays out even
    *   when it was named by `include`.
    */
  def select(
      modules: Seq[JavaModule],
      include: Option[Set[List[String]]],
      exclude: Set[List[String]]
  ): Seq[JavaModule] =
    modules.filter { module =>
      val segments = segmentsOf(module)
      include.forall(_.contains(segments)) && !exclude.contains(segments)
    }
}
