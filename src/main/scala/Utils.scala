package org.stingray.contester

import ContesterImplicits._
import org.stingray.contester.proto.Local.{BinaryTypeResponse, LocalExecutionParameters, LocalEnvironment}


object EnvUtil {
  val quote = "\""
  val win32ReservedVars = Set("ALLUSERSPROFILE", "CommonProgramFiles", "ComSpec", "NUMBER_OF_PROCESSORS", "OS",
    "PATHEXT", "PATH",
    "PROCESSOR_ARCHITECTURE", "PROCESSOR_IDENTIFIER", "PROCESSOR_LEVEL", "PROCESSOR_REVISION",
    "ProgramFiles", "PROMPT", "SystemDrive", "SystemRoot", "windir").map(_.toLowerCase)
  val tempVars = "TEMP" :: "TMP" :: "TMPDIR" :: Nil

  def envVarToTuple(v: LocalEnvironment.Variable) =
    v.getName -> v.getValue

  def tupleToEnvVar(v: Tuple2[String, String]) =
    LocalEnvironment.Variable.newBuilder().setName(v._1).setValue(v._2).build()

  def envToDict(env: LocalEnvironment) = {
    import collection.JavaConversions._
    env.getVariableList.map(envVarToTuple).toMap
  }

  def dictToEnv(dict: Map[String, String]) = {
    import collection.JavaConversions.asJavaIterable
    LocalEnvironment.newBuilder().addAllVariable(dict.map(tupleToEnvVar))
  }

  def filterEnv(env: LocalEnvironment)(f: Function[List[Tuple2[String, String]], List[Tuple2[String, String]]]): LocalEnvironment = {
    import collection.JavaConversions._
    LocalEnvironment.newBuilder().addAllVariable(f(env.getVariableList.toList.map(envVarToTuple)).map(tupleToEnvVar))
      .setEmpty(env.getEmpty).build()
  }

  def setEnvEmpty(env: LocalEnvironment) =
    env.toBuilder.setEmpty(true).build()

  def filterEnvList(env: LocalEnvironment)(f: Function[String, Boolean]): LocalEnvironment =
    filterEnv(env)(x => x.filter(y => f(y._1)))

  def filterEnvListSet(env: LocalEnvironment, set: Function[String, Boolean]): LocalEnvironment =
    filterEnvList(env)(x => set(x))

  def sanitizeLocalEnv(env: LocalEnvironment, extraVars: Set[String] = Set()) =
    setEnvEmpty(filterEnvListSet(env, x => win32ReservedVars.contains(x.toLowerCase) || extraVars.contains(x.toLowerCase)))

  def setEnvVars(env: LocalEnvironment, vars: Map[String, String]): LocalEnvironment =
    filterEnv(env) { list =>
      list.filterNot(x => vars.keys.toSet.contains(x._1)) ++ vars
    }

  def setEnvVar(env: LocalEnvironment, entry: Tuple2[String, String]) =
    setEnvVars(env, Map(entry))

  def setTempTo(env: LocalEnvironment, path: String) =
    setEnvVars(env, tempVars.map(x => x -> path).toMap)
}

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
    repr.toBuilder.setCurrentDirectory(path).setEnvironment(EnvUtil.setTempTo(repr.getEnvironment, path)).build()

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
