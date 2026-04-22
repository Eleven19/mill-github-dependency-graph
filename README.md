# Mill GitHub Dependency Graph

[![Maven Central](https://img.shields.io/maven-central/v/io.eleven19.mill-github-dependency-graph/mill-github-dependency-graph_3)](https://central.sonatype.com/artifact/io.eleven19.mill-github-dependency-graph/mill-github-dependency-graph_3)

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

## Installation

Add the plugin as a build dependency in your `build.mill.yaml`:

```yaml
mill-build:
  mvnDeps:
    - "io.eleven19.mill-github-dependency-graph::mill-github-dependency-graph:0.0.1"
```

### Maven Coordinates

| | Group ID | Artifact ID | Version |
|---|---|---|---|
| **Plugin** | `io.eleven19.mill-github-dependency-graph` | `mill-github-dependency-graph_3` | `0.0.1` |
| **Domain** | `io.eleven19.mill-github-dependency-graph` | `github-dependency-graph-domain_3` | `0.0.1` |

Browse on Sonatype Central:
[mill-github-dependency-graph](https://central.sonatype.com/artifact/io.eleven19.mill-github-dependency-graph/mill-github-dependency-graph_3)
| [github-dependency-graph-domain](https://central.sonatype.com/artifact/io.eleven19.mill-github-dependency-graph/github-dependency-graph-domain_3)

## Usage

### Generate locally

To preview the dependency manifests for your project without submitting:

```sh
./mill io.eleven19.mill.github.dependency.graph.Graph/generate
```

### Submit via GitHub Actions

To automatically submit your dependency graph on every push to `main`, add
this workflow to your project:

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
      run: ./mill io.eleven19.mill.github.dependency.graph.Graph/submit
```

After you submit your graph you'll be able to [view your
dependencies](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/exploring-the-dependencies-of-a-repository#viewing-the-dependency-graph).

## How does this work?

The plugin works in a few steps:

1. Gather all the `JavaModule`s in your build
2. Gather all direct and transitive dependencies of those modules
3. Create a tree-like structure of these dependencies using coursier's
   `DependencyTree` functionality
4. Map this structure to a
   [`DependencySnapshot`](domain/src/io/eleven19/github/dependency/graph/domain/DependencySnapshot.scala),
   which is what the GitHub API expects
5. POST the snapshot to GitHub's Dependency Submission API

### Limitations

You'll notice when using this that a lot of dependencies aren't linked back to
the repositories where they are located, some may be wrongly linked, and much of
the information the plugin is providing (like direct vs indirect) isn't actually
displayed in the UI. Much of this is either bugs or limitations on the GitHub UI
side. You can follow some conversation on this
[here](https://github.com/orgs/community/discussions/19492).

## Attribution

This project is based on [mill-github-dependency-graph](https://github.com/ckipp01/mill-github-dependency-graph)
by [Chris Kipp](https://www.chris-kipp.io), licensed under the Apache License 2.0.
See the [NOTICE](NOTICE) file for details.
