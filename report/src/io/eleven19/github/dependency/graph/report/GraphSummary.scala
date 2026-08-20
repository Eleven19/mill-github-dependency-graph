package io.eleven19.github.dependency.graph.report

import io.eleven19.github.dependency.graph.domain.Manifest

/** One module's share of the graph. */
final case class ModuleSummary(name: String, direct: Int, indirect: Int) {
  def total: Int = direct + indirect
}

/** One `org:name` across every module that reports it.
  *
  * @param versions every version seen, sorted, deduplicated
  * @param modules the modules reporting it, sorted
  */
final case class CoordinateSummary(
    coordinate: String,
    versions: Seq[String],
    modules: Seq[String]
) {

  /** True when this coordinate appears at more than one version.
    *
    * This is a statement of fact, not a judgement. Coursier reconciles to a
    * single version within any one module, so a conflict is always across
    * modules — two modules that resolved the same library differently.
    */
  def hasConflict: Boolean = versions.sizeIs > 1
}

final case class GraphSummary(
    modules: Seq[ModuleSummary],
    coordinates: Seq[CoordinateSummary]
) {
  def moduleCount: Int = modules.size
  def nodeCount: Int = modules.map(_.total).sum
  def distinctCoordinateCount: Int = coordinates.size
  def conflicts: Seq[CoordinateSummary] = coordinates.filter(_.hasConflict)
}

object GraphSummary {

  /** Manifest keys are `org:name:version`. Neither an organization nor an
    * artifact name contains a colon, so the last one separates the version.
    */
  private def split(key: String): Option[(String, String)] = {
    val at = key.lastIndexOf(':')
    if (at <= 0 || at == key.length - 1) None
    else Some((key.substring(0, at), key.substring(at + 1)))
  }

  def from(manifests: Map[String, Manifest]): GraphSummary = {
    val modules = manifests.toSeq
      .map { case (name, manifest) =>
        val (direct, indirect) =
          manifest.resolved.values.partition(_.isDirectDependency)
        ModuleSummary(name, direct.size, indirect.size)
      }
      .sortBy(_.name)

    // (coordinate, version, module) for every node, then grouped.
    val entries = for {
      (moduleName, manifest) <- manifests.toSeq
      key <- manifest.resolved.keys
      (coordinate, version) <- split(key)
    } yield (coordinate, version, moduleName)

    val coordinates = entries
      .groupBy(_._1)
      .toSeq
      .map { case (coordinate, group) =>
        CoordinateSummary(
          coordinate = coordinate,
          versions = group.map(_._2).distinct.sorted,
          modules = group.map(_._3).distinct.sorted
        )
      }
      .sortBy(_.coordinate)

    GraphSummary(modules, coordinates)
  }
}
