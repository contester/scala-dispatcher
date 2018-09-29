package org.stingray.contester.common

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, RequestBuilder, Response, Status}
import com.twitter.io.Buf
import com.twitter.util.Future
import org.apache.commons.io.FilenameUtils
import org.stingray.contester.invokers.Sandbox
import org.stingray.contester.problems.ProblemArchiveUploadException

trait SourceModuleStore {
  def sourceModule: String
}

trait CompiledModuleStore {
  def compiledModule: String
}

trait TestOutputStore {
  def testOutput(test: Int): String
}

trait TestingResultStore extends CompiledModuleStore with TestOutputStore with SourceModuleStore
trait SingleTestStore extends CompiledModuleStore with SourceModuleStore {
  def output: String
}

class InstanceSubmitTestingHandle(submit: String, testingId: Int) extends TestingResultStore {
  override def sourceModule: String = s"${submit}/sourceModule"
  def compiledModule = s"${submit}/compiledModule"
  def testOutput(test: Int) = s"${submit}/${testingId}/${test}/output"
}

object InstanceSubmitTestingHandle {
  def submit(baseUrl: String, handle: String, submitId: Int) =
    s"${baseUrl}submit/${handle}/${submitId}"

  def apply(baseUrl: String, handle: String, submitId: Int, testingId: Int) =
    new InstanceSubmitTestingHandle(submit(baseUrl, handle, submitId), testingId)
}

class CustomTestingHandle(testing: String) extends SingleTestStore {
  override def sourceModule: String = s"${testing}/sourceModule"
  override def compiledModule: String = s"${testing}/compiledModule"
  override def output: String = s"${testing}/output"
}

object CustomTestingHandle {
  def apply(baseUrl: String, handle: String, testingId: Int): CustomTestingHandle =
    new CustomTestingHandle(s"${baseUrl}eval/${handle}/${testingId}")
}

case class TestingStore(baseUrl: String, handle: String) {
  def submit(submitId: Int, testingId: Int) =
    InstanceSubmitTestingHandle(baseUrl, handle, submitId, testingId)

  def custom(testingId: Int) =
    CustomTestingHandle(baseUrl, handle, testingId)
}

trait Module {
  def moduleType: String
  def moduleHash: String

  def putToSandbox(sandbox: Sandbox, destinationName: String): Future[Unit]
}

class ObjectStoreModule(name: String, val moduleType: String, val moduleHash: String) extends Module {
  def putToSandbox(sandbox: Sandbox, destinationName: String): Future[Unit] =
    sandbox.putGridfs(name, destinationName).unit
}

class ByteBufferModule(moduleTypeRaw: String, val content: Array[Byte]) extends Module {
  val moduleHash = "sha1:" + Blobs.bytesToString(Blobs.getSha1(content)).toLowerCase
  val moduleType = Module.noDot(moduleTypeRaw)

  def putToSandbox(sandbox: Sandbox, destinationName: String): Future[Unit] =
    sandbox.put(Blobs.storeBinary(content), destinationName).unit
}

object Module {
  def extractType(filename: String) =
    FilenameUtils.getExtension(filename)

  def noDot(x: String): String =
    if (x(0) == '.')
      x.substring(1)
    else
      x
}

object ModuleUploader {
  def upload(module: ByteBufferModule, client: Service[Request, Response], baseUrl: String, name: String): Future[Unit] = {
    val xname = if (name.startsWith("filer:"))
      name.stripPrefix("filer:")
    else
      baseUrl + "fs/" + name
    val request = RequestBuilder().url(xname)
      .addHeader("X-Fs-Module-Type", module.moduleType)
      .buildPut(Buf.ByteArray.Owned(module.content))
    client(request).flatMap { r =>
      r.status match {
        case Status.Ok => Future.Done
        case x => Future.exception(ProblemArchiveUploadException(x))
      }
    }
  }
}