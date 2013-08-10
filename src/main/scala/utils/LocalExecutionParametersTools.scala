package org.stingray.contester.utils

import org.stingray.contester.proto.Local.{LocalEnvironment, LocalExecutionParameters}
import org.stingray.contester.invokers.Sandbox

final class RichLocalExecutionParameters(val repr: LocalExecutionParameters) {
  import collection.JavaConversions._

  def fillCommandLine(applicationName: String, arguments: ExecutionArguments) =
    repr.toBuilder.setApplicationName(applicationName)
      .setCommandLine(arguments.get(applicationName))
      .addAllCommandLineParameters(arguments.getList(applicationName)).build()

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

  def setSanitizer =
    repr.toBuilder.setNoJob(true).build()

  def setTester =
    outputToMemory.toBuilder.setTimeLimitHardMicros(120 * 1000000).setNoJob(true).build()

  def emulateStdio(s: Sandbox) = {
    val builder = repr.toBuilder
    builder.getStdInBuilder.setFilename((s.path / "input.txt").name)
    builder.getStdOutBuilder.setFilename((s.path / "output.txt").name)
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

