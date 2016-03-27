package org.stingray.contester.common

import com.twitter.util.Future
import org.stingray.contester.invokers.{InvokerRemoteFile, RemoteFileName, Sandbox}
import com.mongodb.casbah.gridfs.GridFS
import java.io.InputStream
import com.mongodb.casbah.commons.MongoDBObject
import org.apache.commons.io.FilenameUtils
import com.mongodb.DBObject

object ObjectStore {
  def getMetadataString(metadata: Map[String, Any], key: String): String =
    metadata.getOrElse(key, "") match {
      case s: String =>
        s
      case _ =>
        ""
    }
}

trait HasGridfsPath {
  def toGridfsPath: String
}

class InstanceHandle(val handle: String) {
  def submit(submitId: Int) =
    new InstanceSubmitHandle(handle, submitId)
}
class InstanceSubmitHandle(val handle: String, val submitId: Int) extends HasGridfsPath {
  def toGridfsPath: String = "submit/%s/%d".format(handle, submitId)

  def testing(testingId: Int) =
    new InstanceSubmitTestingHandle(handle, submitId, testingId)
}

class InstanceSubmitTestingHandle(val handle: String, val submitId: Int, val testingId: Int) extends HasGridfsPath {
  def toGridfsPath: String = "submit/%s/%d/%d".format(handle, submitId, testingId)

  def submit = new InstanceSubmitHandle(handle, submitId)
}

class GridfsPath(override val toGridfsPath: String) extends HasGridfsPath {
  def this(parent: HasGridfsPath, name: String) =
    this("%s/%s".format(parent.toGridfsPath, name))
}

class StoreHandle(val store: GridfsObjectStore, val handle: HasGridfsPath)

case class ObjectMetaData(originalSize: Long, sha1sum: Option[String], moduleType: Option[String], compressionType: Option[String])

object ObjectMetaData {
  def fromMongoDBObject(obj: MongoDBObject): ObjectMetaData = {
    import com.mongodb.casbah.Implicits._

    ObjectMetaData(obj.getAsOrElse[Long]("originalSize", 0), obj.getAs[String]("checksum"),
      obj.getAs[String]("moduleType"), obj.getAs[String]("compressionType"))
  }
}

/**
 * Interface to the object store.
 * @param fs gridfs to work on.
 */
class GridfsObjectStore(fs: GridFS) {
  /**
   * Put byte array with metadata as a given name.
   * @param name
   * @param content
   * @param metadata
   * @return Item's checksum
   */
  def put(name: String, content: Array[Byte], metadata: Map[String, Any]): Future[String] =
    Future {
      val checksum = "sha1:" + Blobs.bytesToString(Blobs.getSha1(content))
      fs.remove(name)
      fs(content) { attributes =>
        import com.mongodb.casbah.commons.Implicits._
        attributes.filename = name
        attributes.metaData = metadata ++ MongoDBObject("checksum" -> checksum)
      }
      checksum
    }

  /**
   * Get metadata for a file name.
   * @param name
   * @return metadata
   */
  def getMetaData(name: String): Future[Option[ObjectMetaData]] =
    Future {
      fs.findOne(name).map { file =>
        import com.mongodb.casbah.Implicits._
        ObjectMetaData.fromMongoDBObject(file.metaData)
      }
    }

  /**
   * Check if file exists.
   * @param name
   * @return
   */
  def exists(name: String): Future[Boolean] =
    getMetaData(name).map(_.isDefined)
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

