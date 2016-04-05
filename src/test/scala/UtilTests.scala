package org.stingray.contester.utils

import java.util.concurrent.TimeUnit

import com.twitter.util.{Await, Duration, Future, Promise}
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers

class SerialHashTests extends FlatSpec with ShouldMatchers {
  "HashQueue" should "return the same future for long-running request" in {
    val sq = new SerialHash[Int, Int]
    val p = new Promise[Int]()
    val q = new Promise[Int]()

    val f1 = sq(1, () => p)
    val f2 = sq(1, () => q)
    q.setValue(8)
    p.setValue(1)

    expectResult(1) {
      Await.result(f1)
    }

    expectResult(1) {
      Await.result(f2)
    }
  }

  it should "return the same future N times for long-running request" in {
    val sq = new SerialHash[Int, Int]
    var c = 0
    val p = new Promise[Int]()

    def fx(k: Int) = {
      c += 1
      Future.value(k)
    }

    sq(1, () => p)

    val results = Future.collect((1 to 100).map(_ => sq(1, () => fx(4))))

    p.setValue(2)

    expectResult((1 to 100).map(_ => 2)) {
      Await.result(results)
    }

    expectResult(0) {
      c
    }
  }
  it should "return different results when prev request is completed" in {
    val sq = new SerialHash[Int, Int]
    val p = new Promise[Int]()
    val q = new Promise[Int]()

    val f1 = sq(1, () => p)
    p.setValue(2)
    expectResult(2) {
      Await.result(f1)
    }

    Await.result(Utils.later(Duration(1, TimeUnit.SECONDS)))

    val f2 = sq(1, () => q)
    f2 should not equal f1
    q.setValue(4)
    expectResult(4) {
      Await.result(f2)
    }
  }

  it should "return different results for const requests" in {
    val sq = new SerialHash[Int, String]

    expectResult("one") {
      Await.result(sq(1, () => Future.value("one")))
    }
    expectResult("two") {
      Await.result(sq(1, () => Future.value("two")))
    }
  }
}