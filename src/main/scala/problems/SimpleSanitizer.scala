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

class ProblemDBSanitizer(db: SanitizeDb,
                         simpleSanitizer: Function[ProblemDescription, Future[ProblemManifest]])
  extends Function[ProblemDescription, Future[Problem]] {

  private val cache = new mutable.HashMap[ProblemDescription, Problem]()
  private val futures = new mutable.HashMap[ProblemDescription, Future[Problem]]()

  private def updateCache(key: ProblemDescription, result: Problem): Unit =
    synchronized {
      cache(key) = result
    }

  private def removeFuture(key: ProblemDescription): Unit =
    synchronized {
      futures.remove(key)
    }

  private def returnOrSanitize(key: ProblemDescription, result: Option[Problem]): Future[Problem] =
    result.map(Future.value(_)).getOrElse(simpleSanitizer(key).flatMap(db.setProblem(key, _)))

  def apply(key: ProblemDescription): Future[Problem] =
    synchronized {
      if (cache.contains(key))
        Future.value(cache(key))
      else
        futures.getOrElseUpdate(key,
          db.getProblem(key).flatMap(returnOrSanitize(key, _)).onSuccess(updateCache(key, _)).ensure(removeFuture(key)))
    }
}