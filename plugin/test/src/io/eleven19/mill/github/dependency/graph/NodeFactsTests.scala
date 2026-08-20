package io.eleven19.mill.github.dependency.graph

import io.eleven19.github.dependency.graph.domain.DependencyRelationship
import io.eleven19.github.dependency.graph.domain.DependencyScope
import utest._

object NodeFactsTests extends TestSuite {

  private val directRuntime = NodeFacts.direct(DependencyScope.runtime)
  private val indirectRuntime = NodeFacts.indirect(DependencyScope.runtime)
  private val directDev = NodeFacts.direct(DependencyScope.development)
  private val indirectDev = NodeFacts.indirect(DependencyScope.development)

  private val all =
    Seq(directRuntime, indirectRuntime, directDev, indirectDev)

  val tests = Tests {

    test("merge widens on both axes") {

      test("direct beats indirect") {
        assert(
          indirectRuntime.merge(directRuntime).relationship ==
            DependencyRelationship.direct
        )
      }

      test("runtime beats development") {
        // A library on the runtime classpath is a production dependency even
        // if a test pulls it too. Understating what ships is the failure to
        // avoid; overstating costs only noise.
        assert(
          indirectDev.merge(indirectRuntime).scope == DependencyScope.runtime
        )
      }

      test("the two axes widen independently") {
        assert(indirectDev.merge(directRuntime) == directRuntime)
      }
    }

    test("merge is order-independent") {

      test("commutative for every pair") {
        // This is the property that stops walk order being load-bearing. It
        // previously was, unwritten: roots were walked direct-first and the
        // dedupe relied on it.
        for { a <- all; b <- all } assert(a.merge(b) == b.merge(a))
      }

      test("idempotent") {
        all.foreach(facts => assert(facts.merge(facts) == facts))
      }

      test("associative for every triple") {
        for { a <- all; b <- all; c <- all }
          assert(a.merge(b).merge(c) == a.merge(b.merge(c)))
      }
    }

    test("asIndirect drops direct and leaves scope alone") {
      assert(directRuntime.asIndirect == indirectRuntime)
      assert(directDev.asIndirect == indirectDev)
      assert(indirectDev.asIndirect == indirectDev)
    }
  }
}
