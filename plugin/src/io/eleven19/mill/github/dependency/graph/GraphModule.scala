package io.eleven19.mill.github.dependency.graph

import io.eleven19.github.dependency.graph.domain
import mill._
import mill.api.ExternalModule
import mill.api.Evaluator

trait GraphModule extends ExternalModule {

  import Writers._

  def submit(ev: Evaluator): Task.Command[Unit] = Task.Command {
    val manifests = generate(ev)()
    val snapshot = Github.snapshot(manifests)
    Github.submit(snapshot)
  }

  def generate(ev: Evaluator): Task.Command[Map[String, domain.Manifest]] =
    Task.Command {
      val modules = Resolver.computeModules(ev)
      val moduleTrees = Resolver.resolveModuleTrees(ev, modules)
      val manifests: Map[String, domain.Manifest] =
        moduleTrees.map(mt => (mt.module.toString(), mt.toManifest())).toMap

      manifests
    }

}
