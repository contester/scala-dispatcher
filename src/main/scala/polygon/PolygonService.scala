package org.stingray.contester.polygon

import com.twitter.util.Future
import org.stingray.contester.invokers.InvokerRegistry
import org.stingray.contester.problems
import problems._
import org.stingray.contester.utils.ScannerCache
import java.net.URL

class ProblemByPid(client: SpecializedClient, pdb: PolygonDb) extends ScannerCache[ProblemURL, PolygonProblem, PolygonProblem] {
  def nearGet(key: ProblemURL): Future[Option[PolygonProblem]] =
    pdb.getProblemDescription(key)

  def nearPut(key: ProblemURL, value: PolygonProblem): Future[PolygonProblem] =
    pdb.setProblemDescription(value).map(_ => value)

  def farGet(key: ProblemURL): Future[PolygonProblem] =
    client.getProblem(key)

  override val farScan: Boolean = true
}

class ContestScanner(pdb: PolygonDb) extends ScannerCache[URL, ContestDescription, ContestDescription] {
  def nearGet(key: URL): Future[Option[ContestDescription]] =
    pdb.getContestDescription(URL)

  def nearPut(key: Int, value: ContestDescription): Future[ContestDescription] =
    pdb.setContestDescription(key, value).map(_ => value)

  def farGet(key: Int): Future[ContestDescription] =
    PolygonClient.getContest(key)

  override val farScan: Boolean = true
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