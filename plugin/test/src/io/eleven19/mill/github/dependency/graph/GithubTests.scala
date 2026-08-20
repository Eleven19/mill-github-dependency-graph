package io.eleven19.mill.github.dependency.graph

import utest._

object GithubTests extends TestSuite {

  private val serverUrl = "https://github.com"
  private val repository = "Eleven19/mill-github-dependency-graph"

  val tests = Tests {

    test("html_url") {

      test("links back to the Actions run") {
        // This is the assertion that catches the original bug: the lookup
        // used a literal `$` in the variable name, so it matched nothing and
        // every submitted snapshot lost its link to the run that produced it.
        val env = Map(
          "GITHUB_SERVER_URL" -> serverUrl,
          "GITHUB_REPOSITORY" -> repository
        )
        assert(
          Github.htmlUrl(env.get, "123") ==
            Some(s"$serverUrl/$repository/actions/runs/123")
        )
      }

      test("is absent without a server url") {
        val env = Map("GITHUB_REPOSITORY" -> repository)
        assert(Github.htmlUrl(env.get, "123").isEmpty)
      }

      test("is absent without a repository") {
        val env = Map("GITHUB_SERVER_URL" -> serverUrl)
        assert(Github.htmlUrl(env.get, "123").isEmpty)
      }

      test("is absent outside Actions entirely") {
        assert(Github.htmlUrl(Map.empty[String, String].get, "123").isEmpty)
      }
    }
  }
}
