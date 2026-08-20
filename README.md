# Mill GitHub Dependency Graph

[![Maven Central](https://img.shields.io/maven-central/v/io.eleven19.mill-github-dependency-graph/mill-github-dependency-graph_mill1_3)](https://central.sonatype.com/artifact/io.eleven19.mill-github-dependency-graph/mill-github-dependency-graph_mill1_3)

A [Mill](https://mill-build.org/) plugin to
submit your dependency graph to GitHub via their [Dependency Submission
API](https://github.blog/2022-06-17-creating-comprehensive-dependency-graph-build-time-detection/).

This is a derivative work of [ckipp01/mill-github-dependency-graph](https://github.com/ckipp01/mill-github-dependency-graph),
updated to support Mill 1.x and maintained by [Eleven19](https://github.com/Eleven19).

The main benefits of doing this are:

1. Being able to see your dependency graph on GitHub in your [Insights
   tab](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/exploring-the-dependencies-of-a-repository#viewing-the-dependency-graph).
2. If enabled, Dependabot can send you
   [alerts](https://docs.github.com/en/code-security/dependabot/dependabot-alerts/viewing-and-updating-dependabot-alerts)
   about security vulnerabilities in your dependencies.

## Requirements

- Mill 1.1.8 or later. That is the version this plugin is built and tested
  against. It reaches into Mill's internal resolver APIs, so earlier 1.x patch
  releases are untested.
- In your repo settings, the Dependency Graph feature must be enabled, plus
  Dependabot Alerts if you want them. (Settings -> Code security and analysis)
- Your workflow job needs `permissions: contents: write`. GitHub's dependency
  submission endpoint writes to the repository, and the default `GITHUB_TOKEN`
  is read-only in many organisations. Without it you get a `403`.

## Installation

Add the plugin as a build dependency in your `build.mill.yaml`:

```yaml
mill-build:
  mvnDeps:
    - "io.eleven19.mill-github-dependency-graph::mill-github-dependency-graph_mill$MILL_BIN_PLATFORM:0.2.0"
```

Check the Maven Central badge above for the current version — the coordinate
here is not updated on every release.

`$MILL_BIN_PLATFORM` is supplied by Mill and expands to the Mill binary
platform you are building with (`1` for Mill 1.x), so the coordinate above
resolves to `mill-github-dependency-graph_mill1_3`.

### Maven Coordinates

| | Group ID | Artifact ID |
|---|---|---|
| **Plugin** | `io.eleven19.mill-github-dependency-graph` | `mill-github-dependency-graph_mill1_3` |
| **Domain** | `io.eleven19.mill-github-dependency-graph` | `github-dependency-graph-domain_3` |
| **Report** | `io.eleven19.mill-github-dependency-graph` | `github-dependency-graph-report_3` *(new in `0.2.0`)* |

The plugin compiles against the Mill API, so its artifact carries the
`_mill1` platform suffix that Mill plugins use to declare which Mill binary
platform they target. The domain and report modules have no Mill dependency
and stay unsuffixed.

> **Upgrading from `0.0.x`:** the artifact id changed in `0.1.0`. Releases up
> to and including `0.0.2` were published as `mill-github-dependency-graph_3`,
> with no platform suffix. A build still pinned to the old coordinate will not
> see `0.1.0` or anything after it, so the coordinate has to be updated, not
> just the version.

Browse on Sonatype Central:
[mill-github-dependency-graph](https://central.sonatype.com/artifact/io.eleven19.mill-github-dependency-graph/mill-github-dependency-graph_mill1_3)
| [github-dependency-graph-domain](https://central.sonatype.com/artifact/io.eleven19.mill-github-dependency-graph/github-dependency-graph-domain_3)
| [github-dependency-graph-report](https://central.sonatype.com/artifact/io.eleven19.mill-github-dependency-graph/github-dependency-graph-report_3)

## Getting started

The plugin gives you three commands. All are spelled out in full because the
plugin installs itself as an external module, not as a task on your own
modules.

**`generate`** builds the dependency manifests and writes them to
`out/io.eleven19.mill.github.dependency.graph.Graph/generate.json`. It never
contacts GitHub. Note that it prints *nothing* — like any Mill command, its
result goes to the `out/` metadata file. Add `show` to print it instead:

```sh
# Runs it. Prints nothing.
./mill io.eleven19.mill.github.dependency.graph.Graph/generate

# Runs it and prints the manifests as JSON.
./mill show io.eleven19.mill.github.dependency.graph.Graph/generate
```

> It does still reach the network: resolving dependencies downloads POM files
> from your configured repositories. It just never talks to *GitHub*.

**`submit`** does the same work and then POSTs the result to GitHub. It only
works inside GitHub Actions, because it reads the run's identity from the
environment variables Actions sets (`GITHUB_TOKEN`, `GITHUB_REPOSITORY`,
`GITHUB_SHA` and friends).

```yml
name: github-dependency-graph

on:
  push:
    branches:
      - main

jobs:
  submit-dependency-graph:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
    - uses: actions/checkout@v7
    - uses: actions/setup-java@v5
      with:
        distribution: 'temurin'
        java-version: '21'
    - name: Submit dependency graph
      env:
        GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
      run: ./mill io.eleven19.mill.github.dependency.graph.Graph/submit
```

After you submit your graph you'll be able to [view your
dependencies](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/exploring-the-dependencies-of-a-repository#viewing-the-dependency-graph).

> **Try `generate` before you wire up `submit`.** `generate` produces the
> manifests that `submit` sends — `submit` just wraps them with the commit
> sha, ref and job metadata and POSTs the lot. Every option below works
> identically on `generate` and `submit`, so you can tune your flags locally
> with `show` and then paste the same ones into your workflow.

**`report`** renders the same graph as a single self-contained HTML file —
one page, no server or extra assets — that you can open straight in a
browser. Like `generate`, it never contacts GitHub:

```sh
./mill io.eleven19.mill.github.dependency.graph.Graph/report
```

> `report`, and `--output` on any command, need `0.2.0` or later. If
> `Graph/report` fails with `Cannot resolve report`, your pinned coordinate
> predates it — bump the version in `build.mill.yaml`.

See [HTML report](#html-report) for what is on the page.

## Configuration

> **These options — and `Graph/report` itself — need `0.2.0` or later.** If
> `--scope` gives you `Unknown arguments: "--scope"`, or `Graph/report` gives
> you `Cannot resolve report`, your pinned plugin predates them — bump the
> version in `build.mill.yaml`.

All three commands accept the same four options. You need none of them to start:
out of the box the plugin covers every module in your build, at runtime scope.

| Option | Values | Default | What it does |
|---|---|---|---|
| `--scope` | `compile`, `runtime`, `all` | unset — each module decides (see `dependencyGraphScope`) | How much of each module's dependency graph to report |
| `--modules` | Mill selectors, repeatable | every module | Only cover the modules these selectors name |
| `--exclude-modules` | Mill selectors, repeatable | nothing excluded | Drop modules, applied after `--modules` |
| `--output` | a file path | `generate`/`submit`: unset — nothing extra is written; only Mill's own `out/…/generate.json` exists. `report`: unset — the command's own `out/…/report.dest/graph-report.html` | `generate`/`submit`: also write the manifests as JSON to this path, as a copy — Mill's own `out/…/generate.json` is written either way. `report`: writes the HTML page to this path *instead of* its default location — no `report.dest/` is created. A relative path resolves against the workspace root for all three. |

There is also one build-file setting:

| Setting | Type | Default | What it does |
|---|---|---|---|
| `dependencyGraphScope` | `GraphScope` | `GraphScope.Runtime` | Per-module scope, from the `GraphScopeModule` trait |

### `--scope` — how much of each module to report

A Maven dependency has a *scope* saying when it is needed. Something you
`import` in your source is `compile` scope. A database driver you never import
but must be on the classpath at run time is `runtime` scope. An annotation
processor the compiler needs but that never ships is `provided` scope.

This flag picks how far down that list to go. Each value **contains** the one
above it, so they only ever get wider:

| `--scope` | Mill dependencies covered | Reach for it when |
|---|---|---|
| `compile` | `allMvnDeps` (that is `mvnDeps` plus Mill's own `mandatoryMvnDeps`, such as the Scala library) and their compile-scope transitives | You want the smallest honest answer to "what does my code compile against?" Useful for a licence or provenance audit where a driver you never import is noise. |
| `runtime` | the above, plus `runMvnDeps` and any transitive a POM declares at `runtime` scope | Almost always. A dependency declared at `runtime` scope — an SLF4J binding, a JDBC driver, or `junit-platform-suite-commons`, which `junit-platform-suite-engine` declares that way — is invisible at `compile` scope but is on the classpath when the code runs. |
| `all` | the above, plus `compileMvnDeps`, which are provided scope | You care about build-time-only dependencies too: Lombok, annotation processors, a servlet API your container supplies. These ship in nobody's jar, but a vulnerability in one still runs on your build machine. |

```sh
# Widest: also reports compileMvnDeps.
./mill io.eleven19.mill.github.dependency.graph.Graph/generate --scope all
```

> **There is no `test` scope, and you are not missing anything.** Mill maps
> `test` to the same set as `runtime`, so it would be a second name for a
> setting that already has one. Your test modules are ordinary modules — they
> get their own manifests, resolved like any other module.

A bad value fails immediately and names the valid ones. Mill prints it behind
its own `[error]` prefix, with a stack trace:

```
java.lang.IllegalArgumentException: Unknown scope 'runtim'. Expected one of: compile, runtime, all.
```

### `dependencyGraphScope` — a different scope for one module

Sometimes one module deserves a different answer from the rest of the build.
Mix `GraphScopeModule` into it and override `dependencyGraphScope`:

```scala
import mill._, scalalib._
import io.eleven19.mill.github.dependency.graph.{GraphScope, GraphScopeModule}

object server extends ScalaModule with GraphScopeModule {
  // This module ships in a container that supplies the servlet API, so its
  // compileMvnDeps are worth reporting even though the rest of the build's
  // are not.
  override def dependencyGraphScope = Task { GraphScope.All }
}
```

Modules that do not mix in the trait use `GraphScope.Runtime`.

> **When to reach for the trait instead of the flag.** The flag is for one
> run; the trait is for a fact about your build that should stay true whoever
> runs it. If you find yourself always passing the same `--scope` in CI for
> one module's sake, the trait is the better home for that decision.

**Passing `--scope` at all overrides every module's declaration.** This is the
one piece of precedence worth remembering, because it makes `--scope runtime`
different from passing nothing:

| You run | `server` (declares `All`) | every other module |
|---|---|---|
| no `--scope` | `all` | `runtime` |
| `--scope runtime` | `runtime` | `runtime` |
| `--scope compile` | `compile` | `compile` |

So `--scope runtime` is not a no-op — it is how you force one scope across the
whole build, ignoring what individual modules asked for.

### `--modules` and `--exclude-modules` — which modules to cover

By default the plugin covers every `JavaModule` in your build. Both flags take
[Mill selectors](https://mill-build.org/mill/cli/query-syntax.html) — the same
syntax you already use for `./mill foo.bar.compile` — and both can be passed
more than once.

`--modules` narrows down to what the selectors name:

```sh
# Just the `app` module itself.
./mill io.eleven19.mill.github.dependency.graph.Graph/generate --modules 'app'

# `app` and everything nested under it, e.g. `app.test`.
./mill io.eleven19.mill.github.dependency.graph.Graph/generate --modules 'app.__'
```

> **`app` and `app.__` are not the same thing.** `app` is exactly one module.
> `app.__` is `app` plus its descendants. If you narrow to `app` and the graph
> comes back smaller than you expected, this is usually why.

`--exclude-modules` drops modules, and runs *after* `--modules`:

```sh
# Everything except test modules.
./mill io.eleven19.mill.github.dependency.graph.Graph/generate \
  --exclude-modules '__.test'

# Combined: the app subtree, but not its tests.
./mill io.eleven19.mill.github.dependency.graph.Graph/generate \
  --modules 'app.__' --exclude-modules 'app.test'
```

Whenever a filter drops anything, the run says so:

```
covering 3 of 5 modules, 2 excluded by selector
```

> **Think twice before excluding test modules.** It is the most common thing
> people reach for, and it is not free. A compromised test-only dependency
> still executes on your CI machines with your CI credentials. Excluding tests
> makes the graph tidier and makes you blind to that. Prefer it when a
> subtree's dependencies genuinely are not yours to worry about — a vendored
> sample project, say — rather than as routine noise reduction.

#### Three rules of selector matching that will surprise you

**A selector Mill cannot resolve is a hard error.** No command falls back
to covering fewer modules:

```
Cannot resolve nosuchmodule. Try `mill resolve _` to see what's available.
```

This means a CI job pinned to `--exclude-modules 'legacy.__'` starts *failing*
the day someone deletes the `legacy` module. That is on purpose — the
alternative is a job that keeps passing while quietly submitting a graph that
no longer matches your build.

**But a selector that resolves to something which is not a `JavaModule` is
silently ignored.** The plugin only reports `JavaModule`s. If a selector names
a task on a plain `Module`, it resolves fine, contributes nothing, and says
nothing — so `--modules 'app' --modules 'tooling.stamp'` quietly covers `app`
alone. `./mill resolve '<your selector>'` shows you what a selector matches,
but you still have to check that those are modules you expect to see in the
graph.

**Matching is literal on a selector's final segment.** `__.test` matches
modules whose last segment is exactly `test`. It does **not** match `itest`,
`testing`, or `tests`.

This is not a nitpick. On the real 157-module
[`finos/morphir-scala`](https://github.com/finos/morphir-scala) build,
`--exclude-modules '__.test'` drops 61 modules and leaves 96 manifests. Eight
more modules there are test modules by intent, spelled `itest`, `tests.*` or
`testing.*` — none of which ends in the literal segment `test`.

There is no single selector that catches the rest, because their final
segments are `js`, `jvm` and `scoverage`, not anything test-shaped. You name
the subtrees you actually have:

```sh
# Only correct for a build that HAS these modules -- per the first rule, a
# selector naming something absent fails the command outright.
./mill io.eleven19.mill.github.dependency.graph.Graph/generate \
  --exclude-modules '__.test' --exclude-modules 'testing.__'
```

#### Selectors that leave nothing to report are a hard error

If your selectors leave no modules, all three commands fail:

```
The selectors given left no modules to report. --modules app.__; --exclude-modules app.__.
```

This looks strict, and for `submit` there is a specific reason. GitHub keys
each submission on a *correlator* — for this plugin, your workflow and job
name — together with the detector name. An empty submission is therefore not
ignored: it **replaces** everything this plugin previously submitted under
that correlator, and Dependabot goes quiet. Failing the build is much easier
to notice. The guard itself lives in `generate` — `submit` and `report`
both fail the same way because they call `generate` to build the graph
before doing anything else with it. The alternative would be a `generate` or
`report` run that silently succeeds with nothing in it.

(A build with no `JavaModule` at all is not caught by this check — there are
no selectors to blame. It submits an empty graph.)

## Usage examples

Recipes for the situations that actually come up.

### Preview what would be submitted

```sh
./mill show io.eleven19.mill.github.dependency.graph.Graph/generate
```

For a build of any size that is a lot of JSON. There is no `--json` flag for
a smaller shape — pipe `mill show`'s output through `jq` instead:

```sh
./mill show io.eleven19.mill.github.dependency.graph.Graph/generate \
  | jq 'to_entries | map({module: .key, dependencies: (.value.resolved | length)})'
```

To see the same count-per-module summary without writing a query, use
[`Graph/report`](#html-report): its "By module" tab lists every module with
its direct, indirect and total counts.

### HTML report

`Graph/report` renders the graph `generate` builds as one self-contained HTML
file — no server, no extra assets — that you can open directly in a browser
or attach as a CI artifact:

```sh
./mill io.eleven19.mill.github.dependency.graph.Graph/report --output dependency-graph.html
```

With no `--output`, it writes into the command's own Mill task directory and
logs the absolute path it wrote. `--scope`, `--modules` and `--exclude-modules`
work exactly as they do on `generate`, and the page's header states which
scope and which module selection it was built from.

The page has two tabs, and one filter box that searches whichever tab is
open:

- **By module** — every module with its direct, indirect and total dependency
  counts. Expand a row to see that module's dependencies.
- **By dependency** — every `org:name`, the versions seen for it across your
  build, and how many modules carry it. Expand a row to see which modules. A
  coordinate resolved to more than one version across modules is marked —
  that is what "version conflict" means here; within a single module,
  coursier has already reconciled to one version.

### Check whether one specific dependency made it into the graph

Useful when you expect something and do not see it in the GitHub UI. This is
the recipe `Graph/report` was designed from: run it, open the "By dependency"
tab, and type the dependency's name into the filter box.

```sh
./mill io.eleven19.mill.github.dependency.graph.Graph/report
```

If it is missing, two things to check before assuming a bug. First, try a
wider scope — `--scope all`. Second, remember that a dependency you get only
through a `moduleDeps` edge belongs to *that* module's manifest, not to this
one (see Limitations).

### Cover build-time dependencies too

Lombok, annotation processors and provided-scope APIs are invisible by
default because they are `compileMvnDeps`:

```yml
    - name: Submit dependency graph
      env:
        GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
      run: ./mill io.eleven19.mill.github.dependency.graph.Graph/submit --scope all
```

### Submit only your published modules

In a repo that also holds sample projects or build tooling you do not ship:

```yml
      run: >-
        ./mill io.eleven19.mill.github.dependency.graph.Graph/submit
        --modules 'core.__' --modules 'server.__'
```

Read this as an allow-list: anything not named is not covered. Prefer it over
a long list of exclusions when the set you *do* want is smaller and more
stable than the set you don't.

### Submit everything except a subtree

The mirror image, for when the set you want to skip is the smaller one:

```yml
      run: >-
        ./mill io.eleven19.mill.github.dependency.graph.Graph/submit
        --exclude-modules 'examples.__'
```

### Compare two scopes to see what a setting actually buys you

Before committing to `--scope all` in CI, find out what it adds:

```sh
set -euo pipefail
for scope in runtime all; do
  ./mill io.eleven19.mill.github.dependency.graph.Graph/generate --scope "$scope"
  python3 -c "
import json
payload = json.load(open('out/io.eleven19.mill.github.dependency.graph.Graph/generate.json'))
manifests = payload.get('value', payload)
print('$scope:', sum(len(m['resolved']) for m in manifests.values()), 'dependencies')
"
done
```

The `set -euo pipefail` matters: without it, a failed `generate` leaves the
previous run's JSON in place and the loop reports stale numbers as though they
were fresh.

`all` should always report at least as many as `runtime`. If it reports fewer,
that is a bug worth [opening an issue](https://github.com/Eleven19/mill-github-dependency-graph/issues)
about.

### Different settings for scheduled and per-push runs

A cheap graph on every push, a thorough one nightly:

```yml
on:
  push:
    branches: [main]
  schedule:
    - cron: '0 3 * * *'

jobs:
  submit-dependency-graph:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
    - uses: actions/checkout@v7
    - uses: actions/setup-java@v5
      with:
        distribution: 'temurin'
        java-version: '21'
    - name: Submit dependency graph
      env:
        GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        SCOPE: ${{ github.event_name == 'schedule' && 'all' || 'runtime' }}
      run: ./mill io.eleven19.mill.github.dependency.graph.Graph/submit --scope "$SCOPE"
```

> **One caveat if you do this.** The correlator is built from your workflow and
> job name, so both runs here write to the *same* graph and each supersedes the
> other. That is fine — it just means the graph reflects whichever ran last,
> not the union. Put the two in separate jobs if you want them tracked apart.

## How does this work?

The plugin works in a few steps:

1. Gather all the `JavaModule`s in your build, then apply `--modules` and
   `--exclude-modules`
2. Gather all direct and transitive dependencies of those modules, at the
   chosen scope
3. Create a tree-like structure of these dependencies using coursier's
   `DependencyTree` functionality
4. Map this structure to a
   [`DependencySnapshot`](domain/src/io/eleven19/github/dependency/graph/domain/DependencySnapshot.scala),
   which is what the GitHub API expects
5. POST the snapshot to GitHub's Dependency Submission API

Each module is resolved through its own synthetic coursier project rather than
through a loose list of its dependencies. That is what lets versions coming
from `depManagement` or a BOM resolve at all, and report the version your
build actually settled on. The scaladoc on
[`GraphScope`](plugin/src/io/eleven19/mill/github/dependency/graph/GraphScope.scala)
and [`ScopedRoots`](plugin/src/io/eleven19/mill/github/dependency/graph/ScopedRoots.scala)
spells out what each scope resolves and why.

### Limitations

- **`moduleDeps` do not appear in the depending module's manifest.** A
  manifest's roots are that module's own `mvnDeps`, `runMvnDeps` and
  `compileMvnDeps`. If `app` depends on `lib`, and `lib` brings in Jackson,
  Jackson appears under `lib`'s manifest, not under `app`'s. Nothing is lost
  from the graph as a whole; it is just filed where you may not expect.
- **`job.html_url` is never populated.** The environment variable lookup for
  it is misspelled, so the link back to the Actions run is always absent from
  the submitted snapshot.
- **The GitHub UI shows less than the plugin sends.** A lot of dependencies
  aren't linked back to the repositories where they are located, some may be
  wrongly linked, and much of the information the plugin provides (like direct
  vs indirect) isn't displayed at all. Much of this is bugs or limitations on
  the GitHub side. You can follow some conversation on this
  [here](https://github.com/orgs/community/discussions/19492).

## Attribution

This project is based on [mill-github-dependency-graph](https://github.com/ckipp01/mill-github-dependency-graph)
by [Chris Kipp](https://www.chris-kipp.io), licensed under the Apache License 2.0.
See the [NOTICE](NOTICE) file for details.
