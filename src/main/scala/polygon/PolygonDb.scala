package org.stingray.contester.polygon

import com.twitter.util.Future
import com.mongodb.casbah.Imports._
import java.net.URL
import org.stingray.contester.utils.ValueCache

trait PolygonCacheKey {
  def url: URL
}

trait PolygonContestKey extends PolygonCacheKey
trait PolygonProblemKey extends PolygonCacheKey {
  def revision: Option[Int]
}

class PolygonCache(mdb: MongoDB) extends ValueCache[PolygonCacheKey, String] {
  private def findProblem(problem: PolygonProblemKey) =
    if (problem.revision.isDefined)
      mdb("problem").findOne("id" -> problem.url.toString, "revision" -> problem.revision.get)
    else
      mdb("problem").find("id" -> problem.url.toString).sort("revision" -> -1).take(1).toIterable.headOption

  def get(key: PolygonCacheKey): Future[Option[String]] =
    key match {
      case contest: PolygonContestKey =>
        Future {
          mdb("contest").findOne("_id" -> contest.url.toString)
            .flatMap(_.getAs[String]("raw"))
        }
      case problem: PolygonProblemKey =>
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
        Future {
          mdb("contest").save(Map("_id" -> contest.url.toString, "raw" -> value))
        }
      case problem: PolygonProblemKey if problem.revision.isDefined =>
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
