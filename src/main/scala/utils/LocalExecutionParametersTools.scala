package org.stingray.contester.utils

import org.stingray.contester.proto.{LocalEnvironment, LocalExecutionParameters}
import org.stingray.contester.invokers.Sandbox

final class RichLocalExecutionParameters(val repr: LocalExecutionParameters) extends AnyVal {
  import collection.JavaConversions._

  def fillCommandLine(applicationName: String, arguments: ExecutionArguments) =
    repr.withApplicationName(applicationName)
      .withCommandLine(arguments.get(applicationName))
      .withCommandLineParameters(arguments.getList(applicationName))

  def outputToMemory =
    repr.withStdErr(repr.getStdErr.withMemory(true))
      .withStdOut(repr.getStdOut.withMemory(true))

  def win16 =
    repr.clearStdOut.clearStdErr.clearApplicationName

  def setCompiler =
    outputToMemory.withTimeLimitHardMicros(30 * 1000000).withJoinStdoutStderr(true)

  def setSolution =
    repr.withCheckIdleness(true).withRestrictUi(true).withProcessLimit(1)

  def setSanitizer() =
    repr.withNoJob(true)

  def setTester =
    outputToMemory.withTimeLimitHardMicros(120 * 1000000).withNoJob(true).withJoinStdoutStderr(true)

  def emulateStdio(s: Sandbox) =
    repr.withStdIn(repr.getStdIn.withFilename((s.path / "input.txt").name))
      .withStdOut(repr.getStdOut.withFilename((s.path / "output.txt").name))

  def emulateStdioIf(x: Boolean, s: Sandbox) =
    if (x) emulateStdio(s) else repr

  def setMemoryLimit(limit: Long) =
    repr.withMemoryLimit(limit)

  def setTimeLimitMicros(limit: Long) =
    repr.withTimeLimitMicros(limit)

  def setEnvironment(env: LocalEnvironment) =
    repr.withEnvironment(env)

  def setSandboxId(sandboxId: String) =
    repr.withSandboxId(sandboxId)

  def setCurrentAndTemp(path: String) =
    repr.withCurrentDirectory(path).withEnvironment(LocalEnvironmentTools.setTempTo(repr.getEnvironment, path))
}

