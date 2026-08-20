package io.eleven19.mill.github.dependency.graph.integration

import java.nio.file.Paths

import mill.testkit.IntegrationTester

/** Shared plumbing for the integration tier.
  *
  * `IntegrationTester` copies a fixture into a temporary workspace and runs a
  * real Mill process against it, so these tests exercise what a consumer
  * exercises: the published coordinate, the command line, and the traits as
  * seen from a build file.
  */
object Fixtures {

  private def resource(name: String): os.Path = {
    val url = Option(getClass.getClassLoader.getResource(name)).getOrElse(
      throw new java.lang.AssertionError(
        s"Missing test resource '$name'. Check integration.test.resources."
      )
    )
    os.Path(Paths.get(url.toURI))
  }

  private lazy val millExecutable: os.Path = resource("mill-executable.jar")

  private lazy val repository: os.Path = millExecutable / os.up / "repository"

  private lazy val pluginVersion: String =
    sys.env.getOrElse(
      "PLUGIN_VERSION",
      throw new java.lang.AssertionError(
        "PLUGIN_VERSION is not set. Check integration.test.forkEnv."
      )
    )

  /** The fixture's Mill resolves the plugin from the local test repository,
    * and its own dependencies from central.
    */
  val env: Map[String, String] = Map(
    "COURSIER_REPOSITORIES" -> Seq(
      repository.toNIO.toUri.toASCIIString,
      "ivy2Local",
      "central"
    ).mkString("|")
  )

  /** Runs `block` against a fresh copy of the named fixture.
    *
    * The fixture's build header pins `@PLUGIN_VERSION@`, which is rewritten
    * here rather than hard-coded, so the fixture stays correct whatever
    * `PUBLISH_VERSION` the build was given.
    */
  def withFixture[T](name: String)(block: IntegrationTester => T): T = {
    val tester = new IntegrationTester(
      daemonMode = false,
      workspaceSourcePath = resource(s"$name/build.mill") / os.up,
      millExecutable = millExecutable
    )
    try {
      tester.modifyFile(
        tester.workspacePath / "build.mill",
        _.replace("@PLUGIN_VERSION@", pluginVersion)
      )
      block(tester)
    } finally tester.close()
  }

  private val generateSelector =
    "io.eleven19.mill.github.dependency.graph.Graph/generate"

  def generate(
      tester: IntegrationTester,
      args: String*
  ): IntegrationTester.EvalResult =
    tester.eval(Seq(generateSelector) ++ args, env = env)

  /** Fails with the subprocess's full output.
    *
    * An integration failure reported as "exit code 1" costs more time than
    * the test saves.
    */
  def requireSuccess(
      result: IntegrationTester.EvalResult
  ): IntegrationTester.EvalResult =
    if (result.isSuccess) result
    else throw new java.lang.AssertionError(result.debugString)

  /** The manifests the last `generate` produced: module name to the set of
    * `org:name:version` keys it reported.
    */
  def manifests(tester: IntegrationTester): Map[String, Set[String]] =
    tester
      .out(generateSelector)
      .json
      .obj
      .map { case (module, manifest) =>
        module -> manifest("resolved").obj.keys.toSet
      }
      .toMap
}
