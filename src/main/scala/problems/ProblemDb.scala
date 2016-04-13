package org.stingray.contester.problems

import com.twitter.util.Future

case class ProblemManifest(testCount: Int, timeLimitMicros: Long, memoryLimit: Long,
                           stdio: Boolean, testerName: String, answers: Iterable[Int], interactorName: Option[String]) {
  def toList = List(
    "testCount" -> testCount,
    "timeLimitMicros" -> timeLimitMicros,
    "memoryLimit" -> memoryLimit,
    "stdio" -> stdio,
    "testerName" -> testerName,
    "answers" -> answers) ++ interactorName.map("interactorName" -> _)
}

trait ProblemServerInterface {
  def getMostRecentProblem(problem: ProblemHandle): Future[Option[Problem]]
}

trait ProblemDb extends ProblemServerInterface {
  def setProblem(problem: ProblemID, manifest: ProblemManifest): Future[Problem]
  def getProblem(problem: ProblemID): Future[Option[Problem]]
}

trait ProblemFileStore {
  def getProblemFile(problem: ProblemID, getFn: => Future[Array[Byte]]): Future[Unit]
}

trait SanitizeDb extends ProblemDb with ProblemFileStore
/*
class CommonProblemDb extends SanitizeDb with Logging {
  import collection.JavaConversions._

  def setProblem(problem: ProblemID, manifest: ProblemManifest) = ???
  private def getManifest(problem: ProblemID): Future[Option[ProblemManifest]] =
    Future {
      trace("Looking for manifest: " + problem)
      val criteria = MongoDBObject("id" -> problem.pid, "revision" -> problem.revision) ++ ("testerName" $exists true)
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
    mfs.put(problem.archiveName, data, Map.empty)
      .unit.flatMap(_ => Utils.later(2.seconds))

  private[this] def hasProblemFile(problem: ProblemID): Future[Boolean] =
    mfs.exists(problem.archiveName)

  def getProblemFile(problem: ProblemID, getFn: => Future[Array[Byte]]): Future[Unit] =
    hasProblemFile(problem).flatMap { exists =>
      if (!exists)
        getFn.flatMap(storeProblemFile(problem, _))
      else Future.Done
    }

  def getPathPart(url: URI) =
    url.getPath.stripPrefix("/").stripSuffix("/")

  def getSimpleUrlId(url: URI) =
    (url.getScheme :: url.getHost :: (if (url.getPort != -1) url.getPort.toString :: getPathPart(url) :: Nil else getPathPart(url) :: Nil)).mkString("/")

  def getMostRecentProblem(problem: ProblemHandle): Future[Option[Problem]] =
    Future {
      problem match {
        case directProblem: DirectProblemHandle =>
          mdb("manifest").find(MongoDBObject("id" -> directProblem.uri.toString)).sort(MongoDBObject("revision" -> -1))
            .take(1).toIterable.headOption.map { found =>
            val revision = found.getAsOrElse[Int]("revision", 1)
            val pid = new SimpleProblemID(getSimpleUrlId(directProblem.uri), revision)
            val m = ProblemManifest(found)
            PDBProblem(this, pid, m)
          }
      }
    }
}

*/