package org.stingray.contester.problems

import org.scalatest.{FlatSpec, Matchers}

class SimpleProblemTests extends FlatSpec with Matchers {
  "Json" should "decode" in {
    val source0 =
      """[{
        |"id":"direct://school.sgu.ru/moodle/1",
        |"revision":2,
        |"testCount":20,"timeLimitMicros":1000000,
        |"memoryLimit":16777216,"testerName":"tester.exe",
        |"answers":[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20]},
        |{
        |"id":"direct://school.sgu.ru/moodle/1",
        |"revision":1,
        |"testCount":19,"timeLimitMicros":1000000,
        |"memoryLimit":16777216,"testerName":"tester.exe",
        |"answers":[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19]}]""".stripMargin

    SimpleProblemDb.parseSimpleProblemManifest(source0) shouldBe Some(SimpleProblemManifest(
      "direct://school.sgu.ru/moodle/1", 2, 20, 1000000, 16777216, false, "tester.exe",
      (1 to 20).toSet, None))

  }
}