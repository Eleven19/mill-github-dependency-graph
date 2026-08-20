package io.eleven19.mill.github.dependency.graph

import mill._
import mill.api.Discover
import mill.scalalib._
import mill.testkit.{TestRootModule, UnitTester}
import utest._

/** Exercises `GraphModule.generate`'s `--output` parameter end to end, through
  * a real `UnitTester` evaluator, rather than testing the file-writing logic
  * in isolation.
  *
  * `generate` is defined on `GraphModule`, materialised as the `Graph`
  * `ExternalModule`. Calling `Graph.generate(eval.evaluator, ...)` and
  * running the returned `Task.Command` through `eval.apply` mirrors exactly
  * how Mill's CLI invokes it in a real build: the evaluator's root module is
  * the build under inspection (`testBuild` here), not `Graph` itself.
  */
object OutputTests extends TestSuite {

  private val scala3 = "3.3.4"

  // `slf4j-simple` is filed as a `runMvnDeps`, which coursier resolves under
  // the runtime configuration only -- so it is present in `foo`'s graph at
  // `GraphScope.Runtime` and absent at `GraphScope.Compile`. That difference
  // is what the `report` scope test below turns into an assertion.
  private val slf4jVersion = "2.0.16"

  object testBuild extends TestRootModule {
    object foo extends ScalaModule {
      def scalaVersion = scala3
      override def runMvnDeps = Seq(mvn"org.slf4j:slf4j-simple:$slf4jVersion")
    }

    lazy val millDiscover = Discover[this.type]
  }

  val tests = Tests {

    test("--output <file> writes that file as JSON keyed by module names") {
      UnitTester(testBuild, null).scoped { eval =>
        val destination = eval.outPath / "manifest.json"

        eval.apply(
          Graph.generate(eval.evaluator, output = Some(destination.toString))
        ) match {
          case Right(result) =>
            assert(os.exists(destination))

            // Parses as JSON, and the keys are the module names generate()
            // itself returned -- not just "some file got written".
            val parsed = ujson.read(os.read(destination))
            assert(parsed.obj.keySet == result.value.keySet)
            assert(parsed.obj.keySet == Set("foo"))
          case Left(failure) =>
            throw new java.lang.AssertionError(s"generate failed: $failure")
        }
      }
    }

    test(
      "a relative --output resolves against the workspace root, not the caller's raw process cwd"
    ) {
      UnitTester(testBuild, null).scoped { eval =>
        // `UnitTester.scoped` sets `BuildCtx.workspaceRoot` to `testBuild`'s
        // own module dir for the scope of this block. That is the directory
        // a relative `--output` must resolve against; `os.pwd` as observed
        // from outside a running task -- e.g. from this test body, before
        // `eval.apply` starts one -- is a different directory (Mill's own
        // sandbox working directory for this test worker), so if generate()
        // resolved the relative path against the wrong base entirely (say,
        // a raw filesystem cwd captured before Mill's own workspace-relative
        // machinery is in play) the file would land there instead.
        val relative = "generated/manifest.json"
        val expected = testBuild.moduleDir / "generated" / "manifest.json"
        val underRawProcessCwd = os.pwd / "generated" / "manifest.json"
        assert(testBuild.moduleDir != os.pwd)

        try
          eval.apply(
            Graph.generate(eval.evaluator, output = Some(relative))
          ) match {
            case Right(_) =>
              assert(os.exists(expected))
              assert(ujson.read(os.read(expected)).obj.keySet == Set("foo"))
              assert(!os.exists(underRawProcessCwd))
            case Left(failure) =>
              throw new java.lang.AssertionError(s"generate failed: $failure")
          }
        finally os.remove.all(os.pwd / "generated")
      }
    }

    test("--output into a directory that does not exist creates it") {
      UnitTester(testBuild, null).scoped { eval =>
        val destination = eval.outPath / "nested" / "deeper" / "manifest.json"
        assert(!os.exists(destination / os.up))

        eval.apply(
          Graph.generate(eval.evaluator, output = Some(destination.toString))
        ) match {
          case Right(_) =>
            assert(os.exists(destination))
            assert(ujson.read(os.read(destination)).obj.keySet == Set("foo"))
          case Left(failure) =>
            throw new java.lang.AssertionError(s"generate failed: $failure")
        }
      }
    }

    test(
      "--output naming an existing directory fails, blaming --output and the path"
    ) {
      UnitTester(testBuild, null).scoped { eval =>
        val destination = eval.outPath / "already-a-directory"
        os.makeDir.all(destination)

        eval.apply(
          Graph.generate(eval.evaluator, output = Some(destination.toString))
        ) match {
          case Left(failure) =>
            // Anchored on the guard's own wording plus the path, not merely
            // "it failed": os.write.over onto a directory throws too, with an
            // unrelated message, so a weaker assertion here would still pass
            // if the explicit directory check were deleted.
            val message = failure.toString
            assert(message.contains("--output names an existing directory"))
            assert(message.contains(destination.toString))
          case Right(_) =>
            throw new java.lang.AssertionError(
              "expected generate to fail when --output names a directory"
            )
        }
      }
    }

    test(
      "no --output returns the same manifests and writes nothing at the earlier path"
    ) {
      UnitTester(testBuild, null).scoped { eval =>
        // Same literal path the first test wrote its output to. Since each
        // `UnitTester(testBuild, null)` wipes and recreates `testBuild`'s
        // module dir, the file itself is gone by now; write a sentinel there
        // ourselves so a regression that writes output by default -- even
        // without `--output` -- has something to clobber.
        val sentinelPath = eval.outPath / "manifest.json"
        os.write.over(sentinelPath, "sentinel", createFolders = true)

        eval.apply(Graph.generate(eval.evaluator)) match {
          case Right(result) =>
            assert(result.value.keySet == Set("foo"))
            assert(os.read(sentinelPath) == "sentinel")
          case Left(failure) =>
            throw new java.lang.AssertionError(s"generate failed: $failure")
        }
      }
    }

    test("report") {

      test(
        "--output <file> writes a self-contained HTML report naming a module"
      ) {
        UnitTester(testBuild, null).scoped { eval =>
          val destination = eval.outPath / "report.html"

          eval.apply(
            Graph.report(eval.evaluator, output = Some(destination.toString))
          ) match {
            case Right(result) =>
              assert(os.exists(destination))
              val html = os.read(destination)
              assert(html.startsWith("<!DOCTYPE html>"))
              assert(html.contains("foo"))

              // report() must hand back the very path it wrote, not merely
              // write to it.
              assert(result.value == destination.toString)
            case Left(failure) =>
              throw new java.lang.AssertionError(s"report failed: $failure")
          }
        }
      }

      test(
        "no --output still writes somewhere, and the returned path exists"
      ) {
        UnitTester(testBuild, null).scoped { eval =>
          eval.apply(Graph.report(eval.evaluator)) match {
            case Right(result) =>
              val written = os.Path(result.value)
              assert(os.exists(written))
              assert(os.read(written).startsWith("<!DOCTYPE html>"))
            case Left(failure) =>
              throw new java.lang.AssertionError(s"report failed: $failure")
          }
        }
      }

      test(
        "--scope compile omits a runMvnDeps entry that --scope runtime includes"
      ) {
        // Proves report() really funnels scope through generate()'s own
        // resolution rather than resolving the graph a second, independent
        // way: if report ever stopped calling generate and resolved on its
        // own, nothing here would force the two scopes to differ.
        UnitTester(testBuild, null).scoped { eval =>
          val compileDestination = eval.outPath / "compile-report.html"
          val runtimeDestination = eval.outPath / "runtime-report.html"

          eval.apply(
            Graph.report(
              eval.evaluator,
              scope = Some("compile"),
              output = Some(compileDestination.toString)
            )
          ) match {
            case Right(_) =>
              assert(!os.read(compileDestination).contains("slf4j-simple"))
            case Left(failure) =>
              throw new java.lang.AssertionError(s"report failed: $failure")
          }

          eval.apply(
            Graph.report(
              eval.evaluator,
              scope = Some("runtime"),
              output = Some(runtimeDestination.toString)
            )
          ) match {
            case Right(_) =>
              assert(os.read(runtimeDestination).contains("slf4j-simple"))
            case Left(failure) =>
              throw new java.lang.AssertionError(s"report failed: $failure")
          }
        }
      }
    }
  }
}
