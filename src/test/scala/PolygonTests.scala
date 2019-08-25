/*package org.stingray.contester.polygon

import java.net.URI

import com.typesafe.config.ConfigFactory
import org.scalatest.{FlatSpec, Matchers}

import scala.io.Source
import scala.xml.XML


class NamingTests extends FlatSpec with Matchers {
  "ProblemIds" should "match" in {
    PolygonProblemUtils.getPdbPath(new URI("https://polygon.test/p/bla/bla/")) shouldBe "polygon/https/polygon.test/p/bla/bla"
    PolygonProblemUtils.getPdbPath(new URI("https://polygon.codeforces.com/p/dmatov/football-trainings/")) shouldBe "polygon/https/polygon.codeforces.com/p/dmatov/football-trainings"
  }
}

object ParseTests {
  val c0 = XML.load(getClass.getResourceAsStream("/contest1.xml"))
  val p0 = XML.load(getClass.getResourceAsStream("/problem-mmirzayanov-qf-2009-trial-odds.xml"))
}

class ParseTests extends FlatSpec with Matchers {
  import ParseTests._
  "contest" should "parse" in {
    ContestDescription.parse(c0) shouldBe ContestDescription(
      Map("english" -> "ACM ICPC 2010-2011, NEERC, Southern Subregional Contest"),
      Map("E" -> "https://polygon.codeforces.com/p/nbond/kidnapping",
        "J" -> "https://polygon.codeforces.com/p/erogacheva/qf-2010-buoys",
        "F" -> "https://polygon.codeforces.com/p/mmirzayanov/lift",
        "A" -> "https://polygon.codeforces.com/p/dmatov/bencoding",
        "I" -> "https://polygon.codeforces.com/p/mmirzayanov/oil-wells",
        "G" -> "https://polygon.codeforces.com/p/mmirzayanov/maximizing-roads-quality",
        "L" -> "https://polygon.codeforces.com/p/mmirzayanov/time-to-repair-roads",
        "B" -> "https://polygon.codeforces.com/p/mmirzayanov/city-3d-model",
        "C" -> "https://polygon.codeforces.com/p/ralekseenkov/explode-them-all",
        "H" -> "https://polygon.codeforces.com/p/mmirzayanov/north-east",
        "K" -> "https://polygon.codeforces.com/p/DStepanenko/running-hero",
        "D" -> "https://polygon.codeforces.com/p/vgoldshteyn/fire-in-the-country").mapValues(x => new URI(x))
    )
  }

  "problem" should "parse" in {
    PolygonProblem.parse(p0) shouldBe PolygonProblem(
      new URI("https://polygon.codeforces.com/p/mmirzayanov/qf-2009-trial-odds"),16,
      Map("english" -> "Odd Numbers"),1000,536870912,25,Set("trial"))
  }
}

object ConfigTests {
  val configText =
    """
      |polygons {
      |  default {
      |    url = "http://foo/bar"
      |    username = u1
      |    password = p1
      |  }
      |  secret {
      |    url = "http://dsdd/"
      |    username = u2
      |    password = p2
      |  }
      |}
    """.stripMargin

  val configConf = ConfigFactory.parseString(configText)
}

class ConfigTests extends FlatSpec with Matchers {
  import ConfigTests._
  "configs" should "parse" in {
    Polygons.fromConfig(configConf.getConfig("polygons").root()) shouldBe Map(
      "default" -> PolygonConfig("default", Seq(new URI("http://foo/bar")), PolygonAuthInfo2("u1", "p1")),
      "secret" -> PolygonConfig("secret", Seq(new URI("http://dsdd/")), PolygonAuthInfo2("u2", "p2"))
    )
  }
}*/
