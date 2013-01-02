package org.stingray.contester.utils

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec

import java.util.concurrent.TimeUnit
import com.twitter.util.{Future, Promise, Duration}

class SerialHashTests extends FlatSpec with ShouldMatchers {
  "HashQueue" should "return the same future for long-running request" in {
    val sq = new SerialHash[Int, Unit]

    val f1 = sq(1, () => Utils.later(Duration(1, TimeUnit.MINUTES)))
    expect(f1) {
      sq(1, () => Utils.later(Duration(1, TimeUnit.MINUTES)))
    }
  }

  it should "return the same future N times for long-running request" in {
    val sq = new SerialHash[Int, Int]
    val p = new Promise[Int]()
    val q = new Promise[Int]()

    sq(1, () => p)

    val results = Future.collect((1 to 100).map(_ => sq(1, () => q)))

    p.setValue(2)
    q.setValue(4)

    expect((1 to 100).map(_ => 2)) {
      results.apply()
    }
  }
  it should "return different results when prev request is completed" in {
    val sq = new SerialHash[Int, Int]
    val p = new Promise[Int]()
    val q = new Promise[Int]()

    val f1 = sq(1, () => p)
    p.setValue(2)
    f1()

    Utils.later(Duration(1, TimeUnit.SECONDS)).apply()

    val f2 = sq(1, () => q)
    f2 should not equal f1
  }
}

class ScannerCacheTests extends FlatSpec with ShouldMatchers {
  "ScannerCache" should "behave transparently" in {
    val c = ScannerCache[Int, Int](_ => Future.None, (_, _) => Future.Done, _ => Future.value(1))

    expect(1) {
      c(1).apply()
    }
  }

  it should "serialize properly" in {
    val p = new Promise[Int]()

    val c = ScannerCache[Int, Int](_ => Future.None, (_, _) => Future.Done, _ => p)
    val f1 = c(1)
    val f2 = c(1)

    expect(f1) {
      c(1)
    }

    p.setValue(2)
    expect(2) {
      f1.apply()
    }

    expect(2) {
      f2.apply()
    }

    val f3 = c(1)
    f3 should not equal f1
    expect(2) {
      f3.apply()
    }
  }
}