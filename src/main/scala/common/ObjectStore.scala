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

trait HasMongoDBObject {
  def toMongoDBObject: DBObject
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
}

class GridfsObjectStore(fs: GridFS) {
  // Low-level direct access
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

  def get(name: String): Future[Option[(InputStream, String, Map[String, Any])]] = ???

  def getMetaData(name: String): Future[Option[(String, Map[String, Any])]] =
    Future {
      fs.findOne(name).map { file =>
        import com.mongodb.casbah.Implicits._
        val metadataMap: Map[String, Any] = file.metaData.map(x => x._1 -> x._2).toMap
        (ObjectStore.getMetadataString(metadataMap, "checksum"), metadataMap.filterKeys(_ != "checksum"))
      }
    }

  def exists(name: String): Future[Boolean] =
    getMetaData(name).map(_.isDefined)

  def setMetaData(name: String)(f: Map[String, Any] => Map[String, Any]): Future[Unit] =
    Future {
      fs.findOne(name).map { file =>
        import com.mongodb.casbah.Implicits._
        file.metaData = f(file.metaData.map(x => x._1 -> x._2).toMap)
        file.save()
      }
    }

  def copyFromSandbox(sandbox: Sandbox, name: String, remote: RemoteFileName, metadata: Map[String, Any]): Future[String] = {
    val moduleType = metadata.get("moduleType").flatMap { x =>
      x match {
       case s: String => Some(s)
       case _ => None
      }
    }
    sandbox.getGridfs(Seq((remote, name, moduleType))).map { files =>
      files.head.checksum.get
    }
  }

  def copyToSandbox(sandbox: Sandbox, name: String, destinationName: String): Future[InvokerRemoteFile] =
    sandbox.putGridfs(name, destinationName).map(_.get)

  // Module operations
  def putModule(name: String, moduleType: String, content: Array[Byte]): Future[Module] =
    put(name, content, Map("moduleType" -> moduleType)).map { checksum =>
      new ObjectStoreModule(name, moduleType, checksum)
    }

  def putModule(sandbox: Sandbox, name: String, remote: RemoteFileName, moduleType: String): Future[Module] =
    copyFromSandbox(sandbox, name, remote, Map("moduleType" -> moduleType)).map { checksum =>
      new ObjectStoreModule(name, moduleType, checksum)
    }

  def getModuleEx(name: String): Future[Option[(Module, Map[String, Any])]] =
    getMetaData(name).map(_.map {
      case (checksum, metadata) =>
        (new ObjectStoreModule(name, ObjectStore.getMetadataString(metadata, "moduleType"), checksum), metadata)
    })

  def getModule(name: String): Future[Option[Module]] =
    getModuleEx(name).map(_.map(_._1))
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

