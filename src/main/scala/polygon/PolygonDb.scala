package org.stingray.contester.polygon

import com.twitter.util.Future
import com.mongodb.casbah.Imports._
import org.stingray.contester.problems.CommonProblemDb
import org.stingray.contester.utils.ProtobufTools

trait PolygonDb {
  def setContestDescription(contestId: Int, contest: ContestDescription): Future[Unit]
  def getContestDescription(contestId: Int): Future[Option[ContestDescription]]

  def setProblemDescription(problem: PolygonProblem): Future[Unit]
  def getProblemDescription(problem: ProblemURL): Future[Option[PolygonProblem]]
}

class CommonPolygonDb(mdb: MongoDB) extends CommonProblemDb(mdb) with PolygonDb {
  def get[I <: com.google.protobuf.Message](key: String)(implicit manifest: Manifest[I]) =
    Future {
      mfs.findOne(key).map(f => ProtobufTools.createProtobuf[I](f.inputStream)).headOption
    }

  def set(key: String, value: com.google.protobuf.MessageLite) =
    Future {
      mfs(value.toByteArray) { fh =>
        fh.filename = key
      }
    }

  def setContestDescription(contestId: Int, contest: ContestDescription) =
    Future {
      mdb("contest").save(MongoDBObject("_id" -> contestId, "raw" -> contest.source.buildString(false)))
    }

  def getContestDescription(contestId: Int) =
    Future {
      mdb("contest").findOne(MongoDBObject("_id" -> contestId))
        .map(i => i.getAs[String]("raw").map(s => PolygonContest(PolygonClient.asXml(s)))).flatten.headOption
    }

  def setProblemDescription(problem: PolygonProblem) =
    Future {
      mdb("problem").insert(
        MongoDBObject(
          "id" -> problem.shortUrl,
          "revision" -> problem.revision,
          "raw" -> problem.source.buildString(false)
        )
      )
    }

  def getProblemDescription(problem: ProblemURL) =
    Future {
      mdb("problem").find(MongoDBObject("id" -> problem.shortId)).sort(MongoDBObject("revision" -> -1))
        .take(1).toIterable.headOption
        .map(i => i.getAs[String]("raw").map(s => PolygonProblem(PolygonClient.asXml(s), problem))).flatten.headOption
    }
}
