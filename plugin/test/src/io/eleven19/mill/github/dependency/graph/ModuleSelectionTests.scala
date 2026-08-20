package io.eleven19.mill.github.dependency.graph

import mill._
import mill.api.Discover
import mill.scalalib._
import mill.testkit.{TestRootModule, UnitTester}
import utest._

object ModuleSelectionTests extends TestSuite {

  private val scala3 = "3.3.4"

  object selectionBuild extends TestRootModule {
    object app extends ScalaModule {
      def scalaVersion = scala3
      object test extends ScalaTests with TestModule.Utest
    }
    object lib extends ScalaModule {
      def scalaVersion = scala3
      object test extends ScalaTests with TestModule.Utest
    }
    lazy val millDiscover = Discover[this.type]
  }

  private val modules: Seq[mill.javalib.JavaModule] =
    Seq(
      selectionBuild.app,
      selectionBuild.app.test,
      selectionBuild.lib,
      selectionBuild.lib.test
    )

  private def named(selected: Seq[mill.javalib.JavaModule]): Set[String] =
    selected.map(_.toString).toSet

  val tests = Tests {

    test("owningModules") {

      test("maps a task segment back to its module") {
        val owning = ModuleSelection.owningModules(
          modules,
          Seq(List("app", "compile"))
        )
        assert(owning == Set(List("app")))
      }

      test("prefers the longest module prefix") {
        // `app.test.compile` names `app.test`, not its parent `app`. Getting
        // this wrong would make `--exclude-modules '__.test'` drop the whole
        // build.
        val owning = ModuleSelection.owningModules(
          modules,
          Seq(List("app", "test", "compile"))
        )
        assert(owning == Set(List("app", "test")))
      }

      test("ignores segments that name no known module") {
        val owning = ModuleSelection.owningModules(
          modules,
          Seq(List("nowhere", "compile"))
        )
        assert(owning == Set.empty)
      }
    }

    test("select") {

      test("keeps everything when nothing is asked for") {
        val selected = ModuleSelection.select(modules, None, Set.empty)
        assert(named(selected) == Set("app", "app.test", "lib", "lib.test"))
      }

      test("narrows to the include set") {
        val selected = ModuleSelection.select(
          modules,
          Some(Set(List("app"), List("app", "test"))),
          Set.empty
        )
        assert(named(selected) == Set("app", "app.test"))
      }

      test("subtracts the exclude set") {
        val selected = ModuleSelection.select(
          modules,
          None,
          Set(List("app", "test"), List("lib", "test"))
        )
        assert(named(selected) == Set("app", "lib"))
      }

      test("exclude beats include") {
        val selected = ModuleSelection.select(
          modules,
          Some(Set(List("app"), List("app", "test"))),
          Set(List("app", "test"))
        )
        assert(named(selected) == Set("app"))
      }
    }

    test("resolveSegments") {

      test("resolves a recursive selector against a real evaluator") {
        UnitTester(selectionBuild, null).scoped { eval =>
          val resolved = mill.github.dependency.graph.internal.EvaluatorBridge
            .resolveSegments(eval.evaluator, Seq("__.test"))
          val owning = ModuleSelection.owningModules(modules, resolved)
          assert(owning == Set(List("app", "test"), List("lib", "test")))
        }
      }

      test("an unknown selector fails rather than resolving to nothing") {
        UnitTester(selectionBuild, null).scoped { eval =>
          val attempted = scala.util.Try {
            mill.github.dependency.graph.internal.EvaluatorBridge
              .resolveSegments(eval.evaluator, Seq("nosuchmodule"))
          }
          assert(attempted.isFailure)
        }
      }
    }
  }
}
