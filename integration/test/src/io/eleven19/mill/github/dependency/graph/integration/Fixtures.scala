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

  private val defaultRuns =
    scala.collection.mutable.Map.empty[String, Map[String, Set[String]]]

  /** One no-flag `generate` over a fixture, run once and reused.
    *
    * Several tests assert on different parts of the same output. Each used to
    * spawn its own Mill subprocess against a fresh workspace, which was most
    * of this suite's runtime for no extra coverage — the input is identical,
    * so the output is too.
    *
    * Runs that pass flags are deliberately NOT shared: different input,
    * different output, and collapsing them would weaken the tests rather than
    * speed them up.
    */
  def defaultManifests(name: String): Map[String, Set[String]] =
    synchronized {
      defaultRuns.getOrElseUpdate(
        name,
        withFixture(name) { tester =>
          requireSuccess(generate(tester))
          manifests(tester)
        }
      )
    }

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

  /** The Mill version `millExecutable` was built from. Fixtures get this
    * written as their `.mill-version` rather than pinning one of their own,
    * so bumping Mill cannot silently load a new-API plugin into an old Mill:
    * the launcher and the fixture always agree.
    */
  private lazy val millVersion: String =
    sys.env.getOrElse(
      "MILL_VERSION",
      throw new java.lang.AssertionError(
        "MILL_VERSION is not set. Check integration.test.forkEnv."
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
      os.write(tester.workspacePath / ".mill-version", millVersion)
      block(tester)
    } finally tester.close()
  }

  private val generateSelector =
    "io.eleven19.mill.github.dependency.graph.Graph/generate"

  private val submitSelector =
    "io.eleven19.mill.github.dependency.graph.Graph/submit"

  /** Runs `submit` with its API endpoint pointed at a closed local port, so
    * the POST cannot leave the machine.
    *
    * Never call `submit` in a test without this. Inside GitHub Actions every
    * `GITHUB_*` variable is set and `ci.yml` exports `GITHUB_TOKEN`, and
    * `IntegrationTester` propagates the environment — so a test that merely
    * assumes "this cannot succeed here" will submit a real dependency
    * snapshot for this repository. An earlier version of this helper did
    * exactly that: the fixture's dependencies were accepted by the API under
    * the `integration_ci` correlator.
    *
    * Port 1 refuses connections, so `submit` fails at the POST — after
    * `generate` has written `--output`, which is what these tests assert on.
    */
  def submitWithoutReachingGitHub(
      tester: IntegrationTester,
      args: String*
  ): IntegrationTester.EvalResult =
    tester.eval(
      Seq(submitSelector) ++ args,
      env = env ++ Map("GITHUB_API_URL" -> "http://127.0.0.1:1")
    )

  private val reportSelector =
    "io.eleven19.mill.github.dependency.graph.Graph/report"

  private def run(
      selector: String,
      tester: IntegrationTester,
      args: Seq[String]
  ): IntegrationTester.EvalResult =
    tester.eval(Seq(selector) ++ args, env = env)

  def generate(
      tester: IntegrationTester,
      args: String*
  ): IntegrationTester.EvalResult =
    run(generateSelector, tester, args)

  /** Runs `Graph/report`, the HTML-report sibling of `generate`. */
  def report(
      tester: IntegrationTester,
      args: String*
  ): IntegrationTester.EvalResult =
    run(reportSelector, tester, args)

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
