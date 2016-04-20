package org.stingray.contester.polygon

import java.net.URI

import org.scalatest.{FlatSpec, Matchers}

import scala.xml.XML

class NamingTests extends FlatSpec with Matchers {
  "ProblemIds" should "match" in {
    PolygonProblemUtils.getPdbPath(new URI("https://polygon.test/p/bla/bla/")) shouldBe "polygon/https/polygon.test/p/bla/bla"
    PolygonProblemUtils.getPdbPath(new URI("https://polygon.codeforces.com/p/dmatov/football-trainings/")) shouldBe "polygon/https/polygon.codeforces.com/p/dmatov/football-trainings"
  }
}

object ContestTests {
  val c0 = """<?xml version="1.0" encoding="utf-8" standalone="no"?>
             |<contest url="https://polygon.codeforces.com/c/1">
             |    <names>
             |        <name language="english" value="ACM ICPC 2010-2011, NEERC, Southern Subregional Contest"/>
             |    </names>
             |    <statements>
             |        <statement language="english" type="application/pdf" url="https://polygon.codeforces.com/c/1/english/statements.pdf"/>
             |    </statements>
             |    <problems>
             |        <problem index="a" url="https://polygon.codeforces.com/p/dmatov/bencoding"/>
             |        <problem index="b" url="https://polygon.codeforces.com/p/mmirzayanov/city-3d-model"/>
             |        <problem index="c" url="https://polygon.codeforces.com/p/ralekseenkov/explode-them-all"/>
             |        <problem index="d" url="https://polygon.codeforces.com/p/vgoldshteyn/fire-in-the-country"/>
             |        <problem index="e" url="https://polygon.codeforces.com/p/nbond/kidnapping"/>
             |        <problem index="f" url="https://polygon.codeforces.com/p/mmirzayanov/lift"/>
             |        <problem index="g" url="https://polygon.codeforces.com/p/mmirzayanov/maximizing-roads-quality"/>
             |        <problem index="h" url="https://polygon.codeforces.com/p/mmirzayanov/north-east"/>
             |        <problem index="i" url="https://polygon.codeforces.com/p/mmirzayanov/oil-wells"/>
             |        <problem index="j" url="https://polygon.codeforces.com/p/erogacheva/qf-2010-buoys"/>
             |        <problem index="k" url="https://polygon.codeforces.com/p/DStepanenko/running-hero"/>
             |        <problem index="l" url="https://polygon.codeforces.com/p/mmirzayanov/time-to-repair-roads"/>
             |    </problems>
             |</contest>""".stripMargin('|')
}

class ContestTests extends FlatSpec with Matchers {
  import ContestTests._
  "contest" should "parse" in {
    println(ContestDescription.parse(XML.loadString(c0)))
  }
}
