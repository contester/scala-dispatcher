package org.stingray.contester.common

import com.twitter.util.Future
import org.stingray.contester.invokers.{RemoteFile, Sandbox}
import com.mongodb.casbah.gridfs.GridFS
import java.io.InputStream

object ObjectStore {
  def getMetadataString(metadata: Map[String, Any], key: String): String =
    metadata.getOrElse(key, "") match {
      case s: String =>
        s
      case _ =>
        ""
    }
}

trait ObjectStore {
  // Low-level operations
  def put(name: String, content: Array[Byte], metadata: Map[String, Any]): Future[String]
  def get(name: String): Future[Option[(InputStream, String, Map[String, Any])]]
  def getMetaData(name: String): Future[Option[(String, Map[String, Any])]]

  def putFromSandbox(sandbox: Sandbox, name: String, metadata: Map[String, Any]): Future[String]
  def putToSandbox(sandbox: Sandbox, name: String, destinationName: String): Future[Unit]

  // Module operations
  def putModule(name: String, moduleType: String, content: Array[Byte]): Future[Module] =
    put(name, content, Map("moduleType" -> moduleType)).map { checksum =>
      new ObjectStoreModule(name, moduleType, checksum)
    }

  def getModule(name: String): Future[Option[Module]] =
    getMetaData(name).map(_.map {
      case (checksum, metadata) =>
        new ObjectStoreModule(name, ObjectStore.getMetadataString(metadata, "moduleType"), checksum)
    })
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

object Module {

}

class GridfsObjectStore(fs: GridFS) extends ObjectStore {
  // Low-level operations
  def put(name: String, content: Array[Byte], metadata: Map[String, Any]): Future[Unit] =
    Future {
      val checksum = "sha1:" + Blobs.bytesToString(Blobs.getSha1(content))
      fs(content) { attributes =>
        attributes.filename = name
        attributes.metaData = metadata + "checksum" -> checksum
      }
    }


  def get(name: String): Future[Option[(InputStream, String, Map[String, Any])]] = ???

  def getMetaData(name: String): Future[Option[(String, Map[String, Any])]] =
    Future {
      fs.findOne(name).map { file =>
        import com.mongodb.casbah.Implicits._
        val metadataMap = file.metaData.toMap[String,Any]
        (ObjectStore.getMetadataString(metadataMap, "checksum"), metadataMap.filterKeys(_ != "checksum"))
      }
    }

  def putFromSandbox(sandbox: Sandbox, name: String, remoteName: RemoteFile, metadata: Map[String, Any]): Future[String] =
    sandbox.getGridfs()

  def putToSandbox(sandbox: Sandbox, name: String, destinationName: String): Future[Unit] = ???
}