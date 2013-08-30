package org.stingray.contester.polygon

import com.twitter.util.Future
import org.stingray.contester.invokers.InvokerRegistry
import org.stingray.contester.problems
import problems._
import org.stingray.contester.utils._
import scala.xml.XML
import org.jboss.netty.buffer.ChannelBuffer
import com.twitter.finagle.Service

class ContestRepository(client: Service[PolygonClientRequest, ChannelBuffer],
                        val cache: ValueCache[ContestHandle, String])
  extends ScannerCache2[ContestHandle, ContestDescription, String] {
  def fetch(key: ContestHandle): Future[String] =
    client(key).map(PolygonClient.asPage)

  def transform(key: ContestHandle, x: String) =
    (new ContestDescription(XML.loadString(x)))
}

class ProblemRepository(client: Service[PolygonClientRequest, ChannelBuffer],
                        val cache: ValueCache[PolygonProblemHandle, String]) extends ScannerCache2[PolygonProblemHandle, PolygonProblem, String] {
  def fetch(key: PolygonProblemHandle): Future[String] =
    client(key).map(PolygonClient.asPage)

  def transform(key: PolygonProblemHandle, x: String): PolygonProblem =
    new PolygonProblem(XML.loadString(x), Some(key.url))
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