package io.eleven19.mill.github.dependency.graph

import io.eleven19.github.dependency.graph.domain
import mill._
import mill.api.ExternalModule
import mill.api.Evaluator

trait GraphModule extends ExternalModule {

  import Writers._

  // Both `submit` and `generate` must be exclusive commands because they
  // access `Evaluator.rootModule` and `Evaluator.executeApi` via
  // `EvaluatorBridge`. In Mill 1.x, non-exclusive Task.Command bodies receive
  // an `EvaluatorProxy` that throws "No evaluator available here; Evaluator
  // is only available in exclusive commands" on those accesses.
  def submit(ev: Evaluator): Task.Command[Unit] = Task.Command(exclusive = true) {
    val manifests = generate(ev)()
    val snapshot = Github.snapshot(manifests)
    Github.submit(snapshot)
  }

  def generate(ev: Evaluator): Task.Command[Map[String, domain.Manifest]] =
    Task.Command(exclusive = true) {
      val modules = Resolver.computeModules(ev)
      val moduleTrees = Resolver.resolveModuleTrees(ev, modules)
      val manifests: Map[String, domain.Manifest] =
        moduleTrees.map(mt => (mt.module.toString(), mt.toManifest())).toMap

      manifests
    }

}
