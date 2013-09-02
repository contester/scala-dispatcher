package org.stingray.contester.utils

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec

import java.util.concurrent.TimeUnit
import com.twitter.util.{Await, Future, Promise, Duration}
import com.google.common.cache.{CacheLoader, CacheBuilder}
import com.google.common.util.concurrent.{SettableFuture, ListenableFuture}
import com.google.common.base.Ticker

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

class ScannerCacheTests extends FlatSpec with ShouldMatchers {
  "ScannerCache" should "behave transparently" in {
    val c = ScannerCache[Int, Int](_ => Future.None, (_, _) => Future.Done, _ => Future.value(1))

    expectResult(1) {
      Await.result(c(1))
    }
  }

  it should "serialize properly" in {
    val p = new Promise[Int]()

    val c = ScannerCache[Int, Int](_ => Future.None, (_, _) => Future.Done, _ => p)
    val f1 = c(1)
    val f2 = c(1)

    expectResult(false) {
      c(1).isDefined
    }

    p.setValue(2)
    expectResult(2) {
      Await.result(f1)
    }

    expectResult(2) {
      Await.result(f2)
    }

    val f3 = c(1)
    f3 should not equal f1
    expectResult(2) {
      Await.result(f3)
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

    expectResult(2) {
      Await.result(f1)
    }

    expectResult(2) {
      Await.result(f2)
    }

    val f3 = c(1)
    expectResult(2) {
      Await.result(f3)
    }
  }

  it should "rescan" in {
    var cnt = 0
    def fx(x: Int) = {
      cnt += 1
      Future.value(x)
    }

    val c = ScannerCache[Int, Int](_ => Future.None, (_, _) => Future.Done, fx)

    Await.result(c(1))
    Await.result(c(1))

    expectResult(1) {
      cnt
    }

    Await.result(c(2).join(c(2)))
    expectResult(2) {
      cnt
    }

    Await.result(c.scan(Seq(1)))

    expectResult(3) {
      cnt
    }
  }

  it should "use near-cache" in {
    val c = ScannerCache[Int, Int](_ => Future.value(Some(5)), (_, _) => Future.Done, _ => Future.value(7))

    expectResult(5) {
      Await.result(c(1))
    }
    Await.result(c.scan(Seq(1)))
    expectResult(5) {
      Await.result(c(1))
    }
  }

}

class RefreshCacheTests extends FlatSpec with ShouldMatchers {
  "CacheLoader" should "refresh" in {
    object Loader extends CacheLoader[String, String] {
      def load(key: String): String = key

      override def reload(key: String, oldValue: String): ListenableFuture[String] = {
        val result = SettableFuture.create[String]()
        result.set(key + 100)
        result
      }
    }

    object Tick extends Ticker {
      var value: Long = 0
      def read(): Long = value
    }

    val c = CacheBuilder.newBuilder().refreshAfterWrite(5, TimeUnit.NANOSECONDS).expireAfterAccess(15, TimeUnit.NANOSECONDS).ticker(Tick).build(Loader)

    expectResult("foo")(c.get("foo"))
    Tick.value = 10
    expectResult("foo100")(c.get("foo"))
  }
}