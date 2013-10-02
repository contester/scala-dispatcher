package org.stingray.contester.invokers

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import com.twitter.util.{Await, Promise, Future}

class FakeInvoker extends HasCaps[Int] {
  def caps: Iterable[Int] = 1 :: 2 :: Nil
}

case class FakeKey(x: Int) extends Ordered[FakeKey] {
  def compare(that: FakeKey): Int = x.compareTo(that.x)
}

class FakeRequestStore extends RequestStore[Int, FakeKey, FakeInvoker] {
  protected def stillAlive(invoker: FakeInvoker): Boolean = true
}

class RequestStoreTests extends FlatSpec with ShouldMatchers {
  "Request store" should "not race" in {
    val s = new FakeRequestStore

    val f = new FakeInvoker
    s.addInvokers(f :: Nil)

    expectResult(f) {
      Await.result(s.get(1, FakeKey(1), "1")(x => Future.value(x)))
    }

    val p = new Promise[Int]()

    val r = s.get(1, FakeKey(1), "1")(x => p)
    val r2 = s.get(2, FakeKey(2), "2")(x => Future.value(2))

    p.setValue(5)
    expectResult(5) {
      Await.result(r)
    }

    expectResult(2) {
      Await.result(r2)
    }
  }

}

