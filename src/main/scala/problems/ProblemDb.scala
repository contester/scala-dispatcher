package org.stingray.contester.problems

import com.mongodb.casbah.Imports._
import collection.JavaConversions
import com.twitter.util.Future
import org.stingray.contester.utils.Utils
import com.mongodb.casbah.gridfs.GridFS
import grizzled.slf4j.Logging

case class ProblemManifest(testCount: Int, timeLimitMicros: Long, memoryLimit: Long,
                           stdio: Boolean, testerName: String, answers: Iterable[Int], interactorName: Option[String]) {
  def toList = List(
    "testCount" -> testCount,
    "timeLimitMicros" -> timeLimitMicros,
    "memoryLimit" -> memoryLimit,
    "stdio" -> stdio,
    "testerName" -> testerName,
    "answers" -> answers) ++ interactorName.map("interactorName" -> _)

  def ::(problem: ProblemT) =
    MongoDBObject(
      List("id" -> problem.id, "revision" -> problem.revision) ++ toList
    )
}

object ProblemManifest {
  def apply(m: MongoDBObject): Option[ProblemManifest] =
    m.getAs[String]("testerName").flatMap { testerName =>
      m.getAs[Long]("timeLimitMicros").flatMap { timeLimitMicros =>
        m.getAs[Long]("memoryLimit").flatMap { memoryLimit =>
          m.getAs[Int]("testCount").flatMap { testCount =>
            m.getAs[java.lang.Iterable[Int]]("answers").map { answers =>
              val stdio = m.getAsOrElse[Boolean]("stdio", false)
              val interactorName = m.getAs[String]("interactorName")
              new ProblemManifest(testCount, timeLimitMicros, memoryLimit, stdio, testerName,
                JavaConversions.iterableAsScalaIterable(answers), interactorName)
            }
          }
        }
      }
    }
}

trait ProblemDb {
  def setProblem(problem: ProblemT, manifest: ProblemManifest): Future[Problem]
  def getProblem(problem: ProblemT): Future[Option[Problem]]
}

trait ProblemFileStore {
  def getProblemFile(problem: ProblemT, getFn: => Future[Array[Byte]]): Future[Unit]
}

trait SanitizeDb extends ProblemDb with ProblemFileStore

class CommonProblemDb(mdb: MongoDB) extends SanitizeDb with Logging {
  lazy val mfs = GridFS(mdb)

  def setProblem(problem: ProblemT, manifest: ProblemManifest) =
    Future {
      trace("Inserting manifest: " + (problem :: manifest))
      mdb("manifest").insert(problem :: manifest)
      PDBProblem(this, problem, manifest)
    }

  private def getManifest(problem: ProblemT): Future[Option[ProblemManifest]] =
    Future {
      trace("Looking for manifest: " + problem)
      val criteria = MongoDBObject("id" -> problem.id, "revision" -> problem.revision) ++ ("testerName" $exists true)
      mdb("manifest").findOne(criteria)
        .flatMap { i =>
        ProblemManifest(i)
      }
    }

  def getProblem(problem: ProblemT): Future[Option[Problem]] =
    getManifest(problem).map { opt =>
      opt.map { m =>
        PDBProblem(this, problem, m)
      }
    }
  import com.twitter.util.TimeConversions._

  private[this] def storeProblemFile(problem: ProblemT, data: Array[Byte]) =
    Future {
      mfs(data) { fh =>
        fh.filename = problem.archiveName
      }
    }.unit.flatMap(_ => Utils.later(2.seconds))

  private[this] def hasProblemFile(problem: ProblemT): Future[Boolean] =
    Future {
      mfs.findOne(problem.archiveName).headOption.isDefined
    }

  def getProblemFile(problem: ProblemT, getFn: => Future[Array[Byte]]): Future[Unit] =
    hasProblemFile(problem).flatMap { exists =>
      if (!exists)
        getFn.flatMap(storeProblemFile(problem, _))
      else Future.Done
    }
}

