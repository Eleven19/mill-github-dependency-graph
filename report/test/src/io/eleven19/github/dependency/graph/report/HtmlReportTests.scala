package io.eleven19.github.dependency.graph.report

import io.eleven19.github.dependency.graph.domain._
import utest._

object HtmlReportTests extends TestSuite {

  private def node(direct: Boolean) =
    DependencyNode(
      package_url = None,
      metadata = Map.empty,
      relationship = Some(
        if (direct) DependencyRelationship.direct
        else DependencyRelationship.indirect
      ),
      scope = None,
      dependencies = Nil
    )

  private val manifests = Map(
    "app" -> Manifest(
      "app",
      None,
      Map.empty,
      Map(
        "org.slf4j:slf4j-api:2.0.16" -> node(direct = true),
        "com.fasterxml.jackson.core:jackson-core:2.18.2" -> node(direct = false)
      )
    ),
    "lib" -> Manifest(
      "lib",
      None,
      Map.empty,
      Map(
        "com.fasterxml.jackson.core:jackson-core:2.15.0" -> node(direct = false)
      )
    )
  )

  private val html = HtmlReport.render(
    summary = GraphSummary.from(manifests),
    scope = "runtime",
    selection = "2 of 2 modules",
    manifests = manifests
  )

  val tests = Tests {

    test("document shape") {

      test("is a complete html document") {
        assert(html.startsWith("<!DOCTYPE html>"))
        assert(html.contains("</html>"))
      }

      test("is self-contained") {
        // The whole point of a single file: it must open from file://, survive
        // being emailed, and work as a CI artifact with no web server. Any
        // external asset reference breaks all three, silently.
        assert(!html.contains("http://"))
        assert(!html.contains("https://"))
      }

      test("carries its own styling and behaviour inline") {
        assert(html.contains("<style>"))
        assert(html.contains("<script>"))
      }
    }

    test("header states what the run covered") {
      assert(html.contains("runtime"))
      assert(html.contains("2 of 2 modules"))
    }

    test("stats") {

      test("reports module, node and coordinate counts") {
        // Pinning the exact rendered digits (">2<", ">3<") is brittle: it
        // depends on markup this task is writing. Anchoring on a
        // `data-stat` marker instead asserts the same fact -- the module
        // count and the node count are both present, and distinguishable
        // from each other -- without coupling the test to incidental
        // formatting.
        assert(html.contains("data-stat=\"modules\">2<"))
        assert(html.contains("data-stat=\"nodes\">3<"))
      }

      test("reports the conflict count") {
        // jackson-core appears at 2.15.0 and 2.18.2. Anchored on the same
        // `data-stat` marker as the other counters: `contains("conflict")`
        // alone can never fail, since that substring also lives in static
        // CSS class names, the "Version conflicts" label, and the
        // explanation note -- present even on the empty-graph fixture.
        assert(html.contains("data-stat=\"conflicts\">1<"))
      }
    }

    test("by-module tab") {

      test("has a row per module") {
        assert(html.contains("app"))
        assert(html.contains("lib"))
      }

      test("lists a module's own dependencies") {
        assert(html.contains("org.slf4j:slf4j-api:2.0.16"))
      }
    }

    test("by-dependency tab") {

      test("groups a coordinate across modules") {
        assert(html.contains("com.fasterxml.jackson.core:jackson-core"))
      }

      test("shows every version a coordinate was seen at") {
        assert(html.contains("2.15.0"))
        assert(html.contains("2.18.2"))
      }

      test("marks a conflicting row") {
        // jackson-core is the only coordinate at more than one version; its
        // row carries the "conflict" class alongside the plain "row" class
        // every row gets, so a later restyle can rename or move the visual
        // treatment without this test caring, but cannot delete the marker
        // outright without going red.
        assert(html.contains("class=\"row conflict\""))
      }

      test("explains what a conflict means") {
        // The brief requires this be stated plainly on the panel, because
        // the first reaction to a marked row is otherwise "is this a bug in
        // the plugin?". Anchored on a distinctive phrase from the sentence,
        // not the whole thing, so minor copy-editing does not break it.
        assert(html.contains("Coursier has already reconciled"))
      }
    }

    test("escapes content rather than injecting it") {
      // Coordinates come from POMs we do not control. scalatags escapes text
      // nodes by default; this asserts we did not reach for `raw` on them.
      val hostile = Map(
        "app" -> Manifest(
          "app",
          None,
          Map.empty,
          Map("org.evil:<script>alert(1)</script>:1.0" -> node(direct = true))
        )
      )
      val rendered = HtmlReport.render(
        GraphSummary.from(hostile),
        "runtime",
        "1 of 1 modules",
        hostile
      )
      assert(!rendered.contains("<script>alert(1)</script>"))
      assert(rendered.contains("&lt;script&gt;"))
    }

    test("an empty graph renders rather than failing") {
      val rendered =
        HtmlReport.render(
          GraphSummary.from(Map.empty),
          "runtime",
          "0 modules",
          Map.empty
        )
      assert(rendered.startsWith("<!DOCTYPE html>"))
    }
  }
}
