package org.stingray.contester.utils

import com.twitter.util.{Await, Future, Promise}
import org.scalatest.{FlatSpec, Matchers}
/*

class SerialHashTests extends FlatSpec with Matchers {
  "HashQueue" should "return the same future for long-running request" in {
    val sq = new SerialHash[Int, Int]
    val p = new Promise[Int]()
    val q = new Promise[Int]()

    val f1 = sq(1, () => p)
    val f2 = sq(1, () => q)
    q.setValue(8)
    p.setValue(1)

    Await.result(f1) shouldBe 1
    Await.result(f2) shouldBe 1
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

    Await.result(results) shouldBe (1 to 100).map(_ => 2)
    c shouldBe 0
  }

  it should "return different results when prev request is completed" in {
    val sq = new SerialHash[Int, Int]
    val p = new Promise[Int]()
    val q = new Promise[Int]()

    val f1 = sq(1, () => p)
    p.setValue(2)
    Await.result(f1) shouldBe 2

    //implicit val timer = HashedWheelTimer.Default
    //Await.result(Future.sleep(Duration(1, TimeUnit.SECONDS)))

    val f2 = sq(1, () => q)
    f2 should not equal f1
    q.setValue(4)

    Await.result(f2) shouldBe 4
  }

  it should "return different results for const requests" in {
    val sq = new SerialHash[Int, String]

    Await.result(sq(1, () => Future.value("one"))) shouldBe "one"
    Await.result(sq(1, () => Future.value("two"))) shouldBe "two"
  }
}


class ScannerCacheTests extends FlatSpec with Matchers {
  "ScannerCache" should "behave transparently" in {
    val c = ScannerCache[Int, Int, Int](x => x, x => Future.None, (x, y) => Future.Done, x => Future.value(x))

    Await.result(c(1)) shouldBe 1
    Await.result(c(2)) shouldBe 2
  }

  it should "serialize properly" in {
    val p = new Promise[Int]()

    val c = ScannerCache[Int, Int, Int](x => x, x => Future.None, (x, y) => Future.Done, x => p)
    val f1 = c(1)
    val f2 = c(1)
    c(1).isDefined shouldBe false
    p.setValue(2)
    Await.result(f1) shouldBe 2
    Await.result(f2) shouldBe 2
    Await.result(c(1)) shouldBe 2
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

    val c = ScannerCache[Int, Int, Int](x => x, _ => Future.None, (_, _) => Future.Done, fx)

    val f1 = c(1)
    val f2 = c(1)

    p.setValue(2)

      Await.result(f1) shouldBe 2

      Await.result(f2) shouldBe 2

    val f3 = c(1)
      Await.result(f3) shouldBe 2
  }

  it should "rescan" in {
    var cnt = 0
    def fx(x: Int) = {
      cnt += 1
      Future.value(x)
    }

    val c = ScannerCache[Int, Int, Int](x => x, _ => Future.None, (_, _) => Future.Done, fx)

    Await.result(c(1))
    Await.result(c(1))
    cnt shouldBe 1
    Await.result(c(2).join(c(2)))
    cnt shouldBe 2
    Await.result(c.refresh(1))
    cnt shouldBe 3
  }

  it should "use near-cache" in {
    val c = ScannerCache[Int, Int, Int](x => x, _ => Future.value(Some(5)), (_, _) => Future.Done, _ => Future.value(7))

    Await.result(c(1)) shouldBe 5
    Await.result(c(1)) shouldBe 5
    Await.result(c.refresh(1)) shouldBe 7
    Await.result(c(1)) shouldBe 7
  }

}
*/
