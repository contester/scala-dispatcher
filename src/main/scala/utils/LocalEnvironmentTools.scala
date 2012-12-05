package org.stingray.contester.utils

import org.stingray.contester.proto.Local.LocalEnvironment

object LocalEnvironmentTools {
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

