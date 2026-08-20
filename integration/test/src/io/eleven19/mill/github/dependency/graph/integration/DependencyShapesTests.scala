package io.eleven19.mill.github.dependency.graph.integration

import utest._

/** The dependency shapes the plugin has had to get right, exercised through a
  * real Mill process against the published artifact.
  *
  * Everything here passes on `main`. A failure means the harness is wrong,
  * not that a feature is missing.
  */
object DependencyShapesTests extends TestSuite {

  val tests = Tests {

    test("a consumer build can load the published plugin") {
      // Also covers issue #4: the `_mill1_3` coordinate has to resolve from a
      // `//|` header, which no unit test can check.
      Fixtures.withFixture("dependency-shapes") { tester =>
        val result = Fixtures.requireSuccess(Fixtures.generate(tester))
        assert(result.isSuccess)
      }
    }

    test("every fixture module gets a manifest") {
      Fixtures.withFixture("dependency-shapes") { tester =>
        Fixtures.requireSuccess(Fixtures.generate(tester))
        val reported = Fixtures.manifests(tester).keySet
        assert(
          reported == Set(
            "viaDepManagement",
            "viaBom",
            "bomOverridesTransitive",
            "runtimeScoped",
            "everyScope"
          )
        )
      }
    }

    test("issue #3: a depManagement version reaches the manifest") {
      Fixtures.withFixture("dependency-shapes") { tester =>
        Fixtures.requireSuccess(Fixtures.generate(tester))
        val reported = Fixtures.manifests(tester)("viaDepManagement")
        assert(reported.contains("dev.zio:zio-test_3:2.1.14"))
      }
    }

    test("issue #3: a BOM version reaches the manifest") {
      Fixtures.withFixture("dependency-shapes") { tester =>
        Fixtures.requireSuccess(Fixtures.generate(tester))
        val reported = Fixtures.manifests(tester)("viaBom")
        assert(
          reported.contains(
            "com.fasterxml.jackson.core:jackson-databind:2.18.2"
          )
        )
      }
    }

    test("a BOM-raised transitive is reported at the BOM's version") {
      Fixtures.withFixture("dependency-shapes") { tester =>
        Fixtures.requireSuccess(Fixtures.generate(tester))
        val reported = Fixtures.manifests(tester)("bomOverridesTransitive")
        assert(
          reported.contains("com.fasterxml.jackson.core:jackson-core:2.18.2")
        )
        assert(
          !reported.contains("com.fasterxml.jackson.core:jackson-core:2.15.0")
        )
      }
    }

    test("issue #12: a runtime-scoped transitive reaches the manifest") {
      Fixtures.withFixture("dependency-shapes") { tester =>
        Fixtures.requireSuccess(Fixtures.generate(tester))
        val reported = Fixtures.manifests(tester)("runtimeScoped")
        assert(
          reported.contains(
            "org.junit.platform:junit-platform-suite-commons:1.11.4"
          )
        )
      }
    }
  }
}
