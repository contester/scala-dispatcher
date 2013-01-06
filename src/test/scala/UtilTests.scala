package org.stingray.contester.utils

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec

import java.util.concurrent.TimeUnit
import com.twitter.util.{Future, Promise, Duration}

class SerialHashTests extends FlatSpec with ShouldMatchers {
  "HashQueue" should "return the same future for long-running request" in {
    val sq = new SerialHash[Int, Int]
    val p = new Promise[Int]()
    val q = new Promise[Int]()

    val f1 = sq(1, () => p)
    val f2 = sq(1, () => q)
    q.setValue(8)
    p.setValue(1)

    expect(1) {
      f1()
    }

    expect(1) {
      f2()
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

    expect((1 to 100).map(_ => 2)) {
      results.apply()
    }

    expect(0) {
      c
    }
  }
  it should "return different results when prev request is completed" in {
    val sq = new SerialHash[Int, Int]
    val p = new Promise[Int]()
    val q = new Promise[Int]()

    val f1 = sq(1, () => p)
    p.setValue(2)
    expect(2) {
      f1()
    }

    Utils.later(Duration(1, TimeUnit.SECONDS)).apply()

    val f2 = sq(1, () => q)
    f2 should not equal f1
    q.setValue(4)
    expect(4) {
      f2()
    }
  }

  it should "return different results for const requests" in {
    val sq = new SerialHash[Int, String]

    expect("one") {
      sq(1, () => Future.value("one")).apply()
    }
    expect("two") {
      sq(1, () => Future.value("two")).apply()
    }
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

    expect(false) {
      c(1).isDefined
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

  it should "actually cache" in {
    var b = false
    val p = new Promise[Int]()
    def fx(x: Int) =
      if (b)
        Future.exception(new RuntimeException("foo"))
      else {
        b = true
        p
      }

    val c = ScannerCache[Int, Int](_ => Future.None, (_, _) => Future.Done, fx)

    val f1 = c(1)
    val f2 = c(1)

    p.setValue(2)

    expect(2) {
      f1.apply()
    }

    expect(2) {
      f2.apply()
    }

    val f3 = c(1)
    expect(2) {
      f3.apply()
    }
  }

  it should "rescan" in {
    var cnt = 0
    def fx(x: Int) = {
      cnt += 1
      Future.value(x)
    }

    val c = ScannerCache[Int, Int](_ => Future.None, (_, _) => Future.Done, fx)

    c(1).apply()
    c(1).apply()

    expect(1) {
      cnt
    }

    c(2).join(c(2)).apply()
    expect(2) {
      cnt
    }

    c.scan(Seq(1)).apply()

    expect(3) {
      cnt
    }
  }

  it should "use near-cache" in {
    val c = ScannerCache[Int, Int](_ => Future.value(Some(5)), (_, _) => Future.Done, _ => Future.value(7))

    expect(5) {
      c(1).apply()
    }
    c.scan(Seq(1)).apply()
    expect(5) {
      c(1).apply()
    }
  }

}