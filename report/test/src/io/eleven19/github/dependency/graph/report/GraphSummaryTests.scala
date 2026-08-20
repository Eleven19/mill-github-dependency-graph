package io.eleven19.github.dependency.graph.report

import io.eleven19.github.dependency.graph.domain._
import utest._

object GraphSummaryTests extends TestSuite {

  private def node(direct: Boolean, children: Seq[String] = Nil) =
    DependencyNode(
      package_url = None,
      metadata = Map.empty,
      relationship = Some(
        if (direct) DependencyRelationship.direct
        else DependencyRelationship.indirect
      ),
      scope = None,
      dependencies = children
    )

  private def manifest(name: String, nodes: (String, DependencyNode)*) =
    Manifest(name, None, Map.empty, nodes.toMap)

  /** Two modules that share slf4j at one version, and disagree on jackson. */
  private val manifests = Map(
    "app" -> manifest(
      "app",
      "org.slf4j:slf4j-api:2.0.16" -> node(direct = true),
      "com.fasterxml.jackson.core:jackson-core:2.18.2" -> node(direct = false)
    ),
    "lib" -> manifest(
      "lib",
      "org.slf4j:slf4j-api:2.0.16" -> node(direct = false),
      "com.fasterxml.jackson.core:jackson-core:2.15.0" -> node(direct = false),
      "org.junit.platform:junit-platform-commons:1.11.4" -> node(direct = true)
    )
  )

  private val summary = GraphSummary.from(manifests)

  val tests = Tests {

    test("module rollups") {

      test("one entry per module") {
        assert(summary.modules.map(_.name).toSet == Set("app", "lib"))
      }

      test("splits direct from indirect") {
        val app = summary.modules.find(_.name == "app").get
        assert(app.direct == 1)
        assert(app.indirect == 1)
        assert(app.total == 2)
      }

      test("counts every node across the graph") {
        assert(summary.moduleCount == 2)
        assert(summary.nodeCount == 5)
      }
    }

    test("coordinate rollups") {

      test("groups a coordinate across modules") {
        val slf4j =
          summary.coordinates.find(_.coordinate == "org.slf4j:slf4j-api").get
        assert(slf4j.modules.toSet == Set("app", "lib"))
        assert(slf4j.versions == Seq("2.0.16"))
      }

      test("counts distinct coordinates, not nodes") {
        // Five nodes, but slf4j is one coordinate seen twice.
        assert(summary.distinctCoordinateCount == 3)
      }

      test("a coordinate at one version is not a conflict") {
        val slf4j =
          summary.coordinates.find(_.coordinate == "org.slf4j:slf4j-api").get
        assert(!slf4j.hasConflict)
      }

      test("a coordinate at two versions is a conflict") {
        val jackson = summary.coordinates
          .find(_.coordinate == "com.fasterxml.jackson.core:jackson-core")
          .get
        assert(jackson.hasConflict)
        assert(jackson.versions == Seq("2.15.0", "2.18.2"))
      }

      test("conflicts lists exactly the conflicting coordinates") {
        assert(
          summary.conflicts.map(_.coordinate) ==
            Seq("com.fasterxml.jackson.core:jackson-core")
        )
      }
    }

    test("an empty graph summarises to zeros rather than failing") {
      // A build with no JavaModules is legal; the report renders zeros.
      val empty = GraphSummary.from(Map.empty)
      assert(empty.moduleCount == 0)
      assert(empty.nodeCount == 0)
      assert(empty.distinctCoordinateCount == 0)
      assert(empty.conflicts.isEmpty)
    }
  }
}
