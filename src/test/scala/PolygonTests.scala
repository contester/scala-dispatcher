package org.stingray.contester.polygon

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import java.net.URL

class NamingTests extends FlatSpec with ShouldMatchers {
  "ProblemIds" should "match" in {
    expectResult("polygon/https/polygon.test/p/bla/bla") {
      PolygonProblemUtils.getPdbPath(new URL("https://polygon.test/p/bla/bla/"))
    }

    expectResult("polygon/https/polygon.codeforces.com/p/dmatov/football-trainings") {
      PolygonProblemUtils.getPdbPath(new URL("https://polygon.codeforces.com/p/dmatov/football-trainings/"))
    }
  }
}
