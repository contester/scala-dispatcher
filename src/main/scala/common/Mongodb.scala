package org.stingray.contester.common

import com.mongodb.casbah.{MongoConnection, MongoDB}
import com.mongodb.casbah.gridfs.GridFS

class MongoDBInstance(val db: MongoDB, val fs: GridFS) {
  val objectStore = new GridfsObjectStore(fs)

  def this(db: MongoDB) =
    this(db, GridFS(db))

  def this(conn: MongoConnection, db: String) =
    this(conn.getDB(db))

  def this(host: String, db: String) =
    this(MongoConnection(host), db)
}