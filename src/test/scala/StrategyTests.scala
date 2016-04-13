package org.stingray.contester.testing

import com.twitter.util.{Await, Future}
import org.scalatest.{FlatSpec, Matchers}

object ZeroTest extends TestingStrategy {
  def test(test: Solution.NumberedTest): Future[Solution.EvaluatedTestResult] =
    Future.value(true -> (test._1, null))
}

class FailAt(x: Int) extends TestingStrategy {
  def test(test: Solution.NumberedTest): Future[Solution.EvaluatedTestResult] =
    Future.value((x != test._1) -> (test._1, null))
}

class StrategyTest extends FlatSpec with Matchers {
  private val testCount = 100
  private val sortedTests = (1 to testCount).map(_ -> null)
  private val sortedResults = (1 to testCount).map(x => true -> (x -> null))

  "Sequential" should "be in ascending order" in {
    Await.result(ZeroTest.sequential(sortedTests)) shouldBe sortedResults
  }
  "Parallel" should "be in any order" in {
    Await.result(ZeroTest.parallel(sortedTests)).sortBy(_._2._1) shouldBe sortedResults
  }
  "Sequential" should "stop at X" in {
    Await.result(new FailAt(55).sequential(sortedTests)) shouldBe (1 to 55).map(x => (x != 55) -> (x -> null))
  }
  "Parallel" should "not stop at X" in {
    Await.result(new FailAt(55).parallel(sortedTests)).sortBy(_._2._1) shouldBe (1 to testCount).map(x => (x != 55) -> (x -> null))
  }
  "School" should "stop at 1" in {
    Await.result(new FailAt(1).school((1 to 100).map(_ -> null))) shouldBe (1 to 1).map(x => (x != 1) -> (x -> null))
  }
  "School" should "not stop at 2" in {
    Await.result(new FailAt(2).school((1 to 100).map(_ -> null))) shouldBe (1 to 100).map(x => (x != 2) -> (x -> null))
  }
}

