package io.eleven19.mill.github.dependency.graph.integration

import utest._

object ModuleSelectionIntegrationTests extends TestSuite {

  private val everyModule =
    Set("app", "app.test", "lib", "lib.test", "tooling.sub")

  val tests = Tests {

    test("with no flags") {

      test("covers every JavaModule") {
        Fixtures.withFixture("module-selection") { tester =>
          Fixtures.requireSuccess(Fixtures.generate(tester))
          assert(Fixtures.manifests(tester).keySet == everyModule)
        }
      }

      test("does not cover the plain container module") {
        // `tooling` is a `Module`, not a `JavaModule`, so it has no
        // dependencies to report and must not appear.
        Fixtures.withFixture("module-selection") { tester =>
          Fixtures.requireSuccess(Fixtures.generate(tester))
          assert(!Fixtures.manifests(tester).keySet.contains("tooling"))
        }
      }
    }

    test("--exclude-modules") {

      test("drops the test modules and keeps the rest") {
        Fixtures.withFixture("module-selection") { tester =>
          Fixtures.requireSuccess(
            Fixtures.generate(tester, "--exclude-modules", "__.test")
          )
          assert(
            Fixtures.manifests(tester).keySet ==
              Set("app", "lib", "tooling.sub")
          )
        }
      }

      test("says how much it dropped") {
        // A filter that shrinks the graph silently reads in the GitHub UI
        // exactly like a project with few dependencies.
        Fixtures.withFixture("module-selection") { tester =>
          val result = Fixtures.requireSuccess(
            Fixtures.generate(tester, "--exclude-modules", "__.test")
          )
          val output = result.out + result.err
          assert(output.contains("submitting 3 of 5 modules"))
          assert(output.contains("2 excluded by selector"))
        }
      }
    }

    test("--modules") {

      test("narrows to the named subtree") {
        Fixtures.withFixture("module-selection") { tester =>
          Fixtures.requireSuccess(
            Fixtures.generate(tester, "--modules", "app.__")
          )
          assert(
            Fixtures.manifests(tester).keySet == Set("app", "app.test")
          )
        }
      }

      test("exclusion applies after inclusion") {
        Fixtures.withFixture("module-selection") { tester =>
          Fixtures.requireSuccess(
            Fixtures.generate(
              tester,
              "--modules",
              "app.__",
              "--exclude-modules",
              "app.test"
            )
          )
          assert(Fixtures.manifests(tester).keySet == Set("app"))
        }
      }
    }

    test("an unknown selector fails rather than selecting nothing") {
      Fixtures.withFixture("module-selection") { tester =>
        val result =
          Fixtures.generate(tester, "--exclude-modules", "nosuchmodule")
        assert(!result.isSuccess)
      }
    }
  }
}
