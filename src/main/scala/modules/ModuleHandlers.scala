package org.stingray.contester.modules

import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.apache.commons.io.IOUtils
import org.stingray.contester.ContesterImplicits._
import org.stingray.contester.common.Blobs
import org.stingray.contester.invokers.{InvokerAPI, RemoteFileName, Sandbox}
import org.stingray.contester.problems.TestLimits
import org.stingray.contester.proto.LocalExecutionParameters
import org.stingray.contester.utils.{CommandLineTools, ExecutionArguments}

import scala.reflect.ClassTag

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

case class SpecializedModuleFactory(modules: Map[String, ModuleHandler]) extends Logging {
  def keySet = modules.keySet

  def getSource(name: String): Option[SourceHandler] =
    modules.get(name).flatMap { m =>
      m match {
        case v: SourceHandler => Some(v)
        case _ => None
      }
    }

  def getBinary(name: String): Option[BinaryHandler] =
    modules.get(name).flatMap { m =>
      m match {
        case v: BinaryHandler => Some(v)
        case _ => None
      }
    }

  def get7z: Option[SevenzipHandler] =
    modules.get("zip").flatMap { m =>
      m match {
        case v: SevenzipHandler => Some(v)
        case _ => None
      }
    }
}

object ModuleFactory {
  def apply(api: InvokerAPI): Future[SpecializedModuleFactory] =
    new Win32ModuleFactory(api).generate.map { x =>
      SpecializedModuleFactory(byTypes(x))
    }

  private def byTypes[A <: ModuleHandler](modules: Iterable[A]) =
    modules.flatMap(module => module.moduleTypes.map(_ -> module)).toMap
}

object ScriptLanguage {
  val list = Set("py2", "py3")
}

class Win32ModuleFactory(api: InvokerAPI) extends ModuleFactory(api) {
  def fvs(m: ModuleHandler): Future[Seq[ModuleHandler]] = Future.value(Seq(m))

  def fcf(s: Seq[Future[Seq[ModuleHandler]]]): Future[Seq[ModuleHandler]] = Future.collect(s).map(_.flatten)

  implicit def plain2rich(x: Future[Seq[ModuleHandler]]): ModuleHandlerOps = new ModuleHandlerOps(x)

  implicit def rich2plain(x: ModuleHandlerOps): Future[Seq[ModuleHandler]] = x.repr

  def generate: Future[Seq[ModuleHandler]] =
    add((api.disks / "mingw" / "bin" / "gcc.exe") ++ (api.disks / "Programs" / "mingw" / "bin" / "gcc.exe"), (x: String) => new GCCSourceHandler(x, false, false, false)) +
      add((api.disks / "mingw" / "bin" / "g++.exe") ++ (api.disks / "Programs" / "mingw" / "bin" / "g++.exe"), (x: String) => new GCCSourceHandler(x, true, false, false)) +
      add((api.disks / "mingw" / "bin" / "g++.exe") ++ (api.disks / "Programs" / "mingw" / "bin" / "g++.exe"), (x: String) => new GCCSourceHandler(x, true, false, true)) +
      add(api.programFiles / "Borland" / "Delphi7" / "bin" / "dcc32.exe", (x: String) => new DelphiSourceHandler(x)) +
      add(api.programFiles / "Mono" / "bin" / "mono.exe", (x: String) => new MonoBinaryHandler(x)) +
      add((api.disks / "FPC" / "*" / "bin" / "i386-win32" / "fpc.exe") ++ (api.disks / "Programs" / "FP" / "bin" / "i386-win32" / "fpc.exe"), (x: String) => new FPCSourceHandler(x, false)) +
      add((api.programFiles / "PascalABC.NET" / "pabcnetcclear.exe") ++ (api.disks / "Programs" / "PascalABC.NET" / "pabcnetcclear.exe"), (x: String) => new PascalABCSourceHandler(x)) +
      add(api.disks / "WINDOWS" / "System32" / "ntvdm.exe", win16(_)) +
      add(api.disks / "WINDOWS" / "System32" / "cmd.exe", visualStudio(_)) +
      add((api.disks / "Python37" / "Python.exe") ++ (api.disks / "Python36" / "Python.exe") ++ (api.disks / "Python35" / "Python.exe") ++ (api.disks / "Python34" / "Python.exe") ++ (api.disks / "Programs" / "Python-3" / "Python.exe"), new PythonModuleHandler("py3", _)) +
      add((api.disks / "Python27" / "Python.exe") ++ (api.disks / "Programs" / "Python-2" / "Python.exe"), new PythonModuleHandler("py2", _)) +
      add(api.disks / "WINDOWS" / "System32" / "cmd.exe", java(_)) + p7z + new Win32BinaryHandler

  private def win16(ntvdm: String) =
    add(api.disks / "WINDOWS" / "System32" / "cmd.exe", win16Compilers(_)) +
      new Win16BinaryHandler

  private def win16Compilers(cmd: String): Future[Seq[ModuleHandler]] =
    add(api.disks / "Compiler" / "BP" / "Bin" / "bpc.exe", (x: String) => new BPCSourceHandler(cmd, x)) +
      add(api.disks / "Compiler" / "BC" / "Bin" / "bcc.exe",
        (x: String) => Seq(new BCCSourceHandler(cmd, x, false), new BCCSourceHandler(cmd, x, true)))

  private def visualStudio(cmd: String): Future[Seq[ModuleHandler]] =
    // api.programFiles / "Microsoft Visual Studio*" / "Common7" / "Tools" / "vsvars32.bat"
    add(api.programFiles / "Microsoft Visual Studio" / "2019"/ "Community"/ "VC" / "Auxiliary" / "Build" / "vcvars32.bat",
      (x: String) => Seq(new VisualStudioSourceHandler(cmd, x), new VisualCSharpSourceHandler(cmd, x)))

  private def java(cmd: String): Future[Seq[ModuleHandler]] =
    add((api.disks / "Programs" / "jdk*" / "bin" / "java.exe") ++ (api.programFiles / "Java" / "jdk*" / "bin" / "java.exe"), (x: String) => new JavaBinaryHandler(x, false)) +
      add((api.disks / "Programs" / "Java-7-32" / "bin" / "javac.exe") ++ (api.programFiles / "Java" / "jdk*" / "bin" / "javac.exe"),
        (javac: String) =>
          add(api.programFiles / "Java" / "jdk*" / "bin" / "jar.exe", (x: String) => new JavaSourceHandler(cmd, javac, false)))

  private def p7z: Future[Seq[ModuleHandler]] =
    add(api.programFiles / "7-Zip" / "7z.exe", new SevenzipHandler(_))
}

class PythonModuleHandler(ext: String, val python: String) extends BinaryHandler {
  def solutionName: String = "solution.py"

  def getTesterParameters(sandbox: Sandbox, name: String,
                          arguments: List[String]): Future[LocalExecutionParameters] =
    sandbox.getExecutionParameters(
      python, ("-O" :: name :: Nil) ++ arguments)

  def getSolutionParameters(sandbox: Sandbox, name: String, test: TestLimits): Future[LocalExecutionParameters] =
    sandbox.getExecutionParameters(
      python, "-O" :: "Solution.py" :: Nil)
      .map(_.setSolution.setMemoryLimit(test.memoryLimit)
        .setTimeLimitMicros(test.timeLimitMicros))

  def moduleTypes: Iterable[String] = ext :: Nil
}

class MonoBinaryHandler(val monoPath: String) extends BinaryHandler {
  def solutionName: String = "solution.exe"

  def getTesterParameters(sandbox: Sandbox, name: String,
                          arguments: List[String]): Future[LocalExecutionParameters] =
    sandbox.getExecutionParameters(
      monoPath, (name :: Nil) ++ arguments)

  def getSolutionParameters(sandbox: Sandbox, name: String, test: TestLimits): Future[LocalExecutionParameters] =
    sandbox.getExecutionParameters(
      monoPath, "solution.exe" :: Nil)
      .map(_.setSolution.setMemoryLimit(test.memoryLimit)
        .setTimeLimitMicros(test.timeLimitMicros))

  def moduleTypes: Iterable[String] = "mono" :: "csexe" :: Nil
}

class LinuxBinaryHandler extends BinaryHandler {
  val binaryExt = "bin"
  val solutionName = "Solution.bin"

  def moduleTypes = "linux-bin" :: Nil

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

  def getTesterParameters(sandbox: Sandbox, name: String, arguments: List[String]) =
    sandbox.getExecutionParameters((sandbox.path / name).name, arguments).map(_.win16)

  //sandbox.getExecutionParameters(cmd, "/S /C \"" + name + "\"")

  def getSolutionParameters(sandbox: Sandbox, name: String, test: TestLimits) =
    w16(sandbox, (sandbox.path / "Solution.exe").name)
      .map(_.setSolution.setTimeLimitMicros(test.timeLimitMicros))

  private def w16(sandbox: Sandbox, name: String) =
    sandbox.getExecutionParameters(name, Nil).map(_.win16)
}

class Win32BinaryHandler extends BinaryHandler {
  val moduleTypes = "exe" :: "delphibin" :: Nil
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
  val moduleTypes = "exe" :: Nil
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


class GCCSourceHandler(val compiler: String, cplusplus: Boolean, linux: Boolean, c11: Boolean) extends SimpleCompileHandler {
  val binaryExt = if (linux) "linux-bin" else "exe"
  val ext = if (cplusplus) "cc" else "c"
  private val linuxPrefix = if (linux) "linux-" else ""
  val moduleTypes = linuxPrefix + (if (cplusplus) (if (c11) "cc14" else "g++") else "gcc") :: Nil
  val commonFlags = "-static" :: "-DONLINE_JUDGE" :: "-lm" :: "-s" ::
    "-O2" :: "-o" :: "Solution." + binaryExt :: "Solution." + ext :: Nil
  val platformFlags = if (linux) ("-m32" :: commonFlags) else ("-Wl,--stack=67108864" :: commonFlags)
  val pflags01: Seq[String] = if (c11) "-std=c++14" :: Nil else Nil
  val flags: ExecutionArguments = if (cplusplus) ("-x" :: "c++" :: platformFlags) ++ pflags01 else platformFlags
  val sourceName = "Solution." + ext
  val binary = "Solution." + binaryExt
}

class DelphiSourceHandler(val compiler: String) extends SimpleCompileHandler {
  val flags: ExecutionArguments = "-DONLINE_JUDGE" :: "-$M67108864,67108864" :: "-cc" :: "Solution.dpr" :: Nil
  val sourceName = "Solution.dpr"
  val binary = "Solution.exe"
  val binaryExt = "delphibin"
  val moduleTypes = "dpr" :: Nil
}

class FPCSourceHandler(val compiler: String, linux: Boolean) extends SimpleCompileHandler {
  val binaryExt = if (linux) "linux-bin" else "exe"
  private val linuxPrefix = if (linux) "linux-" else ""

  def moduleTypes = (linuxPrefix + "pp") :: Nil

  def flags: ExecutionArguments = "-O2" :: "-dONLINE_JUDGE" :: "-Cs67107839" :: "-XS" :: "Solution.pas" :: "-oSolution.exe" :: Nil

  def sourceName = "Solution.pas"

  def binary = "Solution.exe"
}

class PascalABCSourceHandler(val compiler: String) extends SimpleCompileHandler {
  val binaryExt = "exe"
  val moduleTypes = "pascalabc" :: Nil
  val sourceName = "Solution.pas"
  val binary = "Solution.exe"

  def flags: ExecutionArguments = "Solution.pas" :: Nil
}

class VisualStudioSourceHandler(val compiler: String, vcvars: String) extends SimpleCompileHandler {
  val clflags = "/W4" :: "/F67108864" :: "/EHsc" :: "/O2" :: "/DONLINE_JUDGE" :: Nil
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
  private val linuxPrefix = if (linux) "linux-" else ""
  val binaryExt = "jar"
  val solutionName = "Solution.jar"
  val moduleTypes = (linuxPrefix + "jar") :: Nil

  override def prepare(sandbox: Sandbox): Future[Unit] =
    JavaUtils.resourcesToSandbox(sandbox, "contesteragent.jar", "java.policy").unit

  def getTesterParameters(sandbox: Sandbox, name: String, arguments: List[String]) =
    sandbox.getExecutionParameters(
      java, "-XX:-UsePerfData" :: "-Xmx256M" :: "-DONLINE_JUDGE=true" :: "-Duser.language=en" :: "-Duser.region=US" :: "-Duser.variant=US" ::
        "-jar" :: name :: arguments)

  def getSolutionParameters(sandbox: Sandbox, name: String, test: TestLimits) =
    sandbox.getExecutionParameters(
      java, getTestLimits(test) ++ ("-XX:-UsePerfData" :: "-Xss64M" :: "-DONLINE_JUDGE=true" ::
        "-Duser.language=en" :: "-Duser.region=US" :: "-Duser.variant=US" ::
        "-Djava.security.manager" :: "-Djava.security.policy=java.policy" :: "-javaagent:contesteragent.jar" ::
        "-jar" :: "Solution.jar" :: Nil))
      .map(_.setTimeLimitMicros(test.timeLimitMicros).setSolution)

  private def getTestLimits(test: TestLimits): List[String] = {
    val ml0 = test.memoryLimit / (1024 * 1024)
    val ml = (if (ml0 < 32) 32 else ml0).toString
    ("-Xmx" + ml + "M") :: ("-Xms" + ml + "M") :: Nil
  }
}

object JavaUtils {
  def resourcesToSandbox(sandbox: Sandbox, names: String*) =
    Future.collect(names.map(resourceToSandbox(sandbox, _)))

  private def resourceToSandbox(sandbox: Sandbox, name: String) =
    readResource("/" + name).flatMap(buffer => sandbox.put(Blobs.storeBinary(buffer), name))

  private def readResource(s: String): Future[Array[Byte]] =
    Future {
      val addon = this.getClass.getResourceAsStream(s)
      try {
        IOUtils.toByteArray(addon)
      } finally {
        addon.close()
      }
    }
}

class JavaSourceHandler(val compiler: String, val javac: String, linux: Boolean) extends SimpleCompileHandler {
  private val linuxPrefix = if (linux) "linux-" else ""
  val binaryExt = if (linux) "linux-jar" else "jar"
  val flags: ExecutionArguments = "/S /C javac-ext.bat Solution.java"
  val jarFlags = "cmf" :: "manifest.mf" :: "Solution.jar" :: Nil
  val moduleTypes = (linuxPrefix + "java") :: Nil
  val sourceName = "Solution.java"
  val binary = "Solution.jar"

  override def compile(sandbox: Sandbox): Future[CompileResultAndModule] =
    unpackAddon(sandbox).flatMap { _ =>
      super.compile(sandbox)
    }

  def unpackAddon(sandbox: Sandbox) =
    JavaUtils.resourcesToSandbox(sandbox, "javac-ext.bat").unit
}

class KumirSourceHandler(baseDir: String) extends SourceHandler {
  override val sourceName: String = "solution.kum"

  /**
    * Compile module which is already in the sandbox.
    *
    * @param sandbox Sandbox to use for compilation.
    * @return Result and file name, if any.
    */
  override def compile(sandbox: Sandbox): Future[CompileResultAndModule] = {
    ???
  }

  override def moduleTypes: Iterable[String] = Seq("kum")
}

/*
call "C:\Program Files\Microsoft Visual Studio 8\Common7\Tools\vsvars32.bat"
csc /out:Solution.csexe %1

Csexe altermemlimit=None

 */
