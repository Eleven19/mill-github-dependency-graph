package io.eleven19.mill.github.dependency.graph

import com.github.packageurl.PackageURLBuilder
import coursier.graph.DependencyTree
import io.eleven19.github.dependency.graph.domain._
import mill.javalib.JavaModule

import scala.collection.mutable
import scala.util.Try

/** Represents a project module and the dependency trees that belong to it.
  *
  * @param module The module
  * @param roots Each tree, paired with what the nodes reached from it inherit.
  *   A root's own [[NodeFacts]] describe the root itself; everything below it
  *   inherits the scope and becomes indirect.
  */
final case class ModuleTrees(
    module: JavaModule,
    roots: Seq[(DependencyTree, NodeFacts)]
) {

  /** Takes the dependencyTrees and flattens them to fit the model of the
    * DependencyNode that GitHub wants. They become flattened and every
    * dependency has a top level entry. Only the roots of the trees however
    * get a "direct" relationship.
    *
    * A coordinate can be reached from more than one root — declared directly
    * *and* pulled in through a `moduleDeps` edge, say, or present both at
    * runtime and as a `compileMvnDeps`. [[NodeFacts.merge]] decides what the
    * node then says, and it is order-independent, so the order roots are
    * walked in does not change the output.
    *
    * @return Mapping of the name of the dependency and the DependencyNode that
    * corresponds to it. The format of the name is org:module:version.
    */
  def toFlattenedNodes()(implicit
      ctx: mill.api.TaskCtx
  ): Map[String, DependencyNode] = {

    // Keep track of every seen dependency and the DependencyNode for it
    val allDependencies = mutable.Map[String, DependencyNode]()
    // NOTE: maybe not necessary, but since we do this lookup various times, we cache it
    val treeToName = mutable.Map[DependencyTree, String]()

    def getNameFromTree(tree: DependencyTree): String =
      treeToName.getOrElseUpdate(
        tree, {
          val dep = tree.dependency
          @annotation.nowarn("msg=deprecated")
          val reconciledVersion0 = tree.reconciledVersion
          s"${dep.module.orgName}:${reconciledVersion0}"
        }
      )

    def factsOf(node: DependencyNode): NodeFacts =
      NodeFacts(
        node.relationship.getOrElse(DependencyRelationship.indirect),
        node.scope.getOrElse(DependencyScope.runtime)
      )

    /** True when the recorded node already says everything `facts` would add.
      *
      * This is what makes revisiting cheap *and* correct. The old check was
      * "have we seen this name at all", which was fine when the only claim a
      * root made was direct-or-indirect and roots were walked direct-first.
      * Now a later root can carry a *wider* scope, and skipping it would
      * leave a runtime dependency marked development.
      */
    def settled(name: String, facts: NodeFacts): Boolean =
      allDependencies
        .get(name)
        .exists(node => factsOf(node).merge(facts) == factsOf(node))

    def toNode(tree: DependencyTree, facts: NodeFacts): Unit = {
      val dep = tree.dependency
      val name = getNameFromTree(tree)
      @annotation.nowarn("msg=deprecated")
      val reconciledVersion0 = tree.reconciledVersion
      val childrenNames = tree.children.map(getNameFromTree)

      def purl: Option[String] =
        Try(
          PackageURLBuilder
            .aPackageURL()
            .withType("maven")
            .withNamespace(dep.module.organization.value)
            .withName(dep.module.name.value)
            .withVersion(reconciledVersion0)
            .build()
        ).fold(
          e => {
            ctx.log.error(
              s"PURL can't be created from: ${dep.module.orgName}:${reconciledVersion0}"
            )
            ctx.log.error(e.getMessage())
            None
          },
          validPurl => Some(validPurl.toString())
        )

      allDependencies.get(name) match {
        case Some(existing) =>
          val merged = factsOf(existing).merge(facts)
          if (merged != factsOf(existing))
            allDependencies += ((
              name,
              existing.copy(
                relationship = Some(merged.relationship),
                scope = Some(merged.scope)
              )
            ))
          else
            ctx.log.debug(
              s"Already seen ${name} saying at least this much, so skipping..."
            )

        // Not a very elegant check, but we don't want to include a range in
        // here. These shouldn't still be a range at this point, but it is for
        // whatever reason. For now ignore it. This should be incredibly rare
        // and I believe a bug in coursier.
        case None if reconciledVersion0.contains(",") =>
          ctx.log.error(
            s"""Found what I think is a range version that shouldn't be here...
                |
                |${dep.module.organization.value}:${dep.module.name.value}:${reconciledVersion0}
                |
                |If you see this, report it. Skipping...
                |""".stripMargin
          )

        case None =>
          allDependencies += ((
            name,
            DependencyNode(
              purl,
              // TODO we can check if original == reconciled here and add metadata that it is a reconciled version
              Map.empty,
              Some(facts.relationship),
              Some(facts.scope),
              childrenNames
            )
          ))
      }

      // Everything below a root is indirect, whatever the root itself was.
      // Scope is inherited unchanged: a dependency reached only through a
      // test module's tree is a development dependency all the way down.
      val childFacts = facts.asIndirect

      if (childrenNames.forall(settled(_, childFacts))) {
        ctx.log.debug(
          s"short circuiting as all children of ${name} are already settled."
        )
      } else {
        // This is a bit odd, but needed in the context of
        // https://github.com/ckipp01/mill-github-dependency-graph/issues/77
        // There can be poms that _look_ like they have cyclical dependencies
        // espeically when using classifiers. This actually seems like it might
        // be another bug in Couriser:
        // https://github.com/coursier/coursier/issues/2683
        // So, for now we filter out itself if it has itself listed as a child.
        // Children that already say everything we would tell them are skipped
        // too, which is what stops a cycle here: `merge` only ever widens a
        // node, and both axes have one step, so revisiting converges.
        tree.children
          .filterNot(child =>
            child == tree || settled(getNameFromTree(child), childFacts)
          )
          .foreach(toNode(_, childFacts))
      }
    }

    roots.foreach { case (tree, facts) => toNode(tree, facts) }
    allDependencies.toMap
  }

  def toManifest()(implicit ctx: mill.api.TaskCtx): Manifest = {
    // NOTE: That this may seem odd when reading the spec that we have a
    // manifest per module basically, but we did check with the GitHub team and
    // they verified the manifests that we showed them.
    val name = module.toString()
    // TODO in the future we may want to also figure out how to resolve these
    // locations if they are defined in other files, but for now we just say package.mill
    val file = FileInfo("package.mill")
    val resolved = toFlattenedNodes()
    Manifest(name, Some(file), Map.empty, resolved)
  }
}
