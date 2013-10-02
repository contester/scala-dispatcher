package org.stingray.contester.utils

import org.stingray.contester.proto.Local.LocalExecutionParameters

trait ExecutionArguments {
  def get(applicationName: String): String
  def getList(applicationName: String): Seq[String]
}

class ExecutionArgumentsList(val arguments: List[String]) extends ExecutionArguments {
  def get(applicationName: String) =
    (applicationName :: arguments).map(CommandLineTools.quoteArgument).mkString(" ")
  def getList(applicationName: String) =
    applicationName :: arguments
}

class ExecutionArgumentsString(val commandLine: String) extends ExecutionArguments {
  def get(applicationName: String) =
    (CommandLineTools.quoteArgument(applicationName) :: commandLine :: Nil).mkString(" ")
  def getList(applicationName: String) =
    List(applicationName) ++ commandLine.split(" ")
}

object CommandLineTools {
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
    x.map(quoteArgument).mkString(" ")

  import org.stingray.contester.ContesterImplicits._

  def fillCommandLine(applicationName: String, arguments: ExecutionArguments) =
    LocalExecutionParameters.getDefaultInstance
      .fillCommandLine(applicationName, arguments)
}

