package org.stingray.contester.utils

import org.stingray.contester.proto.LocalEnvironment

object LocalEnvironmentTools {
  val quote = "\""
  val win32ReservedVars = Set("ALLUSERSPROFILE", "CommonProgramFiles", "ComSpec", "NUMBER_OF_PROCESSORS", "OS",
    "PATHEXT", "PATH",
    "PROCESSOR_ARCHITECTURE", "PROCESSOR_IDENTIFIER", "PROCESSOR_LEVEL", "PROCESSOR_REVISION",
    "ProgramFiles", "PROMPT", "SystemDrive", "SystemRoot", "windir").map(_.toLowerCase)
  val tempVars = "TEMP" :: "TMP" :: "TMPDIR" :: Nil

  def sanitizeLocalEnv(env: LocalEnvironment, extraVars: Set[String] = Set()) =
    env.withEmpty(true).withVariable(env.variable.filter(x => win32ReservedVars(x.name.toLowerCase) || extraVars(x.name.toLowerCase)))

  def setEnvVars(env: LocalEnvironment, vars: Map[String, String]): LocalEnvironment = {
    val newVars = env.variable.filterNot(v => vars.contains(v.name)) ++ vars.map(x => LocalEnvironment.Variable(x._1, Some(x._2)))
    env.withVariable(newVars)
  }

  def setTempTo(env: LocalEnvironment, path: String) =
    setEnvVars(env, tempVars.map(x => x -> path).toMap)
}

