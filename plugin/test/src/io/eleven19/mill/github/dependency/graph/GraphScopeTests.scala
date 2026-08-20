package io.eleven19.mill.github.dependency.graph

import utest._

object GraphScopeTests extends TestSuite {

  val tests = Tests {

    test("parsing") {

      test("accepts every value it advertises") {
        val parsed =
          GraphScope.values.map(scope => GraphScope.fromString(scope.name))
        assert(parsed == GraphScope.values.map(Right(_)))
      }

      test("rejects an unknown value") {
        assert(GraphScope.fromString("runtim").isLeft)
      }

      test("names every valid value in the rejection") {
        // The whole point of the message: a typo should tell you what to type
        // instead, rather than leaving you with a silently empty graph.
        val message = GraphScope.fromString("runtim").left.getOrElse("")
        assert(message.contains("runtim"))
        assert(GraphScope.values.forall(scope => message.contains(scope.name)))
      }
    }

    test("serialisation") {

      test("round-trips through upickle") {
        // `dependencyGraphScope` is a Task, and Mill caches task values as
        // JSON, so this has to hold for the per-module override to work.
        val roundTripped = GraphScope.values.map { scope =>
          upickle.default.read[GraphScope](upickle.default.write(scope))
        }
        assert(roundTripped == GraphScope.values)
      }

      test("writes the plain name") {
        assert(upickle.default.write(GraphScope.All) == "\"all\"")
      }
    }
  }
}
