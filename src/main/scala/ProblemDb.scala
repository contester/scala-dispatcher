package org.stingray.contester.common

import com.mongodb.casbah.Imports._
import com.mongodb.casbah.MongoConnection
import com.mongodb.casbah.gridfs.Imports._
import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.polygon.{Problem, PolygonClient, Contest}
import org.stingray.contester.rpc4.RpcTools
import org.stingray.contester.utils.Utils
import org.stingray.contester.{ProblemTuple, ProblemURL}

class ProblemManifest(val testerName: String, val answers: Iterable[Int], val interactorName: Option[String])

class ProblemDb(val mongoHost: String, val client: PolygonClient) extends Logging {
  val mconn = MongoConnection(mongoHost)
  val mdb = mconn("contester")
  val mfs = GridFS.apply(mdb)

  def get[I <: com.google.protobuf.Message](key: String)(implicit manifest: Manifest[I]) =
    Future {
      mfs.findOne(key).map(f => RpcTools.createProtobufFromInputStream[I](f.inputStream)).headOption
    }

  def set(key: String, value: com.google.protobuf.MessageLite) =
    Future {
      trace("Setting %s".format(key))
      mfs(value.toByteArray) { fh =>
        fh.filename = key
      }
    }

  def setProblemManifest(problem: ProblemTuple, manifest: ProblemManifest) =
    Future {
      val m = Map(
        "id" -> problem.shortUrl,
        "revision" -> problem.revision,
        "testerName" -> manifest.testerName,
        "answers" -> manifest.answers
      ) ++ manifest.interactorName.map("interactorName" -> _)

      mdb("manifest").insert(
        MongoDBObject(
          m.toList
        )
      )
    }

  def getProblemManifest(problem: ProblemTuple): Future[Option[ProblemManifest]] =
    Future {
      val criteria = MongoDBObject("id" -> problem.shortUrl, "revision" -> problem.revision) ++ ("testerName" $exists true)
      mdb("manifest").findOne(criteria)
        .flatMap { i =>
        import collection.JavaConversions._
        i.getAs[String]("testerName").flatMap { n =>
          i.getAs[java.lang.Iterable[Int]]("answers").map(new ProblemManifest(n, _, i.getAs[String]("interactorName")))
        }
    }}

  def setProblem(problem: Problem) =
    Future {
      mdb("problem").insert(
        MongoDBObject(
          "id" -> problem.shortId,
          "revision" -> problem.revision,
          "raw" -> problem.source.buildString(false),
          "testCount" -> problem.testCount,
          "timeLimit" -> problem.timeLimit / 1000,
          "memoryLimit" -> problem.memoryLimit,
          "titles" -> problem.titles
        )
      )
    }

  def getProblem(problem: ProblemURL) =
    Future {
      mdb("problem").find(MongoDBObject("id" -> problem.short)).sort(MongoDBObject("revision" -> -1))
        .take(1).toIterable.headOption
        .map(i => i.getAs[String]("raw").map(s => Problem(PolygonClient.asXml(s), problem))).flatten
    }

  def setContest(contestId: Int, contest: Contest) =
    Future {
      mdb("contest").save(MongoDBObject("_id" -> contestId, "raw" -> contest.source.buildString(false)))
    }

  def getContest(contestId: Int) =
    Future {
      mdb("contest").findOne(MongoDBObject("_id" -> contestId))
        .map(i => i.getAs[String]("raw").map(s => Contest(PolygonClient.asXml(s)))).flatten
    }

  import com.twitter.util.TimeConversions._

  def storeProblemFile(problem: ProblemTuple, data: Array[Byte]) =
    Future {
      mfs(data) { fh =>
        fh.filename = problem.archiveName
      }
    }.unit.flatMap(_ => Utils.later(2.seconds))

  def getProblemFile(problem: ProblemTuple) =
    Future {
      mfs.findOne(problem.archiveName).headOption.map(_ => true)
    }.flatMap(_.map(_ => Future.Done).getOrElse(client.getProblemFile(problem).flatMap(p => storeProblemFile(problem, p))))
}

object ProblemDb {
  def apply(mhost: String, pclient: PolygonClient) =
    new ProblemDb(mhost, pclient)
}
