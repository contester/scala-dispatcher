package org.stingray.contester.modules

import com.twitter.util.Future
import java.util.zip.ZipInputStream
import org.stingray.contester.ContesterImplicits._
import org.stingray.contester.common.Blobs
import org.stingray.contester.utils.{CommandLineTools, ExecutionArguments}
import org.stingray.contester.invokers.{RemoteFileName, Sandbox, InvokerAPI}
import org.stingray.contester.problems.TestLimits
import org.stingray.contester.proto.Local.LocalExecutionParameters

final class ModuleHandlerOps(val repr: Future[Seq[ModuleHandler]]) {
  def +(m: ModuleHandler): Future[Seq[ModuleHandler]] =
    repr.map(_ :+ m)

  def +(m: Future[Seq[ModuleHandler]]): Future[Seq[ModuleHandler]] =
    repr.join(m).map(x => x._1 ++ x._2)
}

abstract class ModuleFactory(api: InvokerAPI) {
  implicit def f2sf(x: Future[ModuleHandler]): Future[Seq[ModuleHandler]] =
    x.map(Seq(_))

  implicit def m2sf(x: ModuleHandler): Future[Seq[ModuleHandler]] =
    Future.value(Seq(x))

  implicit def s2sf(x: Seq[ModuleHandler]): Future[Seq[ModuleHandler]] =
    Future.value(x)

  implicit def m2f(x: (String) => ModuleHandler): ((String) => Future[Seq[ModuleHandler]]) =
    (s: String) => Future.value(Seq(x(s)))

  implicit def s2f(x: (String) => Seq[ModuleHandler]): ((String) => Future[Seq[ModuleHandler]]) =
    (s: String) => Future.value(x(s))

  def add(x: Iterable[RemoteFileName], f: (String) => Future[Seq[ModuleHandler]]): Future[Seq[ModuleHandler]] =
    api.glob(x, false).flatMap(found => Future.collect(found.map(_.name).headOption.toSeq.map(f)).map(_.flatten))
}

object ModuleFactory {
  def apply(api: InvokerAPI) =
    new Win32ModuleFactory(api).generate.map(x => x.flatMap(y => y.moduleTypes.map(_ -> y)).toMap)
}

object ScriptLanguage {
  val list = Set("py2", "py3")
}

class Win32ModuleFactory(api: InvokerAPI) extends ModuleFactory(api) {
  def fvs(m: ModuleHandler): Future[Seq[ModuleHandler]] = Future.value(Seq(m))
  def fcf(s: Seq[Future[Seq[ModuleHandler]]]): Future[Seq[ModuleHandler]] = Future.collect(s).map(_.flatten)

  implicit def plain2rich(x: Future[Seq[ModuleHandler]]) = new ModuleHandlerOps(x)
  implicit def rich2plain(x: ModuleHandlerOps) = x.repr

  private def win16(ntvdm: String) =
    add(api.disks / "WINDOWS" / "System32" / "cmd.exe", win16Compilers(_)) +
    new Win16BinaryHandler

  def generate: Future[Seq[ModuleHandler]] =
    add((api.disks / "mingw" / "bin" / "gcc.exe") ++ (api.disks / "Programs" / "mingw" / "bin" / "gcc.exe"), (x: String) => new GCCSourceHandler(x, false, false)) +
    add((api.disks / "mingw" / "bin" / "g++.exe") ++ (api.disks / "Programs" / "mingw" / "bin" / "g++.exe"), (x: String) => new GCCSourceHandler(x, true, false)) +
    add(api.programFiles / "Borland" / "Delphi7" / "bin" / "dcc32.exe", (x: String) => new DelphiSourceHandler(x)) +
    add((api.disks / "FPC" / "*" / "bin" / "i386-win32" / "fpc.exe") ++ (api.disks / "Programs" / "FP" / "bin" / "i386-win32" / "fpc.exe"), (x: String) => new FPCSourceHandler(x, false)) +
    add(api.disks / "WINDOWS" / "System32" / "ntvdm.exe", win16(_)) +
    add(api.disks / "WINDOWS" / "System32" / "cmd.exe", visualStudio(_)) +
    add((api.disks / "Python33" / "Python.exe") ++ (api.disks / "Programs" / "Python-3" / "Python.exe"), new PythonModuleHandler("py3", _)) +
    java + p7z + new Win32BinaryHandler

  private def win16Compilers(cmd: String): Future[Seq[ModuleHandler]] =
    add(api.disks / "Compiler" / "BP" / "Bin" / "bpc.exe", (x: String) => new BPCSourceHandler(cmd, x)) +
    add(api.disks / "Compiler" / "BC" / "Bin" / "bcc.exe",
      (x: String) => Seq(new BCCSourceHandler(cmd, x, false), new BCCSourceHandler(cmd, x, true)))

  private def visualStudio(cmd: String): Future[Seq[ModuleHandler]] =
    add(api.programFiles / "Microsoft Visual Studio*" / "Common7" / "Tools" / "vsvars32.bat",
      (x: String) => Seq(new VisualStudioSourceHandler(cmd, x), new VisualCSharpSourceHandler(cmd, x)))

  private def java: Future[Seq[ModuleHandler]] =
    add((api.disks / "Programs" / "Java-7-32" / "bin" / "java.exe") ++ (api.programFiles / "Java" / "jdk*" / "bin" / "java.exe"), (x: String) => new JavaBinaryHandler(x, false)) +
    add((api.disks / "Programs" / "Java-7-32" / "bin" / "javac.exe") ++ (api.programFiles / "Java" / "jdk*" / "bin" / "javac.exe"),
      (javac: String) =>
        add(api.programFiles / "Java" / "jdk*" / "bin" / "jar.exe", (x: String) => new JavaSourceHandler(javac, x, false)))

  private def p7z: Future[Seq[ModuleHandler]] =
    add(api.programFiles / "7-Zip" / "7z.exe", new SevenzipHandler(_))
}

class PythonModuleHandler(ext: String, val python: String) extends BinaryHandler {
  def solutionName: String = "solution.py"

  def getTesterParameters(sandbox: Sandbox, name: String,
                          arguments: List[String]): Future[LocalExecutionParameters] =
  sandbox.getExecutionParameters(
    python,  ("-O":: name :: Nil) ++ arguments)

  def getSolutionParameters(sandbox: Sandbox, name: String, test: TestLimits): Future[LocalExecutionParameters] =
    sandbox.getExecutionParameters(
      python,  "-O":: "Solution.py" :: Nil)
        .map(_.setSolution.setMemoryLimit(test.memoryLimit)
        .setTimeLimitMicros(test.timeLimitMicros))

  def moduleTypes: Iterable[String] = ext :: Nil
}

class LinuxBinaryHandler extends BinaryHandler {
  def moduleTypes = "linux-bin" :: Nil
  val binaryExt = "bin"
  val solutionName = "Solution.bin"

  def getTesterParameters(sandbox: Sandbox, name: String, arguments: List[String]) =
    sandbox.getExecutionParameters((sandbox.path / name).name, arguments)

  def getSolutionParameters(sandbox: Sandbox, name: String, test: TestLimits) =
    sandbox.getExecutionParameters((sandbox.path / "Solution.bin").name, Nil)
      .map(_.setSolution.setMemoryLimit(test.memoryLimit)
      .setTimeLimitMicros(test.timeLimitMicros))
}

class Win16BinaryHandler extends BinaryHandler {
  val moduleTypes = "com" :: Nil
  val binaryExt = "exe"
  val solutionName = "Solution.exe"

  val cmd = "C:\\Windows\\System32\\cmd.exe"

  private def w16(sandbox: Sandbox, name: String) =
    sandbox.getExecutionParameters(name, Nil).map(_.win16)
    //sandbox.getExecutionParameters(cmd, "/S /C \"" + name + "\"")

  def getTesterParameters(sandbox: Sandbox, name: String, arguments: List[String]) =
    sandbox.getExecutionParameters((sandbox.path / name).name, arguments).map(_.win16)

  def getSolutionParameters(sandbox: Sandbox, name: String, test: TestLimits) =
    w16(sandbox, (sandbox.path / "Solution.exe").name)
      .map(_.setSolution.setTimeLimitMicros(test.timeLimitMicros))
}

class Win32BinaryHandler extends BinaryHandler {
  val moduleTypes = "exe" :: "delphibin" ::  Nil
  val binaryExt = "exe"
  val solutionName = "Solution.exe"

  def getTesterParameters(sandbox: Sandbox, name: String, arguments: List[String]) =
    sandbox.getExecutionParameters((sandbox.path / name).name, arguments)

  def getSolutionParameters(sandbox: Sandbox, name: String, test: TestLimits) =
    sandbox.getExecutionParameters((sandbox.path / "Solution.exe").name, Nil)
      .map(_.setSolution.setMemoryLimit(test.memoryLimit)
      .setTimeLimitMicros(test.timeLimitMicros))
}

class WineWin32Handler extends BinaryHandler {
  val moduleTypes = "exe" ::  Nil
  //override def internal = true
  val binaryExt = "exe"
  val solutionName = "Solution.exe"

  def getTesterParameters(sandbox: Sandbox, name: String, arguments: List[String]) =
    sandbox.getExecutionParameters("/usr/bin/wine", (sandbox.path / name).name :: arguments)

  def getSolutionParameters(sandbox: Sandbox, name: String, test: TestLimits) =
    sandbox.getExecutionParameters((sandbox.path / "Solution.exe").name, Nil)
      .map(_.setSolution.setMemoryLimit(test.memoryLimit)
      .setTimeLimitMicros(test.timeLimitMicros))
}


class GCCSourceHandler(val compiler: String, cplusplus: Boolean, linux: Boolean) extends SimpleCompileHandler {
  val binaryExt = if (linux) "linux-bin" else "exe"
  private val linuxPrefix = if (linux) "linux-" else ""
  val ext = if (cplusplus) "cc" else "c"
  val moduleTypes = linuxPrefix + (if (cplusplus) "g++" else "gcc") :: Nil
  val commonFlags =  "-static" :: "-fno-optimize-sibling-calls" :: "-fno-strict-aliasing" :: "-DONLINE_JUDGE" :: "-lm" :: "-s" ::
     "-O2" :: "-o" :: "Solution." + binaryExt :: "Solution." + ext :: Nil
  val platformFlags = if (linux) ("-m32" :: commonFlags) else ("-Wl,--stack=33554432" :: commonFlags)
  val flags: ExecutionArguments = if (cplusplus) ("-x" :: "c++" :: platformFlags) else platformFlags
  val sourceName = "Solution." + ext
  val binary = "Solution." + binaryExt
}

class DelphiSourceHandler(val compiler: String) extends SimpleCompileHandler {
  val flags: ExecutionArguments = "-DONLINE_JUDGE" :: "-$M33554432,33554432" :: "-cc" :: "Solution.dpr" :: Nil
  val sourceName = "Solution.dpr"
  val binary = "Solution.exe"
  val binaryExt = "delphibin"
  val moduleTypes = "dpr" :: Nil
}

class FPCSourceHandler(val compiler: String, linux: Boolean) extends SimpleCompileHandler {
  private val linuxPrefix = if (linux) "linux-" else ""
  val binaryExt = if (linux) "linux-bin" else "exe"
  def moduleTypes = (linuxPrefix + "pp") :: Nil
  def flags: ExecutionArguments = "-dONLINE_JUDGE" :: "-Cs33554432" :: "-Mdelphi" :: "-O2" :: "-XS" :: "Solution.pp" :: "-oSolution.exe" :: Nil
  def sourceName = "Solution.pp"
  def binary = "Solution.exe"
}


class VisualStudioSourceHandler(val compiler: String, vcvars: String) extends SimpleCompileHandler {
  val clflags = "/W4" :: "/F33554432" :: "/EHsc" :: "/O2" :: "/DONLINE_JUDGE" :: Nil
  val bFlags = "/S" :: "/C" :: "\"" + (
    (CommandLineTools.quoteArgument(vcvars) :: "&&" :: "cl" :: Nil) ++
      clflags ++ ("Solution.cxx" :: Nil)).mkString(" ") + "\"" :: Nil
  val flags: ExecutionArguments = bFlags.mkString
  val sourceName = "Solution.cxx"
  val binary = "Solution.exe"
  val moduleTypes = "cxx" :: Nil
  val binaryExt = "exe"
}

class VisualCSharpSourceHandler(val compiler: String, vcvars: String) extends SimpleCompileHandler {
  val clflags = "/out:Solution.exe" :: "/o+" :: "/d:ONLINE_JUDGE" :: "/r:System.Numerics.dll" :: Nil
  val bFlags = "/S" :: "/C" :: "\"" + (
    (CommandLineTools.quoteArgument(vcvars) :: "&&" :: "csc" :: Nil) ++
      clflags ++ ("Solution.cs" :: Nil)).mkString(" ") + "\"" :: Nil
  val flags: ExecutionArguments = bFlags.mkString
  val sourceName = "Solution.cs"
  val binary = "Solution.exe"
  val moduleTypes = "cs" :: Nil
  val binaryExt = "exe"
}

class BPCSourceHandler(val compiler: String, bpc: String) extends SimpleCompileHandler {
  val bpcflags = "-$M65520,0,655360" :: "Solution.pas" :: Nil
  val flags: ExecutionArguments = "/S /C \"" + bpc + " " + CommandLineTools.quoteArguments(bpcflags) + "\""
  val sourceName = "Solution.pas"
  val binary = "Solution.exe"
  val binaryExt = "com"
  val moduleTypes = "pas" :: Nil
}

class BCCSourceHandler(val compiler: String, bcc: String, cplusplus: Boolean) extends SimpleCompileHandler {
  val ext = if (cplusplus) "cpp" else "c"
  val moduleTypes = ext :: Nil
  val bccflags = "-ml" :: "-3" :: "-O2" :: ("Solution." + ext) :: Nil
  val flags: ExecutionArguments = "/S /C \"" + bcc + " " + CommandLineTools.quoteArguments(bccflags) + "\""
  val sourceName = "Solution." + ext
  val binary = "Solution.exe"
  val binaryExt = "com"
}

class JavaBinaryHandler(val java: String, linux: Boolean) extends BinaryHandler {
  val binaryExt = "jar"
  private val linuxPrefix = if (linux) "linux-" else ""
  val solutionName = "Solution.jar"
  val moduleTypes = (linuxPrefix + "jar") :: Nil

  def getTesterParameters(sandbox: Sandbox, name: String, arguments: List[String]) =
    sandbox.getExecutionParameters(
      java, "-XX:-UsePerfData" :: "-Xmx256M" :: "-DONLINE_JUDGE=true" :: "-Duser.language=en" :: "-Duser.region=US" :: "-Duser.variant=US" ::
        "-jar" :: name :: arguments)

  private def getTestLimits(test: TestLimits): List[String] = {
    val ml0 = test.memoryLimit / (1024 * 1024)
    val ml = (if (ml0 < 32) 32 else ml0).toString
    ("-Xmx" + ml + "M") :: Nil
  }

  def getSolutionParameters(sandbox: Sandbox, name: String, test: TestLimits) =
    sandbox.getExecutionParameters(
      java, getTestLimits(test) ++ ("-XX:-UsePerfData" :: "-Xss32M" :: "-Xms32M" :: "-server" :: "-DONLINE_JUDGE=true" :: "-Duser.language=en" :: "-Duser.region=US" :: "-Duser.variant=US" ::
        "-jar" :: "Solution.jar" :: Nil))
      .map(_.setTimeLimitMicros(test.timeLimitMicros).setSolution)
}

class JavaSourceHandler(val javac: String, val jar: String, linux: Boolean) extends SourceHandler {
  private val linuxPrefix = if (linux) "linux-" else ""
  val binaryExt = if (linux) "linux-jar" else "jar"
  val javacFlags = "Solution.java" :: Nil
  val jarFlags = "cmf" :: "manifest.mf" :: "Solution.jar" :: Nil
  val moduleTypes = (linuxPrefix + "java") :: Nil
  val sourceName = "Solution.java"

  def unpackAddon(sandbox: Sandbox) = {
    val addon = new ZipInputStream(this.getClass.getResourceAsStream("/JavaRunner.zip"))
    Future.collect(Stream.continually(addon.getNextEntry)
      .takeWhile(_ != null)
      .filter(!_.isDirectory).map { entry =>
      val buffer = new Array[Byte](entry.getSize.toInt)
      addon.read(buffer)
      sandbox.put(Blobs.storeBinary(buffer), entry.getName)
    }).unit
  }

  def compile(sandbox: Sandbox) =
    SourceHandler.stepAndCheck("Compilation", sandbox, javac, javacFlags, "Solution.class")
      .flatMap {
      case (compileStepResult, success) =>
        if (compileStepResult.success && success) {
          unpackAddon(sandbox).flatMap { _ =>
            if (linux) {
              sandbox.glob("*.class", false).map(_.isFile.map(_.basename))
            } else Future.value("*.class" :: Nil)
          }.flatMap { classlist =>
            SourceHandler.stepAndCheck("JAR Creation", sandbox, jar, jarFlags ++ classlist, "Solution.jar")
              .map {
              case (jarResult, locsuccess) =>
                Seq(compileStepResult, jarResult) -> locsuccess
            }
          }
        } else
          Future.value(Seq(compileStepResult), false)
    }.map {
      case (steps, success) =>
        SourceHandler.makeCompileResultAndModule(steps, success, "Solution.jar", binaryExt)
    }
}

/*
call "C:\Program Files\Microsoft Visual Studio 8\Common7\Tools\vsvars32.bat"
csc /out:Solution.csexe %1

Csexe altermemlimit=None

 */
