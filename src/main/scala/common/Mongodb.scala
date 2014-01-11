package org.stingray.contester.common

import com.mongodb.casbah.{MongoURI, MongoConnection, MongoDB}
import com.mongodb.casbah.gridfs.GridFS

object MongoDBInstance {
  def apply(uri: MongoURI): Either[Throwable, MongoDBInstance] =
    uri.connectDB.right.map(new MongoDBInstance(_))

  def apply(uri: String): Either[Throwable, MongoDBInstance] =
    apply(MongoURI(uri))
}

class MongoDBInstance(val db: MongoDB, val fs: GridFS) {
  val objectStore = new GridfsObjectStore(fs)

  def this(db: MongoDB) =
    this(db, GridFS(db))
}