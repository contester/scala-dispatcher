package org.stingray.contester.common

import org.stingray.contester.proto.Blobs.{Module, Blob}
import org.stingray.contester.proto.Local.{LocalExecutionParameters, LocalExecutionResult}

trait Result {
  def success: Boolean
}

trait RunResult extends Result {
  def status: Int
  def success = status == StatusCode.Accepted
  def toMap: Map[String, Any]
  def time: Long
  def memory: Long
  def returnCode: Int
  def stdErr: Blob
}

class SingleRunResult(val params: LocalExecutionParameters, val result: LocalExecutionResult) extends RunResult {
  lazy val returnCode = result.getReturnCode

  lazy val flags = result.getFlags
  lazy val isTimeLimitExceeded =
    flags.getTimeLimitHit || flags.getTimeLimitHard || flags.getTimeLimitHitPost || flags.getInactive

  lazy val isRuntimeError =
    returnCode != 0

  lazy val isMemoryLimitExceeded =
    flags.getMemoryLimitHit || flags.getMemoryLimitHitPost

  def status =
    if (isTimeLimitExceeded)
      StatusCode.TimeLimitExceeded
    else if (isMemoryLimitExceeded)
      StatusCode.MemoryLimitExceeded
    else if (isRuntimeError)
      StatusCode.RuntimeError
    else
      StatusCode.Accepted

  def stdOut =
    result.getStdOut

  def stdErr =
    result.getStdErr

  val time = result.getTime.getUserTimeMicros
  val memory = result.getMemory

  // TODO: fix
  def toMap: Map[String, Any] = Map(
    "params" -> params,
    "result" -> result
  )
}

class JavaRunResult(p: LocalExecutionParameters, r: LocalExecutionResult) extends SingleRunResult(p, r) {
  override def status =
    super.status match {
      case StatusCode.RuntimeError => returnCode match {
        case 3 => StatusCode.MemoryLimitExceeded
        case _ => StatusCode.RuntimeError
      }
      case _ => super.status
    }
}

class TesterRunResult(p: LocalExecutionParameters, r: LocalExecutionResult) extends SingleRunResult(p, r) {
  override def status =
    super.status match {
      case StatusCode.Accepted => StatusCode.Accepted
      case StatusCode.RuntimeError => returnCode match {
        case 1 => StatusCode.WrongAnswer
        case 2 => StatusCode.PresentationError
        case _ => StatusCode.TestingError
      }
      case _ => StatusCode.TestingError
    }
}

object SingleRunResult {
  def apply(params: LocalExecutionParameters, result: LocalExecutionResult) = new SingleRunResult(params, result)
}

object JavaRunResult {
  def apply(params: LocalExecutionParameters, result: LocalExecutionResult) = new JavaRunResult(params, result)
}

object TesterRunResult {
  def apply(params: LocalExecutionParameters, result: LocalExecutionResult) = new TesterRunResult(params, result)
}

class InteractiveRunResult(first: SingleRunResult, second: SingleRunResult) extends RunResult {
  def status =
    second.status match {
      case StatusCode.Accepted =>
        first.status match {
          case StatusCode.Accepted => StatusCode.Accepted
          case StatusCode.RuntimeError => first.returnCode match {
            case 1 => StatusCode.WrongAnswer
            case 2 => StatusCode.PresentationError
            case _ => StatusCode.TestingError
          }
          case StatusCode.TimeLimitExceeded => StatusCode.PresentationError
          case _ => StatusCode.TestingError
        }
      case StatusCode.RuntimeError =>
        first.status match {
          case StatusCode.Accepted => StatusCode.RuntimeError
          case StatusCode.RuntimeError => first.returnCode match {
            case 2 => {
              val interactorError = new String(Blobs.getBinary(first.stdErr), "UTF-8")
              if (interactorError.contains("Unexpected end of file"))
                StatusCode.RuntimeError
              else
                StatusCode.PresentationError
            }
            case 1 => StatusCode.WrongAnswer
            case _ => StatusCode.TestingError
          }
          case _ => StatusCode.TestingError
        }
      case _ => second.status
    }

  def toMap = Map("first" -> first.toMap, "second" -> second.toMap)

  def time = second.time

  def memory = second.memory

  def returnCode = second.returnCode

  def stdErr = first.stdErr
}

class StepResult(val name: String, p: LocalExecutionParameters, r: LocalExecutionResult) extends SingleRunResult(p, r) {
  override def toMap = super.toMap.+("name" -> name)
}

object StepResult {
  def apply(name: String, p: LocalExecutionParameters, r: LocalExecutionResult) =
    new StepResult(name, p, r)
}

class CompileResult(val steps: Seq[StepResult], val module: Option[Module]) extends Result {
  val success = module.isDefined
  val status = if (success) StatusCode.CompilationSuccessful else StatusCode.CompilationFailed

  override def toString =
    StatusCode(status)

  lazy val time = steps.map(_.time).sum
  lazy val memory = steps.map(_.memory).sum

  def getStd(mapper: LocalExecutionResult => Blob) =
    steps.map(x => mapper(x.result))
      .map(Blobs.getBinary(_)).map(x => new String(x, "UTF-8")).mkString("===\n").getBytes("UTF-8")

  lazy val stdOut =
    getStd(_.getStdOut)

  lazy val stdErr =
    getStd(_.getStdErr)

  def toMap: Map[String, Any] = Map(
    "steps" -> steps.map(_.toMap)
  )
}

object CompileResult {
  def apply(steps: Seq[StepResult], module: Option[Module]) = new CompileResult(steps, module)
}

object TestResult {
  def apply(solution: RunResult, tester: Option[TesterRunResult], testId: Int) =
    new TestResult(solution, tester, testId)
}

object StatusCode {
  val CompilationSuccessful = 1
  val CompilationFailed = 2

  val Accepted = 10
  val TimeLimitExceeded = 11
  val RuntimeError = 12
  val WrongAnswer = 13
  val PresentationError = 14
  val MemoryLimitExceeded = 15
  val TestingError = 16

  val Rejected = 21

  val reasons = Map(
    CompilationSuccessful -> "Compilation successful",
    CompilationFailed -> "Compilation failed",
    Accepted -> "Accepted",
    TimeLimitExceeded -> "Time limit exceeded",
    MemoryLimitExceeded -> "Memory limit exceeded",
    RuntimeError -> "Runtime error",
    WrongAnswer -> "Wrong answer",
    PresentationError -> "Presentation error",
    TestingError -> "Testing error",
    Rejected -> "Rejected"
  )

  def apply(code: Int) = reasons.getOrElse(code, "Unknown status " + code)
}

class TestResult(val solution: RunResult, val tester: Option[TesterRunResult], val testId: Int) extends Result {
  lazy val solutionStatus: Option[Int] =
    if (!solution.success)
      Some(solution.status)
    else
      None

  lazy val testerStatus: Int =
    tester.map { test =>
    val r = test.result.getReturnCode
    if (r == 0)
      StatusCode.Accepted
    else if (r == 1)
      StatusCode.WrongAnswer
    else if (r == 2)
      StatusCode.PresentationError
    else
      StatusCode.TestingError
  }.getOrElse(StatusCode.TestingError)

  lazy val status = solutionStatus.getOrElse(testerStatus)

  lazy val success = status == StatusCode.Accepted

  def toMap = Map(
    "solution" -> solution.toMap
  ) ++ tester.map("tester" -> _.toMap)

  def getTesterOutput =
    tester.map(x => Blobs.getBinary(x.stdOut)).getOrElse(Array[Byte]())

  def getTesterError =
    tester.map(x => Blobs.getBinary(x.stdErr)).getOrElse(Blobs.getBinary(solution.stdErr))

  def getTesterReturnCode =
    tester.map(_.result.getReturnCode).getOrElse(0)

  override def toString =
    "Test " + testId + ": " +
      "%s, time=%ss, memory=%s".format(StatusCode(status),
      solution.time / 1000000.0, solution.memory)
}
