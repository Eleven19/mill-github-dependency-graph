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
        // `requireSuccess` already throws if this failed.
        Fixtures.requireSuccess(Fixtures.generate(tester))
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
            "everyScope",
            "declaresCompile"
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

    test("scope flags") {

      test("--scope compile omits the runtime-scoped transitive") {
        Fixtures.withFixture("dependency-shapes") { tester =>
          Fixtures.requireSuccess(
            Fixtures.generate(tester, "--scope", "compile")
          )
          val manifests = Fixtures.manifests(tester)
          val reported = manifests("everyScope")
          assert(
            !reported.contains(
              "org.junit.platform:junit-platform-suite-commons:1.11.4"
            )
          )
          assert(!reported.exists(_.startsWith("org.slf4j:slf4j-simple:")))
          assert(
            reported.contains(
              "org.junit.platform:junit-platform-suite-api:1.11.4"
            )
          )
          // This repo's own build has no BOMs, so it can never dogfood the
          // BOM-version path -- only the fixture can. Asserted here too, not
          // just at the default scope, so a scope flag cannot regress it.
          assert(
            manifests("viaBom").contains(
              "com.fasterxml.jackson.core:jackson-databind:2.18.2"
            )
          )
        }
      }

      test("--scope all adds compileMvnDeps without losing runtime") {
        Fixtures.withFixture("dependency-shapes") { tester =>
          Fixtures.requireSuccess(Fixtures.generate(tester, "--scope", "all"))
          val manifests = Fixtures.manifests(tester)
          val reported = manifests("everyScope")
          assert(reported.contains("org.projectlombok:lombok:1.18.36"))
          assert(reported.contains("org.slf4j:slf4j-simple:2.0.16"))
          assert(
            reported.contains(
              "org.junit.platform:junit-platform-suite-commons:1.11.4"
            )
          )
          assert(
            manifests("viaBom").contains(
              "com.fasterxml.jackson.core:jackson-databind:2.18.2"
            )
          )
        }
      }

      test("a module's own declaration is honoured with no flag") {
        Fixtures.withFixture("dependency-shapes") { tester =>
          Fixtures.requireSuccess(Fixtures.generate(tester))
          val reported = Fixtures.manifests(tester)
          // `declaresCompile` asked for compile...
          assert(
            !reported("declaresCompile").contains(
              "org.junit.platform:junit-platform-suite-commons:1.11.4"
            )
          )
          // ...while its neighbour, same dependency, stayed at runtime.
          assert(
            reported("runtimeScoped").contains(
              "org.junit.platform:junit-platform-suite-commons:1.11.4"
            )
          )
        }
      }

      test("a passed flag beats the module's declaration") {
        Fixtures.withFixture("dependency-shapes") { tester =>
          Fixtures.requireSuccess(
            Fixtures.generate(tester, "--scope", "runtime")
          )
          val reported = Fixtures.manifests(tester)("declaresCompile")
          assert(
            reported.contains(
              "org.junit.platform:junit-platform-suite-commons:1.11.4"
            )
          )
        }
      }

      test("an unknown scope fails and names the valid values") {
        Fixtures.withFixture("dependency-shapes") { tester =>
          val result = Fixtures.generate(tester, "--scope", "nonsense")
          assert(!result.isSuccess)
          val output = result.out + result.err
          assert(output.contains("nonsense"))
          assert(output.contains("compile"))
          assert(output.contains("runtime"))
          assert(output.contains("all"))
        }
      }
    }
  }
}
