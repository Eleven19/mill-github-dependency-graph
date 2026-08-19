package io.eleven19.mill.github.dependency.graph

import java.nio.file.{Files, Path, Paths}

import utest._

/** The published coordinates are public API: consumers pin them in their
  * `build.mill.yaml`, and changing one silently strands every build that did.
  *
  * The plugin compiles against the Mill API, so its artifact carries the
  * `_mill1` platform suffix that Mill plugins use to declare which Mill binary
  * platform they target. The domain module has no Mill dependency and must
  * stay unsuffixed.
  *
  * These assert on the coordinates and the repository layout this build really
  * produces. The values arrive from `plugin/package.mill`, which wires the
  * evaluated `artifactId` and a `publishLocalTestRepo` of each published
  * module into this JVM's environment.
  */
object PublishCoordinatesTests extends TestSuite {

  private val PluginArtifactId = "mill-github-dependency-graph_mill1_3"
  private val DomainArtifactId = "github-dependency-graph-domain_3"

  private def env(name: String): String =
    sys.env.getOrElse(
      name,
      throw new NoSuchElementException(
        s"$name is not set. It is wired up in the `test` module in plugin/package.mill."
      )
    )

  private val organization = env("PUBLISH_ORGANIZATION")
  private val version = env("PUBLISH_VERSION")

  /** Where `LocalM2Publisher` puts a module: the organization split on dots,
    * then the artifact id, then the version.
    */
  private def releaseDir(repo: String, artifactId: String): Path =
    organization
      .split('.')
      .foldLeft(Paths.get(repo))((dir, seg) => dir.resolve(seg))
      .resolve(artifactId)
      .resolve(version)

  private def pomOf(repo: String, artifactId: String): Path =
    releaseDir(repo, artifactId).resolve(s"$artifactId-$version.pom")

  /** Reads a pom, reporting what the repository does hold when it is missing.
    * A bare `NoSuchFileException` here would say nothing about which
    * coordinate was published instead.
    */
  private def readPom(repo: String, artifactId: String): String = {
    val pom = pomOf(repo, artifactId)
    if (!Files.exists(pom))
      throw new java.lang.AssertionError(
        s"No pom for $artifactId at $pom. " +
          s"The repository holds: ${publishedArtifactIds(repo).toSeq.sorted.mkString(", ")}"
      )
    Files.readString(pom)
  }

  /** Every artifact id that the repository actually holds for our
    * organization, so a test can say what is published and what is not.
    */
  private def publishedArtifactIds(repo: String): Set[String] = {
    val orgDir = organization
      .split('.')
      .foldLeft(Paths.get(repo))((dir, seg) => dir.resolve(seg))
    val stream = Files.list(orgDir)
    try
      stream
        .filter(Files.isDirectory(_))
        .map[String](_.getFileName.toString)
        .toArray(new Array[String](_))
        .toSet
    finally stream.close()
  }

  val tests = Tests {

    test("the plugin artifact id carries the Mill platform suffix") {
      assert(env("PLUGIN_ARTIFACT_ID") == PluginArtifactId)
    }

    test("the domain artifact id carries no platform suffix") {
      // The domain module has no Mill dependency, so it is not tied to a Mill
      // binary platform and must not claim to be.
      assert(env("DOMAIN_ARTIFACT_ID") == DomainArtifactId)
    }

    test("the plugin publishes under the suffixed artifact id") {
      val repo = env("PLUGIN_M2_REPO")
      val pom = pomOf(repo, PluginArtifactId)
      assert(Files.exists(pom))
      assert(
        Files.exists(
          releaseDir(repo, PluginArtifactId).resolve(
            s"$PluginArtifactId-$version.jar"
          )
        )
      )
    }

    test("the plugin publishes nothing under the unsuffixed artifact id") {
      // Guards the direction that matters: dropping the suffix would publish
      // `mill-github-dependency-graph_3` again, which is a different artifact.
      val published = publishedArtifactIds(env("PLUGIN_M2_REPO"))
      assert(published == Set(PluginArtifactId))
    }

    test("the domain publishes under the unsuffixed artifact id") {
      val repo = env("DOMAIN_M2_REPO")
      assert(Files.exists(pomOf(repo, DomainArtifactId)))
      assert(publishedArtifactIds(repo) == Set(DomainArtifactId))
    }

    test("the plugin pom names the suffixed artifact") {
      val pom = readPom(env("PLUGIN_M2_REPO"), PluginArtifactId)
      assert(pom.contains(s"<artifactId>$PluginArtifactId</artifactId>"))
      assert(
        !pom.contains("<artifactId>mill-github-dependency-graph_3</artifactId>")
      )
    }

    test("the plugin pom depends on the unsuffixed domain artifact") {
      // The suffix belongs to the Mill-dependent module only. If it ever leaks
      // onto the domain module, it shows up here as a dependency coordinate
      // that no longer exists.
      val pom = readPom(env("PLUGIN_M2_REPO"), PluginArtifactId)
      assert(pom.contains(s"<artifactId>$DomainArtifactId</artifactId>"))
    }
  }
}
