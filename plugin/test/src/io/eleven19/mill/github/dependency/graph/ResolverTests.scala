package io.eleven19.mill.github.dependency.graph

import io.eleven19.github.dependency.graph.domain
import mill._
import mill.api.Discover
import mill.scalalib._
import mill.testkit.{TestRootModule, UnitTester}
import utest._

/** Exercises `Resolver` against a real Mill build.
  *
  * `mill-testkit` gives us an `Evaluator` over the modules defined below, so
  * these run the plugin's own resolution code over modules that use
  * `depManagement` and `bomMvnDeps`, rather than over a stand-in.
  *
  * The bug this guards: resolving a module's `allMvnDeps()` as loose roots
  * reaches coursier with no version at all for any coordinate whose version
  * lives in `depManagement` or a BOM, and resolution fails with "No version
  * available in (,)". Resolving the module's own synthetic coursier project
  * instead carries both.
  */
object ResolverTests extends TestSuite {

  private val scala3 = "3.3.4"
  private val zioVersion = "2.1.14"
  private val jacksonBomVersion = "2.18.2"
  private val jacksonPinnedVersion = "2.15.0"
  private val junitPlatformVersion = "1.11.4"
  private val slf4jVersion = "2.0.16"
  private val lombokVersion = "1.18.36"

  object testBuild extends TestRootModule {

    /** The reported failure: the version is pinned once in `depManagement` and
      * omitted at the use site.
      */
    object viaDepManagement extends ScalaModule {
      def scalaVersion = scala3
      override def depManagement = super.depManagement() ++ Seq(
        mvn"dev.zio::zio-test:$zioVersion"
      )
      override def mvnDeps = Seq(mvn"dev.zio::zio-test")
    }

    /** The same shape, with the version supplied by a BOM instead. */
    object viaBom extends ScalaModule {
      def scalaVersion = scala3
      override def bomMvnDeps = Seq(
        mvn"com.fasterxml.jackson:jackson-bom:$jacksonBomVersion"
      )
      override def mvnDeps = Seq(
        mvn"com.fasterxml.jackson.core:jackson-databind"
      )
    }

    /** Every coordinate carries a version, so resolution succeeds either way.
      * The BOM bumps the transitive jackson artifacts, so only the reported
      * versions differ. This is the accuracy half of the bug: a snapshot built
      * without BOM data reports versions that are wrong rather than missing.
      */
    object bomOverridesTransitive extends ScalaModule {
      def scalaVersion = scala3
      override def bomMvnDeps = Seq(
        mvn"com.fasterxml.jackson:jackson-bom:$jacksonBomVersion"
      )
      override def mvnDeps = Seq(
        mvn"com.fasterxml.jackson.core:jackson-databind:$jacksonPinnedVersion"
      )
    }

    /** A transitive that only exists at runtime scope.
      *
      * `junit-platform-suite-engine` declares `junit-platform-suite-commons`
      * with `<scope>runtime</scope>`, so it is reachable through the runtime
      * configuration and not through the compile one.
      */
    object viaRuntimeScope extends ScalaModule {
      def scalaVersion = scala3
      override def mvnDeps = Seq(
        mvn"org.junit.platform:junit-platform-suite-engine:$junitPlatformVersion"
      )
    }

    /** Has a dependency in every scope that matters, so one module can show
      * what each setting includes and excludes.
      *
      * - `junit-platform-suite-engine` brings `junit-platform-suite-commons`
      *   at runtime scope and `junit-platform-suite-api` at compile scope.
      * - `slf4j-simple` is a `runMvnDeps`, filed by `coursierProject` under
      *   the runtime configuration only.
      * - `lombok` is a `compileMvnDeps`, filed under provided.
      */
    object everyScope extends ScalaModule {
      def scalaVersion = scala3
      override def mvnDeps = Seq(
        mvn"org.junit.platform:junit-platform-suite-engine:$junitPlatformVersion"
      )
      override def runMvnDeps = Seq(mvn"org.slf4j:slf4j-simple:$slf4jVersion")
      override def compileMvnDeps = Seq(
        mvn"org.projectlombok:lombok:$lombokVersion"
      )
    }

    lazy val millDiscover = Discover[this.type]
  }

  /** Resolves one module and builds its manifest, the same way
    * `GraphModule.generate` does.
    *
    * One module at a time on purpose: a module that fails to resolve should
    * fail its own tests only, rather than taking the unrelated scenarios down
    * with it and hiding what each was meant to show.
    */
  private def manifestOf(
      module: mill.javalib.JavaModule,
      scope: Option[GraphScope] = None
  ): domain.Manifest =
    UnitTester(testBuild, null).scoped { eval =>
      val moduleTrees =
        Resolver.resolveModuleTrees(eval.evaluator, Seq(module), scope)

      // `toManifest` needs a task context, so it runs inside a task of its own
      // rather than in the bare test body.
      val toManifest = Task.Anon(moduleTrees.head.toManifest())

      eval.apply(toManifest) match {
        case Right(result) => result.value
        case Left(failure) =>
          throw new java.lang.AssertionError(
            s"Building the manifest failed: $failure"
          )
      }
    }

  // Memoised, since each resolution downloads metadata.
  private lazy val viaDepManagement = manifestOf(testBuild.viaDepManagement)
  private lazy val viaBom = manifestOf(testBuild.viaBom)
  private lazy val bomOverridesTransitive = manifestOf(
    testBuild.bomOverridesTransitive
  )
  private lazy val viaRuntimeScope = manifestOf(testBuild.viaRuntimeScope)

  private def directOf(manifest: domain.Manifest): Set[String] =
    manifest.resolved.filter(_._2.isDirectDependency).keySet

  private def indirectOf(manifest: domain.Manifest): Set[String] =
    manifest.resolved.filterNot(_._2.isDirectDependency).keySet

  val tests = Tests {

    test("a version from depManagement") {

      test("resolves at all") {
        // Before the fix this module failed outright with
        // "No version available in (,)", so reaching a manifest is the
        // headline assertion.
        assert(viaDepManagement.resolved.nonEmpty)
      }

      test("reaches the manifest as the managed version") {
        assert(
          directOf(viaDepManagement).contains(s"dev.zio:zio-test_3:$zioVersion")
        )
      }

      test("brings its transitive dependencies in as indirect") {
        // Both reach the graph only through zio-test, whose own version came
        // from `depManagement`.
        val indirect = indirectOf(viaDepManagement)
        assert(indirect.contains(s"dev.zio:zio_3:$zioVersion"))
        assert(indirect.contains(s"dev.zio:zio-streams_3:$zioVersion"))
      }
    }

    test("a version from a BOM") {

      test("resolves at all") {
        assert(viaBom.resolved.nonEmpty)
      }

      test("reaches the manifest as the BOM's version") {
        assert(
          directOf(viaBom).contains(
            s"com.fasterxml.jackson.core:jackson-databind:$jacksonBomVersion"
          )
        )
      }
    }

    test("a BOM that overrides a transitive version") {

      test("reports the version the BOM settled on") {
        val all = bomOverridesTransitive.resolved.keySet
        assert(
          all.contains(
            s"com.fasterxml.jackson.core:jackson-core:$jacksonBomVersion"
          )
        )
        assert(
          all.contains(
            s"com.fasterxml.jackson.core:jackson-annotations:$jacksonBomVersion"
          )
        )
      }

      test("does not report the version the pom asked for") {
        // The old resolution ignored the BOM and reported these at 2.15.0,
        // which is not what ends up on the classpath.
        val all = bomOverridesTransitive.resolved.keySet
        assert(
          !all.contains(
            s"com.fasterxml.jackson.core:jackson-core:$jacksonPinnedVersion"
          )
        )
        assert(
          !all.contains(
            s"com.fasterxml.jackson.core:jackson-annotations:$jacksonPinnedVersion"
          )
        )
      }

      test("leaves the directly pinned version alone") {
        // The BOM only fills in what the build did not state.
        assert(
          directOf(bomOverridesTransitive).contains(
            s"com.fasterxml.jackson.core:jackson-databind:$jacksonPinnedVersion"
          )
        )
      }
    }

    test("a runtime-scoped transitive") {

      test("does not abort the manifest") {
        // The synthetic coursier project is resolved at one configuration
        // while the tree roots are walked at another, the tree spans nodes
        // the resolution never reconciled, and coursier throws
        // "Cannot find ... in reconciled versions" out of `DependencyTree`.
        assert(viaRuntimeScope.resolved.nonEmpty)
      }

      test("reaches the manifest as indirect") {
        assert(
          indirectOf(viaRuntimeScope).contains(
            s"org.junit.platform:junit-platform-suite-commons:$junitPlatformVersion"
          )
        )
      }

      test("still reports the compile-scoped transitives") {
        // The runtime configuration pulls the compile one in as well, so
        // widening to runtime must not cost us any compile-scope node.
        val indirect = indirectOf(viaRuntimeScope)
        assert(
          indirect.contains(
            s"org.junit.platform:junit-platform-engine:$junitPlatformVersion"
          )
        )
        assert(
          indirect.contains(
            s"org.junit.platform:junit-platform-suite-api:$junitPlatformVersion"
          )
        )
      }
    }

    test("scope") {

      test("compile omits the runtime-scoped transitive") {
        val all = manifestOf(
          testBuild.everyScope,
          Some(GraphScope.Compile)
        ).resolved.keySet
        assert(
          !all.contains(
            s"org.junit.platform:junit-platform-suite-commons:$junitPlatformVersion"
          )
        )
      }

      test("compile omits runMvnDeps") {
        // `coursierProject` files runMvnDeps under the runtime configuration
        // alone, so at compile scope they are not in the resolution and must
        // not be tree roots either.
        val all = manifestOf(
          testBuild.everyScope,
          Some(GraphScope.Compile)
        ).resolved.keySet
        assert(!all.exists(_.startsWith("org.slf4j:slf4j-simple:")))
      }

      test("compile keeps the compile-scoped transitives") {
        val all = manifestOf(
          testBuild.everyScope,
          Some(GraphScope.Compile)
        ).resolved.keySet
        assert(
          all.contains(
            s"org.junit.platform:junit-platform-suite-api:$junitPlatformVersion"
          )
        )
      }

      test("runtime adds the runtime-scoped transitive and runMvnDeps") {
        val all = manifestOf(
          testBuild.everyScope,
          Some(GraphScope.Runtime)
        ).resolved.keySet
        assert(
          all.contains(
            s"org.junit.platform:junit-platform-suite-commons:$junitPlatformVersion"
          )
        )
        assert(all.contains(s"org.slf4j:slf4j-simple:$slf4jVersion"))
      }

      test("runtime still omits compileMvnDeps") {
        val all = manifestOf(
          testBuild.everyScope,
          Some(GraphScope.Runtime)
        ).resolved.keySet
        assert(!all.exists(_.startsWith("org.projectlombok:lombok:")))
      }

      test("all adds compileMvnDeps without losing runtime") {
        val all =
          manifestOf(testBuild.everyScope, Some(GraphScope.All)).resolved.keySet
        assert(all.contains(s"org.projectlombok:lombok:$lombokVersion"))
        assert(
          all.contains(
            s"org.junit.platform:junit-platform-suite-commons:$junitPlatformVersion"
          )
        )
        assert(all.contains(s"org.slf4j:slf4j-simple:$slf4jVersion"))
      }

      test("no scope means runtime") {
        val implied = manifestOf(testBuild.everyScope).resolved.keySet
        val explicit = manifestOf(
          testBuild.everyScope,
          Some(GraphScope.Runtime)
        ).resolved.keySet
        assert(implied == explicit)
      }

      test("every scope produces a manifest at all") {
        // The invariant bug #12 broke: if a tree root is absent from the
        // resolution's reconciled versions, coursier throws out of
        // `DependencyTree.Node.dependency` and takes the whole run down.
        // Asserting it per scope stops a new scope reintroducing the bug.
        GraphScope.values.foreach { scope =>
          val resolved = manifestOf(testBuild.everyScope, Some(scope)).resolved
          assert(resolved.nonEmpty)
        }
      }
    }

    test("every JavaModule in the build is discovered") {
      // Guards `computeModules` independently of resolution, so a resolution
      // failure cannot be mistaken for a discovery failure.
      val discovered = UnitTester(testBuild, null).scoped { eval =>
        Resolver.computeModules(eval.evaluator).map(_.toString).toSet
      }
      assert(
        discovered == Set(
          "viaDepManagement",
          "viaBom",
          "bomOverridesTransitive",
          "viaRuntimeScope",
          "everyScope"
        )
      )
    }
  }
}
