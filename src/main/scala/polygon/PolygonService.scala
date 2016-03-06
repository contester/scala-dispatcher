package org.stingray.contester.polygon

import com.twitter.io.Buf
import com.twitter.util.Future
import org.stingray.contester.problems
import problems._
import org.stingray.contester.utils.{RefresherCache, ValueCache}
import com.twitter.finagle.Service
import scala.xml.XML
import org.stingray.contester.engine.InvokerSimpleApi

class ProblemByPid(client: Service[PolygonClientRequest, Buf], pdb: ValueCache[PolygonCacheKey, String])
    extends RefresherCache[PolygonProblemHandle, PolygonProblem, String] {
  val cache: ValueCache[PolygonProblemHandle, String] = new ValueCache[PolygonProblemHandle, String] {
    def get(key: PolygonProblemHandle): Future[Option[String]] = pdb.get(key)

    def put(key: PolygonProblemHandle, value: String): Future[Unit] = {
      pdb.put(transform(key, value).handle, value)
    }
  }

  def fetch(key: PolygonProblemHandle): Future[String] = client(key).map(x => Buf.Utf8.unapply(x).get)

  def transform(key: PolygonProblemHandle, x: String): PolygonProblem =
    new PolygonProblem(XML.loadString(x), Some(key.url))
}

class ContestByPid(client: Service[PolygonClientRequest, Buf], pdb: ValueCache[PolygonCacheKey, String])
    extends RefresherCache[ContestHandle, ContestDescription, String] {
  val cache: ValueCache[ContestHandle, String] = new ValueCache[ContestHandle, String] {
    def get(key: ContestHandle): Future[Option[String]] = pdb.get(key)

    def put(key: ContestHandle, value: String): Future[Unit] = pdb.put(key, value)
  }

  def fetch(key: ContestHandle): Future[String] = client(key).map(x => Buf.Utf8.unapply(x).get)

  def transform(key: ContestHandle, x: String): ContestDescription = new ContestDescription(XML.loadString(x))
}

class PolygonSanitizer(db: SanitizeDb, client: Service[PolygonClientRequest, Buf], invoker: InvokerSimpleApi)
  extends ProblemDBSanitizer[PolygonProblem](db, new SimpleSanitizer(invoker)) {
  def getProblemFile(key: PolygonProblem): Future[Array[Byte]] =
    client(new PolygonProblemHandle(key.url, Some(key.revision)).file).map(buffer => PolygonClient.asByteArray(buffer))
}

class PolygonService(client: Service[PolygonClientRequest, Buf], pdb: ValueCache[PolygonCacheKey, String]) {
  val contests = new ContestByPid(client, pdb)
  val problems = new ProblemByPid(client, pdb)
}