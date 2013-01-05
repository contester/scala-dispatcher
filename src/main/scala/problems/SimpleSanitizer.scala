package org.stingray.contester.problems

import org.stingray.contester.engine.{Sanitizer, ProblemDescription}
import org.stingray.contester.invokers.InvokerRegistry
import com.twitter.util.Future
import collection.mutable
import org.stingray.contester.utils.ScannerCache

class SimpleSanitizer(invoker: InvokerRegistry) extends Function[ProblemDescription, Future[ProblemManifest]] {
  private val futures = new mutable.HashMap[ProblemDescription, Future[ProblemManifest]]()

  def apply(key: ProblemDescription): Future[ProblemManifest] =
    synchronized {
      futures.getOrElseUpdate(key, invoker("zip", key, "sanitize")(Sanitizer(_, key)).ensure(futures.remove(key)))
    }
}

abstract class ProblemDBSanitizer[ProblemType <: ProblemDescription](db: SanitizeDb,
                         simpleSanitizer: Function[ProblemDescription, Future[ProblemManifest]])
  extends ScannerCache[ProblemType, Problem, ProblemManifest] {

  def nearGet(key: ProblemType): Future[Option[Problem]] =
    db.getProblem(key)

  def nearPut(key: ProblemType, value: ProblemManifest): Future[Problem] = db.setProblem(key, value)

  def farGet(key: ProblemType): Future[ProblemManifest] =
    db.getProblemFile(key, getProblemFile(key))
      .flatMap(_ => simpleSanitizer(key))

  def getProblemFile(key: ProblemType): Future[Array[Byte]]
}