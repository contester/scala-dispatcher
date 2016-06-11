package org.stingray.contester.common

import org.stingray.contester.proto.Blob
import org.stingray.contester.proto.{StatusCodes, LocalExecution, LocalExecutionParameters, LocalExecutionResult}

/**
 * Result of some run, operation, or test
 */
trait Result {
  def success: Boolean
}

/**
 * Result of a single process run.
 */
trait RunResult extends Result {
  def status: StatusCodes
  def success = status == StatusCodes.ACCEPTED
  def toMap: Map[String, Any]
  def time: Long
  def memory: Long
  def returnCode: Int
  def stdErr: Blob
}

class SingleRunResult(val value: LocalExecution) extends RunResult {
  lazy val params = value.parameters
  lazy val result = value.getResult

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
      StatusCodes.TIME_LIMIT_EXCEEDED
    else if (isMemoryLimitExceeded)
      StatusCodes.MEMORY_LIMIT_EXCEEDED
    else if (isRuntimeError)
      StatusCodes.RUNTIME_ERROR
    else
      StatusCodes.ACCEPTED

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

class JavaRunResult(v: LocalExecution) extends SingleRunResult(v) {
  override def status =
    super.status match {
      case StatusCodes.RUNTIME_ERROR => returnCode match {
        case 3 => StatusCodes.MEMORY_LIMIT_EXCEEDED
        case _ => StatusCodes.RUNTIME_ERROR
      }
      case _ => super.status
    }
}

class TesterRunResult(v: LocalExecution) extends SingleRunResult(v) {
  override def status =
    super.status match {
      case StatusCodes.ACCEPTED => StatusCodes.ACCEPTED
      case StatusCodes.RUNTIME_ERROR => returnCode match {
        case 1 => StatusCodes.WRONG_ANSWER
        case 2 => StatusCodes.PRESENTATION_ERROR
        case _ => StatusCodes.TESTING_ERROR
      }
      case _ => StatusCodes.TESTING_ERROR
    }
}

object SingleRunResult {
  def combine(params: LocalExecutionParameters, result: LocalExecutionResult) =
    LocalExecution(parameters = params, result = Some(result))

  def apply(params: LocalExecutionParameters, result: LocalExecutionResult) =
    new SingleRunResult(combine(params, result))
}

object JavaRunResult {
  def apply(params: LocalExecutionParameters, result: LocalExecutionResult) =
    new JavaRunResult(SingleRunResult.combine(params, result))
}

object TesterRunResult {
  def apply(params: LocalExecutionParameters, result: LocalExecutionResult) =
    new TesterRunResult(SingleRunResult.combine(params, result))
}

class InteractiveRunResult(first: SingleRunResult, second: SingleRunResult) extends RunResult {
  def status =
    second.status match {
      case StatusCodes.ACCEPTED =>
        first.status match {
          case StatusCodes.ACCEPTED => StatusCodes.ACCEPTED
          case StatusCodes.RUNTIME_ERROR => first.returnCode match {
            case 1 => StatusCodes.WRONG_ANSWER
            case 2 => StatusCodes.PRESENTATION_ERROR
            case _ => StatusCodes.TESTING_ERROR
          }
          case StatusCodes.TIME_LIMIT_EXCEEDED => StatusCodes.PRESENTATION_ERROR
          case _ => StatusCodes.TESTING_ERROR
        }
      case StatusCodes.RUNTIME_ERROR =>
        first.status match {
          case StatusCodes.ACCEPTED => StatusCodes.RUNTIME_ERROR
          case StatusCodes.RUNTIME_ERROR => first.returnCode match {
            case 2 => {
              val interactorError = new String(Blobs.getBinary(first.stdErr), "UTF-8")
              if (interactorError.contains("Unexpected end of file"))
                StatusCodes.RUNTIME_ERROR
              else
                StatusCodes.PRESENTATION_ERROR
            }
            case 1 => StatusCodes.WRONG_ANSWER
            case _ => StatusCodes.TESTING_ERROR
          }
          case _ => StatusCodes.TESTING_ERROR
        }
      case _ => second.status
    }

  def toMap = Map("first" -> first.toMap, "second" -> second.toMap)

  def time = second.time

  def memory = second.memory

  def returnCode = second.returnCode

  def stdErr = first.stdErr
}

class StepResult(val name: String, v: LocalExecution) extends SingleRunResult(v) {
  override def toMap = super.toMap.+("name" -> name)
}

object StepResult {
  def apply(name: String, p: LocalExecutionParameters, r: LocalExecutionResult) =
    new StepResult(name, SingleRunResult.combine(p, r))
}

trait CompileResult extends Result {
  val status = if (success) StatusCodes.COMPILATION_SUCCESSFUL else StatusCodes.COMPILATION_FAILED

  override def toString =
    StatusCode(status)

  val time: Long = 0
  val memory: Long = 0

  val stdOut = "".getBytes
  val stdErr = "".getBytes

  def toMap: Map[String, Any] = Map()
}

class RealCompileResult(val steps: Seq[StepResult], override val success: Boolean) extends CompileResult {
  override val time = steps.map(_.time).sum
  override val memory = steps.map(_.memory).sum

  def getStd(mapper: LocalExecutionResult => Blob) =
    steps.map(x => mapper(x.result))
      .map(Blobs.getBinary)
      .reduce((x, y) => x ++ y)

  override val stdOut =
    getStd(_.getStdOut)

  override val stdErr =
    getStd(_.getStdErr)

  override def toMap: Map[String, Any] = Map(
    "steps" -> steps.map(_.toMap)
  )
}

object AlreadyCompiledResult extends CompileResult {
  def success: Boolean = true

  override def toString: String = super.toString + " (cached)"
}

object ScriptingLanguageResult extends CompileResult {
  def success: Boolean = true

  override def toString: String = super.toString + " (script)"
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

  def apply(code: StatusCodes) = reasons.getOrElse(code.value, "Unknown status " + code)
}

class RestoredResult(val status: Int) extends Result {
  def success: Boolean = status == 10
}

case class TestResult(solution: RunResult, tester: Option[TesterRunResult]) extends Result {
  lazy val solutionStatus: Option[StatusCodes] =
    if (!solution.success)
      Some(solution.status)
    else
      None

  lazy val testerStatus: StatusCodes =
    tester.map { test =>
    val r = test.result.getReturnCode
    if (r == 0)
      StatusCodes.ACCEPTED
    else if (r == 1)
      StatusCodes.WRONG_ANSWER
    else if (r == 2)
      StatusCodes.PRESENTATION_ERROR
    else
      StatusCodes.TESTING_ERROR
  }.getOrElse(StatusCodes.TESTING_ERROR)

  lazy val status = solutionStatus.getOrElse(testerStatus)

  lazy val success = status == StatusCodes.ACCEPTED

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
      "%s, time=%ss, memory=%s".format(StatusCode(status),
      solution.time / 1000000.0, solution.memory)
}
