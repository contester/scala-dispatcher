package org.stingray.contester.polygon

import com.twitter.util.Future
import org.stingray.contester.invokers.InvokerRegistry
import org.stingray.contester.problems
import problems._
import org.stingray.contester.utils.{ValueCache, ScannerCache}
import org.jboss.netty.buffer.ChannelBuffer
import com.twitter.finagle.Service
import scala.xml.XML

class ProblemByPid(client: Service[PolygonClientRequest, ChannelBuffer], pdb: ValueCache[PolygonCacheKey, String]) extends ScannerCache[PolygonProblemHandle, PolygonProblem, PolygonProblem] {
  def nearGet(key: PolygonProblemHandle): Future[Option[PolygonProblem]] =
    pdb.get(key).map(_.map(page => new PolygonProblem(XML.loadString(page), Some(key.url))))

  def nearPut(key: PolygonProblemHandle, value: PolygonProblem): Future[PolygonProblem] =
    pdb.put(key, value.source.buildString(false)).map(_ => value)

  def farGet(key: PolygonProblemHandle): Future[PolygonProblem] =
    client(key).map(buffer => new PolygonProblem(XML.loadString(PolygonClient.asPage(buffer)), Some(key.url)))

  override val farScan: Boolean = true
}

class ContestByPid(client: Service[PolygonClientRequest, ChannelBuffer], pdb: ValueCache[PolygonCacheKey, String]) extends ScannerCache[ContestHandle, ContestDescription, ContestDescription] {
  def nearGet(key: ContestHandle): Future[Option[ContestDescription]] =
    pdb.get(key).map(_.map(page => new ContestDescription(XML.loadString(page))))

  def nearPut(key: ContestHandle, value: ContestDescription): Future[ContestDescription] =
    pdb.put(key, value.source.buildString(false)).map(_ => value)

  def farGet(key: ContestHandle): Future[ContestDescription] =
    client(key).map(buffer => new ContestDescription(XML.loadString(PolygonClient.asPage(buffer))))

  override val farScan: Boolean = true
}

class PolygonSanitizer(db: SanitizeDb, client: Service[PolygonClientRequest, ChannelBuffer], invoker: InvokerRegistry)
  extends ProblemDBSanitizer[PolygonProblem](db, new SimpleSanitizer(invoker)) {
  def getProblemFile(key: PolygonProblem): Future[Array[Byte]] =
    client(new PolygonProblemHandle(key.url, Some(key.revision)).file).map(buffer => PolygonClient.asByteArray(buffer))
}

class PolygonService(client: Service[PolygonClientRequest, ChannelBuffer], pdb: ValueCache[PolygonCacheKey, String]) {
  val contests = new ContestByPid(client, pdb)
  val problems = new ProblemByPid(client, pdb)
}