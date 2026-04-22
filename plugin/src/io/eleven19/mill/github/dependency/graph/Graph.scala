package io.eleven19.mill.github.dependency.graph

import mill.github.dependency.graph.internal.EvaluatorBridge

object Graph extends GraphModule {

  implicit def millEvaluatorTokenReader
      : mainargs.TokensReader[mill.api.Evaluator] =
    EvaluatorBridge.evaluatorTokenReader

  lazy val millDiscover: mill.api.Discover =
    mill.api.Discover[this.type]
}
