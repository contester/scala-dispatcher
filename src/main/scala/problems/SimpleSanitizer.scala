package org.stingray.contester.problems

import org.stingray.contester.engine.{Sanitizer, ProblemDescription}
import org.stingray.contester.invokers.InvokerRegistry
import com.twitter.util.Future
import collection.mutable

class SimpleSanitizer(invoker: InvokerRegistry) extends Function[ProblemDescription, Future[ProblemManifest]] {
  private val futures = new mutable.HashMap[ProblemDescription, Future[ProblemManifest]]()

  def apply(key: ProblemDescription): Future[ProblemManifest] =
    synchronized {
      futures.getOrElseUpdate(key, invoker("zip", key, "sanitize")(Sanitizer(_, key)).ensure(futures.remove(key)))
    }
}

abstract class ProblemDBSanitizer[ProblemType <: ProblemDescription](db: SanitizeDb,
                         simpleSanitizer: Function[ProblemDescription, Future[ProblemManifest]])
  extends Function[ProblemType, Future[Problem]] {

  def getProblemFile(key: ProblemType): Future[Array[Byte]]

  private val cache = new mutable.HashMap[ProblemType, Problem]()
  private val futures = new mutable.HashMap[ProblemType, Future[Problem]]()

  private def updateCache(key: ProblemType, result: Problem): Unit =
    synchronized {
      cache(key) = result
    }

  private def removeFuture(key: ProblemType): Unit =
    synchronized {
      futures.remove(key)
    }

  private def returnOrSanitize(key: ProblemType, result: Option[Problem]): Future[Problem] =
    result.map(Future.value(_))
      .getOrElse(
          db.getProblemFile(key, getProblemFile(key))
            .flatMap(_ => simpleSanitizer(key)).flatMap(db.setProblem(key, _)))

  def apply(key: ProblemType): Future[Problem] =
    synchronized {
      if (cache.contains(key))
        Future.value(cache(key))
      else
        futures.getOrElseUpdate(key,
          db.getProblem(key).flatMap(returnOrSanitize(key, _)).onSuccess(updateCache(key, _)).ensure(removeFuture(key)))
    }

  def scan(keys: Seq[ProblemType]): Future[Unit] =
    Future.collect(keys.map(apply(_))).unit
}