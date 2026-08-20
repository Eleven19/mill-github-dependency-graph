package io.eleven19.mill.github.dependency.graph

import utest._

object GraphScopeTests extends TestSuite {

  val tests = Tests {

    test("parsing") {

      test("accepts every value it advertises") {
        // `.toSeq`: `GraphScope.values` is the enum's compiler-derived
        // `Array`, whose `==` is reference equality, not content equality.
        val parsed = GraphScope.values.toSeq.map(scope =>
          GraphScope.fromString(scope.name)
        )
        assert(parsed == GraphScope.values.toSeq.map(Right(_)))
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

      test("pins the wire strings a user types on the command line") {
        // `fromString` is implemented by searching `values`, which makes the
        // two tests above self-referential: a rename would pass both while
        // breaking every consumer's command line. This pins the literal
        // strings instead.
        assert(
          GraphScope.values.map(_.name).toSeq == Seq(
            "compile",
            "runtime",
            "all"
          )
        )
      }
    }

    test("serialisation") {

      test("round-trips through upickle") {
        // `dependencyGraphScope` is a Task, and Mill caches task values as
        // JSON, so this has to hold for the per-module override to work.
        // `.toSeq`: see the note on the first "parsing" test above.
        val roundTripped = GraphScope.values.toSeq.map { scope =>
          upickle.default.read[GraphScope](upickle.default.write(scope))
        }
        assert(roundTripped == GraphScope.values.toSeq)
      }

      test("writes the plain name") {
        assert(upickle.default.write(GraphScope.All) == "\"all\"")
      }
    }
  }
}
