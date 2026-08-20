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

  object testBuild extends TestRootModule {
    object foo extends ScalaModule {
      def scalaVersion = scala3
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
  }
}
