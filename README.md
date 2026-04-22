# Mill GitHub Dependency Graph

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

- Mill 1.x (1.1.5+)
- Make sure in your repo settings the Dependency Graph feature is enabled as
    well as Dependabot Alerts if you'd like them. (Settings -> Code security and
    analysis)

## Quick Start

Add the plugin to your `build.mill.yaml`:

```yaml
mill-build:
  mvnDeps:
    - "io.eleven19.mill-github-dependency-graph::mill-github-dependency-graph:0.1.0"
```

Then you can generate the dependency graph locally:

```sh
mill io.eleven19.mill.github.dependency.graph.Graph/generate
```

Or set up a GitHub Actions workflow to submit the graph automatically:

```yml
name: github-dependency-graph

on:
  push:
    branches:
      - main

jobs:
  submit-dependency-graph:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v4
      with:
        distribution: 'temurin'
        java-version: '21'
    - name: Submit dependency graph
      env:
        GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
      run: mill io.eleven19.mill.github.dependency.graph.Graph/submit
```

After you submit your graph you'll be able to [view your
dependencies](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/exploring-the-dependencies-of-a-repository#viewing-the-dependency-graph).

## How does this work?

The general idea is that the plugin works in a few steps:

1. Gather all the modules in your build
2. Gather all direct and transitive dependencies of those modules
3. Create a tree-like structure of these dependencies. We piggy back off
   coursier for this and use its `DependencyTree` functionality.
4. We map this structure to that of a [`DependencySnapshot`](domain/src/io/eleven19/github/dependency/graph/domain/DependencySnapshot.scala), which is what GitHub understands
5. We post this data to GitHub.

You can use the `generate` task to see what the
[`Manifest`s](domain/src/io/eleven19/github/dependency/graph/domain/Manifest.scala)
look like locally for your project, which are the main part of the
`DependencySnapshot`.

### Limitation

You'll notice when using this that a lot of dependencies aren't linked back to
the repositories where they are located, some may be wrongly linked, and much of
the information the plugin is providing (like direct vs indirect) isn't actually
displayed in the UI. Much of this is either bugs or limitations on the GitHub UI
side. You can follow some conversation on this [here](https://github.com/orgs/community/discussions/19492).

## Attribution

This project is based on [mill-github-dependency-graph](https://github.com/ckipp01/mill-github-dependency-graph)
by [Chris Kipp](https://www.chris-kipp.io), licensed under the Apache License 2.0.
See the [NOTICE](NOTICE) file for details.
