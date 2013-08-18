package org.stingray.contester.polygon

import com.twitter.util.{Await, Future}
import org.stingray.contester.invokers.InvokerRegistry
import org.stingray.contester.problems
import problems._
import org.stingray.contester.utils._
import java.net.URL
import com.google.common.cache.{CacheLoader, CacheBuilder}
import scala.xml.XML
import org.jboss.netty.buffer.ChannelBuffer
import com.twitter.finagle.Service
import java.util.concurrent.TimeUnit
import com.google.common.util.concurrent.{SettableFuture, ListenableFuture}


/// watch returns Future[Iterable[ContestDescription]]

class ContestRepository(client: Service[PolygonClientRequest, ChannelBuffer], cache: Cache[ContestHandle, String]) {
  object NearCacheReloader extends CacheLoader[ContestHandle, Future[ContestDescription]] {
    def load(key: ContestHandle): Future[ContestDescription] =
      nearFetch(key)

    override def reload(key: ContestHandle, oldValue: Future[ContestDescription]): ListenableFuture[Future[ContestDescription]] = {
      val result = new SettableFuture[Future[ContestDescription]]
      if (oldValue.isDefined) {
        farFetch(key).map(transform)
          .onSuccess {
            v =>
              result.set(if (v == Await.result(oldValue)) oldValue else Future.value(v))
          }
          .onFailure(e => result.setException(e))
      } else {
        result.set(oldValue)
      }
      result
    }
  }

  private def farFetchReal(key: ContestHandle): Future[String] =
    client(key).map(PolygonClient.asPage)

  private def farFetch(key: ContestHandle) =
    farSerial(key, () => farFetchReal(key))

  private def nearFetch(key: ContestHandle) =
    cache.get(key).flatMap { v =>
      v.map(x => Future.value(transform(x)))
       .getOrElse(farFetch(key).map(transform))
    }

  private def transform(x: String) =
    (new ContestDescription(XML.loadString(x)))

  private val nearFutureCache = CacheBuilder.newBuilder()
    .expireAfterAccess(300, TimeUnit.SECONDS)
    .refreshAfterWrite(60, TimeUnit.SECONDS)
    .build(NearCacheReloader)

  private val farSerial = new SerialHash[ContestHandle, String]

  def apply(key: ContestHandle) =
    nearFutureCache.get(key)
}

class ProblemByPid(client: SpecializedClient, pdb: PolygonDb) extends ScannerCache[ProblemURL, PolygonProblem, PolygonProblem] {
  def nearGet(key: ProblemURL): Future[Option[PolygonProblem]] =
    pdb.getProblemDescription(key)

  def nearPut(key: ProblemURL, value: PolygonProblem): Future[PolygonProblem] =
    pdb.setProblemDescription(value).map(_ => value)

  def farGet(key: ProblemURL): Future[PolygonProblem] =
    client.getProblem(key)

  override val farScan: Boolean = true
}

class ContestScanner(pdb: PolygonDb) extends ScannerCache[URL, ContestDescription, ContestDescription] {
  def nearGet(key: URL): Future[Option[ContestDescription]] =
    pdb.getContestDescription(URL)

  def nearPut(key: Int, value: ContestDescription): Future[ContestDescription] =
    pdb.setContestDescription(key, value).map(_ => value)

  def farGet(key: Int): Future[ContestDescription] =
    PolygonClient.getContest(key)

  override val farScan: Boolean = true
}

class PolygonSanitizer(db: SanitizeDb, client: SpecializedClient, invoker: InvokerRegistry)
  extends ProblemDBSanitizer[PolygonProblem](db, new SimpleSanitizer(invoker)) {
  def getProblemFile(key: PolygonProblem): Future[Array[Byte]] =
    client.getProblemFile(key)
}

object PolygonSanitizer {
  def apply(db: SanitizeDb, client: SpecializedClient, invoker: InvokerRegistry) =
    new PolygonSanitizer(db, client, invoker)
}