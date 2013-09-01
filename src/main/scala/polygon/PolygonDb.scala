package org.stingray.contester.polygon

import com.twitter.util.Future
import com.mongodb.casbah.Imports._
import org.stingray.contester.utils.ValueCache
import java.net.URL
import grizzled.slf4j.Logging

trait PolygonCacheKey {
  def url: URL
}

trait PolygonContestKey extends PolygonCacheKey
trait PolygonProblemKey extends PolygonCacheKey {
  def revision: Option[Int]
}

class PolygonCache(mdb: MongoDB) extends ValueCache[PolygonCacheKey, String] with Logging {
  private def findProblem(problem: PolygonProblemKey) =
    if (problem.revision.isDefined)
      mdb("problem").findOne(MongoDBObject("id" -> problem.url.toString, "revision" -> problem.revision.get))
    else
      mdb("problem").find(MongoDBObject("id" -> problem.url.toString)).sort(MongoDBObject("revision" -> -1)).take(1).toIterable.headOption

  def get(key: PolygonCacheKey): Future[Option[String]] =
    key match {
      case contest: PolygonContestKey =>
        trace("getContest(%s)".format(contest))
        Future {
          mdb("contest").findOne(MongoDBObject("_id" -> contest.url.toString))
            .flatMap(_.getAs[String]("raw"))
        }
      case problem: PolygonProblemKey =>
        trace("getProblem(%s)".format(problem))
        Future {
          findProblem(problem)
            .flatMap(_.getAs[String]("raw"))
        }
      case _ =>
        Future.None
    }

  def put(key: PolygonCacheKey, value: String): Future[Unit] =
    key match {
      case contest: PolygonContestKey =>
        trace("putContest(%s, %s)".format(contest, value))
        Future {
          mdb("contest").save(Map("_id" -> contest.url.toString, "raw" -> value))
        }
      case problem: PolygonProblemKey if problem.revision.isDefined =>
        trace("putProblem(%s, %s)".format(problem, value))
        Future {
          mdb("problem").insert(
            Map(
              "id" -> problem.url.toString,
              "revision" -> problem.revision.get,
              "raw" -> value
            )
          )
        }
      case _ =>
        Future.Done
    }
}