package org.stingray.contester

trait ProblemTuple extends SchedulingKey {
  protected val getTimestamp = EARLIEST

  override def hashCode() =
    url.hashCode() + revision.hashCode()

  override def equals(p1: Any) = p1 match {
    case other: ProblemTuple =>
      url == other.url && revision == other.revision
    case _ => super.equals(p1)
  }

  override def toString =
    "Problem(%s)".format(shortUrl)

  def url: ProblemURL
  def revision: Int

  lazy val shortUrl = url.short
  lazy val destName = shortUrl.replace('/', '.').replace(':', '.') + "." + revision.toString
  lazy val zipName = destName + ".zip"
  lazy val prefix = Seq("problem", shortUrl, revision.toString).mkString("/")
  def dbName(suffix: String) =
    prefix + "/" + suffix

  def manifestName = dbName("manifest")
  def problemXmlName = dbName("problem.xml")
  def checkerName = dbName("checker")
  def testPrefix(testId: Int) = dbName("tests/" + testId + "/")
  def inputName(testId: Int) = testPrefix(testId) + "input.txt"
  def answerName(testId: Int) = testPrefix(testId) + "answer.txt"
  def archiveName = dbName("archive")
  def interactorName = dbName("interactor")
}