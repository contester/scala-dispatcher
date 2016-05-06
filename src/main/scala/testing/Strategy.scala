package org.stingray.contester.testing

import com.twitter.util.Future

/** Mixin with testing strategies.
  *
  */
trait TestingStrategy {
  /** Define this method to perform actual testing.
    *
    * @param test
    * @return
    */
  def test(test: Solution.NumberedTest): Future[Solution.EvaluatedTestResult]

  /** Sequential testing. Run test after test, until validation fails.
    *
    * @param tests Test sequence.
    * @return
    */
  def sequential(tests: Seq[Solution.NumberedTest]): Future[List[Solution.EvaluatedTestResult]] =
    if (tests.nonEmpty)
      test(tests.head).flatMap { etr =>
        if (etr._1)
          sequential(tests.tail).map(x => etr :: x)
        else Future.value(etr :: Nil)
      }
    else Future.value(Nil)

  /** Parallel testing.
    *
    * @param tests
    * @return
    */
  def parallel(tests: Seq[Solution.NumberedTest]): Future[List[Solution.EvaluatedTestResult]] =
    if (tests.nonEmpty)
      Future.collect(tests.map(test(_))).map(_.toList)
    else Future.value(Nil)

  /** School mode: sequentially test first test, the rest is parallel.
    *
    * @param tests
    * @return
    */
  def school(tests: Seq[Solution.NumberedTest]): Future[List[Solution.EvaluatedTestResult]] =
    sequential(Seq(tests.head)).flatMap { first =>
      {
        if (first.head._1)
          parallel(tests.tail)
        else
          Future.value(Nil)
      }.map(x => first ++ x)
    }

  def moodle(tests: Seq[Solution.NumberedTest]): Future[List[Solution.EvaluatedTestResult]] =
    parallel(tests)
}
