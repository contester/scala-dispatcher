package org.stingray.contester.common

import com.twitter.util.Future
import org.apache.commons.io.FilenameUtils
import org.stingray.contester.invokers.Sandbox

trait CompiledModuleStore {
  def compiledModule: String
}

trait TestOutputStore {
  def testOutput(test: Int): String
}

trait TestingResultStore extends CompiledModuleStore with TestOutputStore
trait SingleTestStore extends CompiledModuleStore {
  def output: String
}

class InstanceSubmitTestingHandle(submit: String, testingId: Int) extends TestingResultStore {
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

class ByteBufferModule(moduleTypeRaw: String, content: Array[Byte]) extends Module {
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

