package org.stingray.contester.polygon

import java.net.URI

import org.scalatest.{FlatSpec, Matchers}

class NamingTests extends FlatSpec with Matchers {
  "ProblemIds" should "match" in {
    PolygonProblemUtils.getPdbPath(new URI("https://polygon.test/p/bla/bla/")) shouldBe "polygon/https/polygon.test/p/bla/bla"
    PolygonProblemUtils.getPdbPath(new URI("https://polygon.codeforces.com/p/dmatov/football-trainings/")) shouldBe "polygon/https/polygon.codeforces.com/p/dmatov/football-trainings"
  }
}
