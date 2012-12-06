package org.stingray.contester.modules

import com.twitter.util.Future
import java.util.zip.ZipInputStream
import org.stingray.contester.ContesterImplicits._
import org.stingray.contester._
import org.stingray.contester.common.Blobs
import proto.Local.LocalExecutionParameters
import scala.Some
import org.stingray.contester.utils.{CommandLineTools, ExecutionArguments}
import org.stingray.contester.invokers.{RemoteFile, Sandbox, InvokerId}

class Win32Handlers(i: InvokerId) {
  implicit private def f2l(x: RemoteFile) = x :: Nil
  implicit private def fo[A](x: Option[Future[A]]) = x.getOrElse(None)

  implicit private def f2s(x: RemoteFile) = x.name
  implicit private def m2f(x: ModuleHandler) = Future.value(x)
  implicit private def l2f[A](x: Seq[A]) = Future.value(x)
  implicit private def m2l(x: ModuleHandler) = Seq(x)


  implicit private def flat2list(f: Function[RemoteFile, ModuleHandler]): Function[RemoteFile, Future[Seq[ModuleHandler]]] =
    x => Future.value(Seq(f(x)))

  implicit private def sfs2fs[A](x: Seq[Future[Seq[A]]]) = Future.collect(x).map(_.flatten)

  implicit def a1(x: ModuleHandler) = Future.value(Seq(x))

  private val FirstFile = (x: Iterable[RemoteFile]) => x.firstFile
  private val FirstDir = (x: Iterable[RemoteFile]) => x.firstDir

  private def withFile(x: Iterable[RemoteFile])(f: Function[RemoteFile, Future[Seq[ModuleHandler]]]): Future[Seq[ModuleHandler]] =
    i.glob(x).flatMap { foundOpt =>
      foundOpt.firstFile.map { found =>
        f(found)
      }.getOrElse(Future.value(Seq()))
    }

  private def win32Binary = Future.value(Seq(new Win32BinaryHandler))
  private def win16: Future[Seq[ModuleHandler]] = withFile(i.disks ** "WINDOWS" ** "System32" ** "ntvdm.exe") { ntvdm =>
    Future.value(Seq(new Win16BinaryHandler)) :: win16Compilers :: Nil
  }

  private def win32Simple: Future[Seq[ModuleHandler]] =
    withFile(i.disks ** "mingw" ** "bin" ** "gcc.exe")(new GCCSourceHandler(_, false, false)) ::
      withFile(i.disks ** "mingw" ** "bin" ** "g++.exe")(new GCCSourceHandler(_, true, false)) ::
      withFile(i.programFiles ** "Borland" ** "Delphi7" ** "bin" ** "dcc32.exe")(new DelphiSourceHandler(_)) ::
      withFile(i.disks ** "FPC" ** "2.6.0" ** "bin" ** "i386-win32" ** "fpc.exe")(new FPCSourceHandler(_, false)) ::
      Nil

  private def win16Compilers: Future[Seq[ModuleHandler]] =
    withFile(i.disks ** "WINDOWS" ** "System32" ** "cmd.exe") { cmd =>
      withFile(i.disks ** "Compiler" ** "BP" ** "Bin" ** "bpc.exe")(new BPCSourceHandler(cmd, _)) ::
      withFile(i.disks ** "Compiler" ** "BC" ** "Bin" ** "bcc.exe")(x => new BCCSourceHandler(cmd, x, false) :: new BCCSourceHandler(cmd, x, true) :: Nil) ::
      Nil
    } :: Nil

  private def visualStudio: Future[Seq[ModuleHandler]] =
    withFile(i.disks ** "WINDOWS" ** "System32" ** "cmd.exe") { cmd =>
      withFile(i.programFiles ** "Microsoft Visual Studio*" ** "Common7" ** "Tools" ** "vsvars32.bat")(new VisualStudioSourceHandler(cmd, _))
    }

  private def java: Future[Seq[ModuleHandler]] =
    withFile(i.programFiles ** "Java" ** "jdk*" ** "bin" ** "java.exe")(new JavaBinaryHandler(_, false)) ::
      withFile(i.programFiles ** "Java" ** "jdk*" ** "bin" ** "javac.exe") { javac =>
        withFile(i.programFiles ** "Java" ** "jdk*" ** "bin" ** "jar.exe")(new JavaSourceHandler(javac, _, false))
      } ::
    Nil

  private def p7z: Future[Seq[ModuleHandler]] =
    withFile(i.programFiles ** "7-Zip" ** "7z.exe")(x => Future.value(Seq(new SevenzipHandler(x))))

  def apply: Future[Seq[ModuleHandler]] =
    win32Binary :: win16 :: win32Simple :: visualStudio :: java :: p7z :: Nil
}

class LinuxHandlers(i: InvokerId) {
  implicit private def f2l(x: RemoteFile) = x :: Nil
  implicit private def fo[A](x: Option[Future[A]]) = x.getOrElse(None)

  implicit private def f2s(x: RemoteFile) = x.name
  implicit private def m2f(x: ModuleHandler) = Future.value(x)
  // implicit private def l2f(x: Seq[ModuleHandler]) = x.map(Future.value(_))
  implicit private def l2f[A](x: Seq[A]) = Future.value(x)
  implicit private def m2l(x: ModuleHandler) = Seq(x)

  implicit private def flat2list(f: Function[RemoteFile, ModuleHandler]): Function[RemoteFile, Future[Seq[ModuleHandler]]] =
    x => Future.value(Seq(f(x)))

  implicit private def sfs2fs[A](x: Seq[Future[Seq[A]]]) = Future.collect(x).map(_.flatten)

  implicit def a1(x: ModuleHandler) = Future.value(Seq(x))

  private val FirstFile = (x: Iterable[RemoteFile]) => x.firstFile
  private val FirstDir = (x: Iterable[RemoteFile]) => x.firstDir

  private def withFile(x: Iterable[RemoteFile])(f: Function[RemoteFile, Future[Seq[ModuleHandler]]]): Future[Seq[ModuleHandler]] =
    i.glob(x).flatMap { foundOpt =>
      foundOpt.firstFile.map { found =>
        f(found)
      }.getOrElse(Future.value(Seq()))
    }

  private def linuxBinary = Future.value(Seq(new LinuxBinaryHandler))
  private def wineBinary = Future.value(Seq(new WineWin32Handler))

  private def linuxSimple: Future[Seq[ModuleHandler]] =
    withFile(i.programFiles ** "fpc")(new FPCSourceHandler(_, true)) ::
    withFile(i.programFiles ** "gcc")(new GCCSourceHandler(_, false, true)) ::
    withFile(i.programFiles ** "g++")(new GCCSourceHandler(_, true, true)) :: Nil

  private def linuxJava: Future[Seq[ModuleHandler]] =
    withFile(i.programFiles ** "java")(new JavaBinaryHandler(_, true)) ::
      withFile(i.programFiles ** "javac") { javac =>
        withFile(i.programFiles ** "jar")(new JavaSourceHandler(javac, _, true))
      } ::
      Nil

  def apply: Future[Seq[ModuleHandler]] =
    linuxBinary :: wineBinary :: linuxSimple :: linuxJava :: Nil
}

class LinuxBinaryHandler extends BinaryHandler {
  def moduleTypes = "linux-bin" :: Nil
  val binaryExt = "bin"
  val solutionName = "Solution.bin"

  def getTesterParameters(sandbox: Sandbox, name: String, arguments: List[String]) =
    sandbox.getExecutionParameters((sandbox.path ** name).name, arguments)

  def getSolutionParameters(sandbox: Sandbox, name: String, test: TestLimits) =
    sandbox.getExecutionParameters((sandbox.path **"Solution.bin").name, Nil)
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
    sandbox.getExecutionParameters((sandbox.path ** name).name, arguments).map(_.win16)

  def getSolutionParameters(sandbox: Sandbox, name: String, test: TestLimits) =
    w16(sandbox, (sandbox.path **"Solution.exe").name)
      .map(_.setSolution.setTimeLimitMicros(test.timeLimitMicros))
}

class Win32BinaryHandler extends BinaryHandler {
  val moduleTypes = "exe" :: "delphibin" ::  Nil
  val binaryExt = "exe"
  val solutionName = "Solution.exe"

  def getTesterParameters(sandbox: Sandbox, name: String, arguments: List[String]) =
    sandbox.getExecutionParameters((sandbox.path ** name).name, arguments)

  def getSolutionParameters(sandbox: Sandbox, name: String, test: TestLimits) =
    sandbox.getExecutionParameters((sandbox.path **"Solution.exe").name, Nil)
      .map(_.setSolution.setMemoryLimit(test.memoryLimit)
      .setTimeLimitMicros(test.timeLimitMicros))
}

class WineWin32Handler extends BinaryHandler {
  val moduleTypes = "exe" ::  Nil
  override def internal = true
  val binaryExt = "exe"
  val solutionName = "Solution.exe"

  def getTesterParameters(sandbox: Sandbox, name: String, arguments: List[String]) =
    sandbox.getExecutionParameters("/usr/bin/wine", (sandbox.path ** name).name :: arguments)

  def getSolutionParameters(sandbox: Sandbox, name: String, test: TestLimits) =
    sandbox.getExecutionParameters((sandbox.path **"Solution.exe").name, Nil)
      .map(_.setSolution.setMemoryLimit(test.memoryLimit)
      .setTimeLimitMicros(test.timeLimitMicros))
}


class GCCSourceHandler(val compiler: String, cplusplus: Boolean, linux: Boolean) extends SimpleCompileHandler {
  val binaryExt = if (linux) "linux-bin" else "exe"
  private val linuxPrefix = if (linux) "linux-" else ""
  val ext = if (cplusplus) "cc" else "c"
  val moduleTypes = linuxPrefix + (if (cplusplus) "g++" else "gcc") :: Nil
  val commonFlags =  "-fno-optimize-sibling-calls" :: "-fno-strict-aliasing" :: "-DONLINE_JUDGE" :: "-lm" :: "-s" ::
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
  override val resultType = Some("delphibin")
  val moduleTypes = "dpr" :: Nil
}

class FPCSourceHandler(val compiler: String, linux: Boolean) extends SimpleCompileHandler {
  private val linuxPrefix = if (linux) "linux-" else ""
  val binaryExt = if (linux) "linux-bin" else "exe"
  def moduleTypes = (linuxPrefix + "pp") :: Nil
  def flags: ExecutionArguments = "-dONLINE_JUDGE" :: "-Cs33554432" :: "-Mdelphi" :: "-O2" :: "-XS" :: "Solution.pp" :: "-oSolution.exe" :: Nil
  def sourceName = "Solution.pp"
  def binary = "Solution." + binaryExt
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
}


trait Win16Handler extends SimpleCompileHandler {
  override def filter(params: LocalExecutionParameters) =
    params.win16
}

class BPCSourceHandler(val compiler: String, bpc: String) extends SimpleCompileHandler {
  val bpcflags = "-$M65520,0,655360" :: "Solution.pas" :: Nil
  val flags: ExecutionArguments = "/S /C \"" + bpc + " " + CommandLineTools.quoteArguments(bpcflags) + "\""
  val sourceName = "Solution.pas"
  val binary = "Solution.exe"
  override val resultType = Some("com")
  val moduleTypes = "pas" :: Nil
}

class BCCSourceHandler(val compiler: String, bcc: String, cplusplus: Boolean) extends SimpleCompileHandler {
  val ext = if (cplusplus) "cpp" else "c"
  val moduleTypes = ext :: Nil
  val bccflags = "-ml" :: "-3" :: "-O2" :: ("Solution." + ext) :: Nil
  val flags: ExecutionArguments = "/S /C \"" + bcc + " " + CommandLineTools.quoteArguments(bccflags) + "\""
  val sourceName = "Solution." + ext
  val binary = "Solution.exe"
  override val resultType = Some("com")
}

class JavaBinaryHandler(val java: String, linux: Boolean) extends BinaryHandler {
  val binaryExt = "jar"
  private val linuxPrefix = if (linux) "linux-" else ""
  val solutionName = "Solution.jar"
  val moduleTypes = (linuxPrefix + "jar") :: Nil

  def getTesterParameters(sandbox: Sandbox, name: String, arguments: List[String]) =
    sandbox.getExecutionParameters(
      java, "-Xmx256M" :: "-DONLINE_JUDGE=true" :: "-Duser.language=en" :: "-Duser.region=US" :: "-Duser.variant=US" ::
        "-jar" :: name :: arguments)

  private def getTestLimits(test: TestLimits): List[String] = {
    val ml = (test.memoryLimit / (1024 * 1024)).toString
    ("-Xms" + ml + "M") :: ("-Xmx" + ml + "M") :: Nil
  }

  def getSolutionParameters(sandbox: Sandbox, name: String, test: TestLimits) =
    sandbox.getExecutionParameters(
      java, getTestLimits(test) ++ ("-Xss32M" :: "-Duser.language=en" :: "-Duser.region=US" :: "-Duser.variant=US" ::
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
    })
  }

  def compile(sandbox: Sandbox) =
    compileAndCheck(sandbox, javac, javacFlags, "Solution.class")
      .flatMap {
      case (compiled, classPresent) =>
        if (compiled.success && classPresent) {
        unpackAddon(sandbox).unit.flatMap { _ =>
          if (linux) {
            sandbox.glob(sandbox.sandboxId ** "*.class" :: Nil).map(_.isFile.map(_.basename))
          } else Future.value("*.class" :: Nil)
        }.flatMap { classlist =>
          step("JAR Creation", sandbox, jar, jarFlags ++ classlist)
        }.map(Seq(compiled, _))
      } else Future.value(Seq(compiled))
    }.flatMap(SourceHandler.makeCompileResult(_, sandbox, "Solution.jar", Some(binaryExt)))
}

