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
      val reported = Fixtures.defaultManifests("dependency-shapes").keySet
      assert(
        reported == Set(
          "viaDepManagement",
          "viaBom",
          "bomOverridesTransitive",
          "runtimeScoped",
          "everyScope",
          "declaresCompile",
          "internalLib",
          "dependsOnInternal",
          "jsLib",
          "jsLibDepender"
        )
      )
    }

    test("issue #3: a depManagement version reaches the manifest") {
      val reported =
        Fixtures.defaultManifests("dependency-shapes")("viaDepManagement")
      assert(reported.contains("dev.zio:zio-test_3:2.1.14"))
    }

    test("issue #3: a BOM version reaches the manifest") {
      val reported = Fixtures.defaultManifests("dependency-shapes")("viaBom")
      assert(
        reported.contains(
          "com.fasterxml.jackson.core:jackson-databind:2.18.2"
        )
      )
    }

    test("a BOM-raised transitive is reported at the BOM's version") {
      val reported =
        Fixtures.defaultManifests("dependency-shapes")("bomOverridesTransitive")
      assert(
        reported.contains("com.fasterxml.jackson.core:jackson-core:2.18.2")
      )
      assert(
        !reported.contains("com.fasterxml.jackson.core:jackson-core:2.15.0")
      )
    }

    test("issue #12: a runtime-scoped transitive reaches the manifest") {
      val reported =
        Fixtures.defaultManifests("dependency-shapes")("runtimeScoped")
      assert(
        reported.contains(
          "org.junit.platform:junit-platform-suite-commons:1.11.4"
        )
      )
    }

    test("dependencies reached through moduleDeps") {

      test("reach the depending module's manifest, marked indirect") {
        // The gap this closes was found in this repo's own graph: `plugin`
        // depends on `report`, `report` depends on scalatags, and `plugin`'s
        // manifest denied it. A consumer of the published artifact gets it.
        val reported =
          Fixtures.defaultManifests("dependency-shapes")("dependsOnInternal")
        assert(reported.contains("com.lihaoyi:sourcecode_3:0.4.2"))
      }

      test("--no-module-deps leaves them out") {
        Fixtures.withFixture("dependency-shapes") { tester =>
          Fixtures.requireSuccess(
            Fixtures.generate(tester, "--no-module-deps")
          )
          val reported = Fixtures.manifests(tester)("dependsOnInternal")
          assert(!reported.contains("com.lihaoyi:sourcecode_3:0.4.2"))
          // The module's own dependencies are untouched by the flag.
          assert(
            Fixtures
              .manifests(tester)("internalLib")
              .contains("com.lihaoyi:sourcecode_3:0.4.2")
          )
        }
      }
    }

    test("issue #32: Scala.js moduleDeps with a mismatched binder") {

      test("the depender resolves without aborting") {
        // Before the fix, walking jsLib's versionless coords with
        // jsLibDepender's binder looked up sourcecode_3 in a resolution
        // that held sourcecode_sjs1_3.
        val reported =
          Fixtures.defaultManifests("dependency-shapes")("jsLibDepender")
        assert(reported.contains("com.lihaoyi:sourcecode_sjs1_3:0.4.2"))
        assert(!reported.contains("com.lihaoyi:sourcecode_3:0.4.2"))
      }

      test("the library itself still reports the Scala.js coordinate") {
        val reported =
          Fixtures.defaultManifests("dependency-shapes")("jsLib")
        assert(reported.contains("com.lihaoyi:sourcecode_sjs1_3:0.4.2"))
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
        val reported = Fixtures.defaultManifests("dependency-shapes")
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

    test("--output") {

      test("generate --output writes the manifests as JSON") {
        Fixtures.withFixture("dependency-shapes") { tester =>
          val destination = tester.workspacePath / "generate-report.json"
          Fixtures.requireSuccess(
            Fixtures.generate(tester, "--output", destination.toString)
          )
          val content = os.read(destination)
          // Fails loudly, with the offending content, if `--output` wrote
          // something that is not valid JSON -- rather than a downstream
          // assertion failing on a symptom of that.
          val parsed =
            try ujson.read(content)
            catch {
              case e: Exception =>
                throw new java.lang.AssertionError(
                  s"--output did not write valid JSON: $content",
                  e
                )
            }
          assert(parsed.obj.contains("viaDepManagement"))
          assert(content.contains("dev.zio:zio-test_3:2.1.14"))
        }
      }

      test("submit --output writes the manifests before it tries to POST") {
        // `submit` gained `--output` with no coverage at any tier. Twice on
        // this project a forwarded parameter has been silently dropped with
        // every test still green, so it is worth a case.
        //
        // The POST is deliberately sent nowhere. An earlier version of this
        // test assumed `submit` could not succeed for want of credentials —
        // true on a laptop, false in CI, where Actions supplies every
        // `GITHUB_*` variable and `ci.yml` supplies the token. It really
        // submitted a snapshot. See `submitWithoutReachingGitHub`.
        Fixtures.withFixture("dependency-shapes") { tester =>
          val destination = tester.workspacePath / "submitted.json"
          val result =
            Fixtures.submitWithoutReachingGitHub(
              tester,
              "--output",
              destination.toString
            )

          assert(!result.isSuccess)
          assert(os.exists(destination))
          val parsed = ujson.read(os.read(destination))
          assert(parsed.obj.contains("viaDepManagement"))
        }
      }

      test("report --output writes a self-contained HTML page") {
        Fixtures.withFixture("dependency-shapes") { tester =>
          val destination = tester.workspacePath / "graph-report.html"
          Fixtures.requireSuccess(
            Fixtures.report(tester, "--output", destination.toString)
          )
          val content = os.read(destination)
          assert(content.startsWith("<!DOCTYPE html>"))
          assert(content.contains("dev.zio:zio-test_3:2.1.14"))
        }
      }

      test("the rendered report carries no http:// or https:// reference") {
        // The unit tier (`report/test`) already asserts this on markup
        // rendered in-process. Asserting it again here, on the file the
        // real command wrote after publish, resolve and subprocess, is what
        // would actually catch a stray asset reference: nothing upstream of
        // this assertion is stubbed.
        Fixtures.withFixture("dependency-shapes") { tester =>
          val destination = tester.workspacePath / "graph-report-urls.html"
          Fixtures.requireSuccess(
            Fixtures.report(tester, "--output", destination.toString)
          )
          val content = os.read(destination)
          assert(!content.contains("http://"))
          assert(!content.contains("https://"))
        }
      }
    }
  }
}
