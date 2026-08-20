package io.eleven19.github.dependency.graph.report

import io.eleven19.github.dependency.graph.domain.{DependencyNode, Manifest}
import scalatags.Text.all._

/** Renders a [[GraphSummary]] as one self-contained HTML page.
  *
  * The page is a single file: inline `<style>`, inline `<script>`, no
  * external asset and no `http://`/`https://` reference anywhere in the
  * output. That is what lets it open from `file://`, survive being emailed,
  * and work as a CI artifact with no web server.
  *
  * Everything derived from a coordinate, a version or a module name is
  * passed to scalatags as an ordinary text node, so it is HTML-escaped.
  * `raw(...)` is reserved for the CSS and the JS, which are fixed strings
  * this module owns, not data from a POM we do not control.
  */
object HtmlReport {

  // `details`/`summary` give the by-module and by-dependency rows their
  // "expands in place" behaviour for free, with no JavaScript involved.
  // `title` and `style` are pulled in explicitly by name because scalatags
  // also exposes attributes of those names, which otherwise shadow the tag.
  private val details = tag("details")
  private val summaryTag = tag("summary")
  private val titleTag = tag("title")
  private val styleTag = tag("style")
  private val sectionTag = tag("section")

  def render(
      summary: GraphSummary,
      scope: String,
      selection: String,
      manifests: Map[String, Manifest]
  ): String = {
    val page = doctype("html")(
      html(lang := "en")(
        head(
          meta(charset := "utf-8"),
          titleTag("Dependency Graph Report"),
          styleTag(raw(css))
        ),
        body(
          header(
            h1("Dependency Graph Report"),
            p(cls := "meta")("Scope: ", strong(scope)),
            p(cls := "meta")("Selection: ", strong(selection))
          ),
          sectionTag(cls := "stats")(
            statCard("modules", "Modules", summary.moduleCount),
            statCard("nodes", "Dependencies", summary.nodeCount),
            statCard(
              "coordinates",
              "Distinct coordinates",
              summary.distinctCoordinateCount
            ),
            statCard("conflicts", "Version conflicts", summary.conflicts.size)
          ),
          input(
            id := "filter",
            `type` := "search",
            placeholder := "Filter the active tab..."
          ),
          div(cls := "tabs")(
            button(
              cls := "tab-button active",
              `type` := "button",
              attr("data-tab") := "by-module"
            )("By module"),
            button(
              cls := "tab-button",
              `type` := "button",
              attr("data-tab") := "by-dependency"
            )("By dependency")
          ),
          div(id := "by-module", cls := "tab-panel active")(
            moduleTable(summary.modules, manifests)
          ),
          div(id := "by-dependency", cls := "tab-panel")(
            p(cls := "note")(
              "A conflict means one coordinate was resolved to more than one version ",
              strong("across modules"),
              ". Within a single module, Coursier has already reconciled that " +
                "coordinate to one version -- a marked row is not a bug in this plugin."
            ),
            coordinateTable(summary.coordinates)
          ),
          script(raw(js))
        )
      )
    )
    page.render
  }

  private def statCard(statKey: String, label: String, value: Int): Frag =
    div(cls := "stat")(
      span(cls := "stat-value", attr("data-stat") := statKey)(value.toString),
      span(cls := "stat-label")(label)
    )

  private def moduleTable(
      modules: Seq[ModuleSummary],
      manifests: Map[String, Manifest]
  ): Frag =
    div(cls := "table")(
      div(cls := "row-header")(
        span(cls := "cell name")("Module"),
        span(cls := "cell num")("Direct"),
        span(cls := "cell num")("Indirect"),
        span(cls := "cell num")("Total")
      ),
      if (modules.isEmpty) p(cls := "empty")("No modules in this run.")
      else modules.map(m => moduleRow(m, manifests.get(m.name)))
    )

  private def moduleRow(
      module: ModuleSummary,
      manifest: Option[Manifest]
  ): Frag = {
    val dependencies: Seq[(String, DependencyNode)] =
      manifest.map(_.resolved.toSeq.sortBy(_._1)).getOrElse(Nil)

    details(cls := "row")(
      summaryTag(cls := "row-summary")(
        span(cls := "cell name")(module.name),
        span(cls := "cell num")(module.direct.toString),
        span(cls := "cell num")(module.indirect.toString),
        span(cls := "cell num")(module.total.toString)
      ),
      div(cls := "detail")(
        if (dependencies.isEmpty) p(cls := "empty")("No dependencies.")
        else
          ul(cls := "dep-list")(
            dependencies.map { case (coordinate, dependencyNode) =>
              val isDirect = dependencyNode.isDirectDependency
              val badgeClass =
                if (isDirect) "badge-direct" else "badge-indirect"
              val badgeLabel = if (isDirect) "direct" else "indirect"
              li(
                span(cls := "coord")(coordinate),
                span(cls := s"badge $badgeClass")(badgeLabel)
              )
            }
          )
      )
    )
  }

  private def coordinateTable(coordinates: Seq[CoordinateSummary]): Frag =
    div(cls := "table")(
      div(cls := "row-header dep-header")(
        span(cls := "cell coord")("Coordinate"),
        span(cls := "cell")("Versions"),
        span(cls := "cell num")("Modules")
      ),
      if (coordinates.isEmpty) p(cls := "empty")("No dependencies in this run.")
      else coordinates.map(coordinateRow)
    )

  private def coordinateRow(coordinate: CoordinateSummary): Frag = {
    val rowClass = if (coordinate.hasConflict) "row conflict" else "row"
    details(cls := rowClass)(
      summaryTag(cls := "row-summary")(
        span(cls := "cell coord")(
          coordinate.coordinate,
          if (coordinate.hasConflict)
            span(cls := "badge badge-conflict")("conflict")
          else frag()
        ),
        span(cls := "cell")(coordinate.versions.mkString(", ")),
        span(cls := "cell num")(coordinate.modules.size.toString)
      ),
      div(cls := "detail")(
        p(cls := "empty")("Reported by:"),
        ul(cls := "dep-list")(coordinate.modules.map(m => li(m)))
      )
    )
  }

  private val css =
    """
      |:root { color-scheme: light; }
      |* { box-sizing: border-box; }
      |body {
      |  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      |  margin: 0; padding: 1.5rem; color: #1a1a1a; background: #fff;
      |}
      |header h1 { margin: 0 0 0.25rem; font-size: 1.5rem; }
      |.meta { margin: 0.1rem 0; color: #444; }
      |.stats { display: flex; gap: 1rem; margin: 1rem 0; flex-wrap: wrap; }
      |.stat { border: 1px solid #ccc; border-radius: 6px; padding: 0.5rem 1rem; min-width: 8rem; }
      |.stat-value { display: block; font-size: 1.5rem; font-weight: bold; }
      |.stat-label { display: block; font-size: 0.8rem; color: #555; }
      |#filter {
      |  width: 100%; max-width: 30rem; padding: 0.4rem 0.6rem; margin: 0.5rem 0 1rem;
      |  border: 1px solid #999; border-radius: 4px; font-size: 1rem;
      |}
      |.tabs { display: flex; gap: 0.5rem; margin-bottom: 0.5rem; }
      |.tab-button {
      |  padding: 0.4rem 1rem; border: 1px solid #999; background: #f3f3f3;
      |  border-radius: 4px 4px 0 0; cursor: pointer; font-size: 0.95rem;
      |}
      |.tab-button.active { background: #fff; border-bottom: 2px solid #06c; font-weight: bold; }
      |.tab-panel { display: none; border-top: 1px solid #ccc; padding-top: 0.5rem; }
      |.tab-panel.active { display: block; }
      |.table { border: 1px solid #ccc; border-radius: 4px; overflow: hidden; }
      |.row-header, .row-summary {
      |  display: grid; grid-template-columns: 3fr 1fr 1fr 1fr; gap: 0.5rem;
      |  padding: 0.4rem 0.6rem; align-items: center;
      |}
      |.dep-header, #by-dependency .row-summary { grid-template-columns: 3fr 2fr 1fr; }
      |.row-header { font-weight: bold; background: #eee; border-bottom: 1px solid #ccc; }
      |.row { border-bottom: 1px solid #eee; }
      |.row:last-child { border-bottom: none; }
      |.row-summary { cursor: pointer; list-style: none; }
      |.row-summary::-webkit-details-marker { display: none; }
      |/* `display: grid` on <summary> removes `display: list-item`, so the
      |   native disclosure triangle never generates a box at all. Without an
      |   explicit marker there is no cue that half the report's content --
      |   every module's dependencies, every conflict's module list -- is one
      |   click away. */
      |.row-summary > .cell:first-child::before {
      |  content: "\25B8"; display: inline-block; width: 1em; color: #666;
      |}
      |details[open] > .row-summary > .cell:first-child::before { content: "\25BE"; }
      |.row.conflict { background: #fff4e5; }
      |.row.conflict > .row-summary { border-left: 4px solid #c60; }
      |.cell.coord, .cell.name, .coord { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
      |.detail { padding: 0.5rem 0.8rem 0.8rem 1.2rem; background: #fafafa; }
      |.detail .empty { margin: 0 0 0.3rem; font-size: 0.85rem; color: #555; }
      |.dep-list { list-style: none; margin: 0; padding: 0; }
      |.dep-list li {
      |  display: flex; justify-content: space-between; gap: 0.5rem; padding: 0.15rem 0;
      |  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      |}
      |.badge {
      |  display: inline-block; font-family: -apple-system, sans-serif; font-size: 0.75rem;
      |  padding: 0.1rem 0.5rem; border-radius: 999px; text-transform: uppercase;
      |}
      |.badge-direct { background: #d7f0d7; color: #1a5c1a; }
      |.badge-indirect { background: #e0e0f0; color: #33335c; }
      |.badge-conflict { background: #f8d7a0; color: #7a4a00; margin-left: 0.4rem; }
      |.note { font-size: 0.85rem; color: #555; margin: 0.3rem 0 0.8rem; max-width: 44rem; }
      |.empty { color: #777; font-style: italic; }
      |@media print {
      |  .tab-panel { display: block !important; }
      |  .tabs, #filter { display: none; }
      |}
      |""".stripMargin

  private val js =
    """
      |(function () {
      |  var tabButtons = document.querySelectorAll('.tab-button');
      |  var tabPanels = document.querySelectorAll('.tab-panel');
      |  var filterInput = document.getElementById('filter');
      |
      |  // Behaviour 1: switching the active tab.
      |  function applyFilter() {
      |    if (!filterInput) { return; }
      |    var needle = filterInput.value.toLowerCase();
      |    var activePanel = document.querySelector('.tab-panel.active');
      |    if (!activePanel) { return; }
      |    var rows = activePanel.querySelectorAll('.row');
      |    rows.forEach(function (row) {
      |      var haystack = row.textContent.toLowerCase();
      |      row.style.display = haystack.indexOf(needle) === -1 ? 'none' : '';
      |    });
      |  }
      |
      |  tabButtons.forEach(function (button) {
      |    button.addEventListener('click', function () {
      |      tabButtons.forEach(function (b) { b.classList.remove('active'); });
      |      tabPanels.forEach(function (p) { p.classList.remove('active'); });
      |      button.classList.add('active');
      |      var target = document.getElementById(button.getAttribute('data-tab'));
      |      if (target) { target.classList.add('active'); }
      |      // Keep whichever filter text is already typed applied to the tab
      |      // just switched into, rather than only on the next keystroke.
      |      applyFilter();
      |    });
      |  });
      |
      |  // Behaviour 2: hiding rows whose text does not contain the filter string.
      |  if (filterInput) {
      |    filterInput.addEventListener('input', applyFilter);
      |  }
      |})();
      |""".stripMargin
}
