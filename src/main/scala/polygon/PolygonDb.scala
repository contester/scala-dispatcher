package org.stingray.contester.polygon

import java.io.InputStream
import java.net.URI

import com.twitter.finagle.Service
import com.twitter.finagle.redis.Client
import com.twitter.finagle.redis.util.StringToChannelBuffer
import com.twitter.io.Charsets
import com.twitter.util.Future
import org.apache.http.client.utils.URIBuilder
import org.stingray.contester.utils.ValueCache

import scala.xml.XML

trait ProvidesRedisKey {
  def redisKey: String
}

case class PolygonContest(uri: URI) extends AnyVal

trait PolygonContestClient {
  def getContest(contest: PolygonContest): Future[Option[ContestDescription]]
}

case class PolygonProblemID(uri: URI, revision: Option[Int]) {
  def fullUri: URI = {
    val r = new URIBuilder(uri)
    revision.map(rev => r.addParameter("revision", rev.toString)).getOrElse(r).build()
  }
}

trait PolygonProblemClient {
  def getProblem(problem: PolygonProblemID): Future[Option[PolygonProblem]]
  def getProblemFile(problem: PolygonProblemID): Future[Option[InputStream]]
}

case class PolygonClient(service: Service[URI, Option[PolygonResponse]]) extends PolygonContestClient with PolygonProblemClient {
  override def getContest(contest: PolygonContest): Future[Option[ContestDescription]] =
    service(contest.uri).map { responseOption =>
      responseOption.map { response =>
        ContestDescription.parse(XML.loadString(response.response.contentString))
      }
    }

  override def getProblem(problem: PolygonProblemID): Future[Option[PolygonProblem]] =
    service(problem.fullUri).map { responseOption =>
      responseOption.map { response =>
        PolygonProblem.parse(XML.loadString(response.response.contentString))
      }
    }

  override def getProblemFile(problem: PolygonProblemID): Future[Option[InputStream]] = ???
}

class RedisStore(client: Client) extends ValueCache[ProvidesRedisKey, String] {
  override def get(key: ProvidesRedisKey): Future[Option[String]] =
    client.get(StringToChannelBuffer(key.redisKey)).map(_.map(_.toString(Charsets.Utf8)))

  override def put(key: ProvidesRedisKey, value: String): Future[Unit] =
    client.set(StringToChannelBuffer(key.redisKey), StringToChannelBuffer(value))
}