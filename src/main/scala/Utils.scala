package org.stingray.contester

import ContesterImplicits._
import org.stingray.contester.proto.Local.{BinaryTypeResponse, LocalExecutionParameters, LocalEnvironment}
import utils.LocalEnvironmentTools

trait ExecutionArguments {
  def get(applicationName: String): String
  def getList(applicationName: String): Seq[String]
}

class ExecutionArgumentsList(val arguments: List[String]) extends ExecutionArguments {
  def get(applicationName: String) =
    (applicationName :: arguments).map(CmdlineUtil.quoteArgument(_)).mkString(" ")
  def getList(applicationName: String) =
    (applicationName :: arguments)
}

class ExecutionArgumentsString(val commandLine: String) extends ExecutionArguments {
  def get(applicationName: String) =
    (CmdlineUtil.quoteArgument(applicationName) :: commandLine :: Nil).mkString(" ")
  def getList(applicationName: String) =
    List(applicationName) ++ commandLine.split(" ")
}

class LocalExecutionParametersOps(val repr: LocalExecutionParameters) {

  import collection.JavaConversions._

  def fillCommandLine(applicationName: String, arguments: ExecutionArguments) =
    repr.toBuilder.setApplicationName(applicationName).setCommandLine(arguments.get(applicationName)).addAllCommandLineParameters(arguments.getList(applicationName)).build()

  def outputToMemory = {
    val builder = repr.toBuilder
    builder.getStdErrBuilder.setMemory(true)
    builder.getStdOutBuilder.setMemory(true)
    builder.build()
  }

  def noOutput =
    repr.toBuilder.clearStdOut().clearStdErr().build()

  def win16 =
    noOutput.toBuilder.clearApplicationName().build()

  def setCompiler =
    outputToMemory.toBuilder.setTimeLimitHardMicros(30 * 1000000).build()

  def setSolution =
    repr.toBuilder.setCheckIdleness(true).setRestrictUi(true).setProcessLimit(1).build()

  def setTester =
    outputToMemory.toBuilder.setTimeLimitHardMicros(120 * 1000000).setNoJob(true).build()

  def emulateStdio(s: Sandbox) = {
    val builder = repr.toBuilder
    builder.getStdInBuilder.setFilename((s.path ** "input.txt").name)
    builder.getStdOutBuilder.setFilename((s.path ** "output.txt").name)
    builder.build()
  }

  def emulateStdioIf(x: Boolean, s: Sandbox) =
    if (x) emulateStdio(s) else repr

  def setMemoryLimit(limit: Long) =
    repr.toBuilder.setMemoryLimit(limit).build()

  def setTimeLimitMicros(limit: Long) =
    repr.toBuilder.setTimeLimitMicros(limit).build()

  def setEnvironment(env: LocalEnvironment) =
    repr.toBuilder.setEnvironment(env).build()

  def setSandboxId(sandboxId: String) =
    repr.toBuilder.setSandboxId(sandboxId).build()

  def setCurrentAndTemp(path: String) =
    repr.toBuilder.setCurrentDirectory(path).setEnvironment(LocalEnvironmentTools.setTempTo(repr.getEnvironment, path)).build()

}

object CmdlineUtil {

  val quote = "\""

  def quoteArgument(s: String) = {
    val sp = s.split('"').toList
    val r = (sp.slice(0, sp.length).map {
      x =>
        x + ("\\" * x.reverse.prefixLength(_ == '\\'))
    } ++ sp.slice(sp.length, sp.length + 1)).mkString("\\\"")
    if (r.contains(" "))
      List(quote, r, "\\" * r.reverse.prefixLength(_ == '\\'), quote).mkString
    else r
  }

  def quoteArguments(x: Iterable[String]) =
    x.map(quoteArgument(_)).mkString(" ")

  def fillCommandLine(applicationName: String, arguments: ExecutionArguments) =
    LocalExecutionParameters.getDefaultInstance.fillCommandLine(applicationName, arguments)
}

object ExecUtil {
  def isWin16(resp: BinaryTypeResponse) =
    Set(BinaryTypeResponse.Win32BinaryType.SCS_DOS_BINARY, BinaryTypeResponse.Win32BinaryType.SCS_WOW_BINARY)(resp.getResult)
}
