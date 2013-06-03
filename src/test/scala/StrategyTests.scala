package org.stingray.contester.testing

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import com.twitter.util.{Await, Future}

object ZeroTest extends TestingStrategy {
  def test(test: Solution.NumberedTest): Future[Solution.EvaluatedTestResult] =
    Future.value(true -> (test._1, null))
}

class FailAt(x: Int) extends TestingStrategy {
  def test(test: Solution.NumberedTest): Future[Solution.EvaluatedTestResult] =
    Future.value((x != test._1) -> (test._1, null))
}

class StrategyTest extends FlatSpec with ShouldMatchers {
  "Sequential" should "be in ascending order" in {
    expectResult((1 to 100).map(x => true -> (x -> null))) {
      Await.result(ZeroTest.sequential((1 to 100).map(_ -> null)))
    }
  }
  "Parallel" should "be in any order" in {
    expectResult((1 to 100).map(x => true -> (x -> null))) {
      Await.result(ZeroTest.parallel((1 to 100).map(_ -> null))).sortBy(_._2._1)
    }
  }
  "Sequential" should "stop at X" in {
    expectResult((1 to 55).map(x => (x != 55) -> (x -> null))) {
      Await.result(new FailAt(55).sequential((1 to 100).map(_ -> null)))
    }
  }
  "Parallel" should "not stop at X" in {
    expectResult((1 to 100).map(x => (x != 55) -> (x -> null))) {
      Await.result(new FailAt(55).parallel((1 to 100).map(_ -> null))).sortBy(_._2._1)
    }
  }
  "School" should "stop at 1" in {
    expectResult((1 to 1).map(x => (x != 1) -> (x -> null))) {
      Await.result(new FailAt(1).school((1 to 100).map(_ -> null)))
    }
  }
  "School" should "not stop at 2" in {
    expectResult((1 to 100).map(x => (x != 2) -> (x -> null))) {
      Await.result(new FailAt(2).school((1 to 100).map(_ -> null)))
    }
  }
}

