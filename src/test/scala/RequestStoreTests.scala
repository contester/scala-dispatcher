package org.stingray.contester.invokers

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import com.twitter.util.{Promise, Future}

class FakeInvoker extends HasCaps[Int] {
  def caps: Iterable[Int] = 1 :: 2 :: Nil
}

case class FakeKey(val x: Int) extends Ordered[FakeKey] {
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

    expect(f) {
      s.get(1, FakeKey(1), "1")(x => Future.value(x)).apply()
    }

    val p = new Promise[Int]()

    val r = s.get(1, FakeKey(1), "1")(x => p)
    val r2 = s.get(2, FakeKey(2), "2")(x => Future.value(2))

    p.setValue(5)
    expect(5) {
      r.apply()
    }

    expect(2) {
      r2.apply()
    }
  }

}

