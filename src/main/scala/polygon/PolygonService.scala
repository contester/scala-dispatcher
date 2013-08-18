package org.stingray.contester.polygon

import com.twitter.util.{Time, Future}
import org.stingray.contester.invokers.InvokerRegistry
import org.stingray.contester.problems
import problems._
import org.stingray.contester.utils._
import java.net.URL
import com.google.common.collect.MapMaker
import com.google.common.cache.CacheBuilder
import scala.xml.XML
import org.jboss.netty.buffer.ChannelBuffer
import com.twitter.finagle.Service
import scala.collection.mutable


/// watch returns Future[Iterable[ContestDescription]]


class ContestSimpleWatcher(client: Service[PolygonClientRequest, ChannelBuffer], cache: Cache[ContestHandle, (String, Time)]) {
  /**
   * Maps the key to future for rescan action.
   * Futures are replaced (not chained) for each rescan.
   * To stop watching, remove a future & (optionally) cancel it.
   */
  private val watched = {
    import scala.collection.JavaConverters._
    new MapMaker().makeMap[ContestHandle, Future[Unit]].asScala
  }

  private val watchers = new mutable.HashMap[WatcherReceiver[ContestHandle, ContestDescription], Iterable[ContestHandle]]

  private val localValueCache = CacheBuilder.newBuilder().build[ContestHandle, (ContestDescription, Time)]

  private def transform(x: (String, Time)) =
    (new ContestDescription(XML.loadString(x._1)), x._2)

  private def storeLocal(key: ContestHandle, rawValue: (String, Time)): (ContestDescription, Time) = {
    val result = transform(rawValue)
    localValueCache.put(key, result)
    result
  }

  // farfetch needs to wait if there's an exception/item not found.

  private def farFetch(key: ContestHandle): Future[(String, Time)] =
    client(key).map(PolygonClient.asPage).map(x => (x, Time.now))

  // TODO: change notifications
  private def fetchAndReplace(key: ContestHandle) =
    farFetch(key).flatMap { v =>
      cache.put(key, v).map(_ => storeLocal(key, v)._1)
    }.ensure(rescheduleWatch(key))

  private def rescheduleWatch(key: ContestHandle): Unit = {
    import com.twitter.util.TimeConversions._
    watched.replace(key, Utils.later(1.minute).flatMap(_ => fetchAndReplace(key).map(_ => rescheduleWatch(key))))
  }

  private def cachedFetch(key: ContestHandle): Future[ContestDescription] =
    cache.get(key).flatMap(_.map(v => Future.value(storeLocal(key, v)._1))
      .getOrElse(farFetch(key).flatMap { v =>
        cache.put(key, v).map(_ => storeLocal(key, v)._1)
    }))

  def get(key: ContestHandle): Future[ContestDescription] =
    Option(localValueCache.getIfPresent(key))
      .map(v => Future.value(v._1))
      .getOrElse(cachedFetch(key))

  /**
   * Set watched keys for the receiver.
   * @param receiver
   * @param keys
   */
  def watch(receiver: WatcherReceiver[ContestHandle, ContestDescription], keys: Iterable[ContestHandle]): Unit = {
    synchronized {
      val prev = watchers.values.flatten.toSet
      watchers.put(receiver, keys)
      val now = watchers.values.flatten.toSet

      (prev -- now).foreach(watched.remove(_).foreach(_.cancel()))
      (now -- prev).foreach(watched.putIfAbsent(_, Future.never))
    }
    Future.collect(keys.map(k => get(k).map(k -> _)).toSeq)
  }
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