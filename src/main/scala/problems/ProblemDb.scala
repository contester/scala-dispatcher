package org.stingray.contester.problems

import com.mongodb.casbah.Imports._
import com.twitter.util.Future
import org.stingray.contester.utils.Utils
import com.mongodb.casbah.gridfs.GridFS
import grizzled.slf4j.Logging
import com.google.common.collect.MapMaker

case class ProblemManifest(testCount: Int, timeLimitMicros: Long, memoryLimit: Long,
                           stdio: Boolean, testerName: String, answers: Iterable[Int], interactorName: Option[String]) {
  def toList = List(
    "testCount" -> testCount,
    "timeLimitMicros" -> timeLimitMicros,
    "memoryLimit" -> memoryLimit,
    "stdio" -> stdio,
    "testerName" -> testerName,
    "answers" -> answers) ++ interactorName.map("interactorName" -> _)

  def ::(problem: ProblemID) =
    MongoDBObject(
      List("_id" -> problem.pdbId, "id" -> problem.id, "revision" -> problem.revision) ++ toList
    )
}

object ProblemManifest {
  def apply(m: MongoDBObject): ProblemManifest =
    new ProblemManifest(
      m.getAsOrElse[Int]("testCount", 1),
      m.getAsOrElse[Long]("timeLimitMicros", 1000000),
      m.getAsOrElse[Long]("memoryLimit", 64 * 1024 * 1024),
      m.getAsOrElse[Boolean]("stdio", false),
      m.getAsOrElse[String]("testerName", "check.exe"),
      m.getAs[Iterable[Int]]("answers").getOrElse(Seq()),
      m.getAs[String]("interactorName")
    )
}

trait ProblemDb {
  def setProblem(problem: ProblemID, manifest: ProblemManifest): Future[Problem]
  def getProblem(problem: ProblemID): Future[Option[Problem]]
  def getMostRecentProblem(problemId: String): Future[Option[Problem]]
}

trait ProblemFileStore {
  def getProblemFile(problem: ProblemID, getFn: => Future[Array[Byte]]): Future[Unit]
}

trait SanitizeDb extends ProblemDb with ProblemFileStore

class CommonProblemDb(mdb: MongoDB) extends SanitizeDb with Logging {
  lazy val mfs = GridFS(mdb)

  import collection.JavaConversions._
  val localCache: collection.concurrent.Map[(String, Int), PDBProblem] = new MapMaker().weakValues().makeMap[(String, Int), PDBProblem]()

  def buildIndexes: Future[Unit] =
    Future {
      trace("Ensuring indexes on problem db")
      mdb("manifest").ensureIndex(Map("id" -> 1, "revision" -> 1), "uniqueProblem", true)
    }

  def setProblem(problem: ProblemID, manifest: ProblemManifest) =
    Future {
      trace("Inserting manifest: " + (problem :: manifest))
      mdb("manifest").insert(problem :: manifest)
      PDBProblem(this, problem, manifest)
    }

  private def getManifest(problem: ProblemID): Future[Option[ProblemManifest]] =
    Future {
      trace("Looking for manifest: " + problem)
      val criteria = MongoDBObject("id" -> problem.id, "revision" -> problem.revision) ++ ("testerName" $exists true)
      mdb("manifest").findOne(criteria)
        .map { i =>
        ProblemManifest(i)
      }
    }

  def getProblem(problem: ProblemID): Future[Option[Problem]] =
    getManifest(problem).map { opt =>
      opt.map { m =>
        PDBProblem(this, problem, m)
      }
    }
  import com.twitter.util.TimeConversions._

  private[this] def storeProblemFile(problem: ProblemID, data: Array[Byte]) =
    Future {
      mfs(data) { fh =>
        fh.filename = problem.archiveName
      }
    }.unit.flatMap(_ => Utils.later(2.seconds))

  private[this] def hasProblemFile(problem: ProblemID): Future[Boolean] =
    Future {
      mfs.findOne(problem.archiveName).headOption.isDefined
    }

  def getProblemFile(problem: ProblemID, getFn: => Future[Array[Byte]]): Future[Unit] =
    hasProblemFile(problem).flatMap { exists =>
      if (!exists)
        getFn.flatMap(storeProblemFile(problem, _))
      else Future.Done
    }

  def getMostRecentProblem(problemId: String): Future[Option[Problem]] =
    Future {
      mdb("manifest").find(MongoDBObject("id" -> problemId)).sort(MongoDBObject("revision" -> -1))
        .take(1).toIterable.headOption.map { found =>
        val revision = found.getAsOrElse[Int]("revision", 1)
        localCache.get((problemId, revision)).getOrElse {
          val pt = SimpleProblemID(problemId, revision)
          val m = ProblemManifest(found)
          val p = PDBProblem(this, pt, m)
          localCache.putIfAbsent((problemId, revision), p).getOrElse(p)
        }
      }
    }
}

