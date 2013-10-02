package org.stingray.contester.db

import collection.mutable.ArrayBuffer
import com.twitter.conversions.time._
import com.twitter.util.{Future, Duration, SimplePool}
import grizzled.slf4j.Logging
import java.sql.{ResultSet, Connection}

class ConnectionPool(host: String, db: String, username: String, password: String) extends Logging {

  import java.sql.{SQLRecoverableException, SQLNonTransientException, SQLTransientException}

  private[this] def newConn =
    ConnectionPool.newConnection(host, db, username, password)

  private[this] val connections = new SimplePool[Connection](Seq())

  private[this] def asyncNewConn(): Unit =
    Future { newConn }
      .onFailure { x => error("Creating new MySQL connection", x); asyncNewConn }
      .onSuccess { x =>
        trace("Created new MySQL connection " + x)
        connections.release(x)
      }

  1 to 2 foreach { _ => asyncNewConn }

  def reserveQuery[A](f: Connection => Future[A]): Future[A] =
    connections.reserve().flatMap { c =>
      f(c).onSuccess(_ => connections.release(c))
        .rescue {
        case e @ (_: SQLRecoverableException | _: SQLNonTransientException) => {
          error("Closing connection " + c, e)
          c.close()
          asyncNewConn
          reserveQuery(f)
        }
        case e: SQLTransientException => {
          error("Transient error on " + c, e)
          connections.release(c)
          reserveQuery(f)
        }
      }
      .onFailure { e =>
        error("Exception: ", e)
        connections.release(c)
      }
    }

  def select[A](query: String, params: Any*)(f: ResultSet => A): Future[Seq[A]] =
    reserveQuery { conn =>
      NonBlocking.executeQuery(conn, query, params: _*)(f)
    }

  def execute(query: String, params: Any*): Future[UpdateResult] =
    reserveQuery { conn =>
      NonBlocking.executeUpdate(conn, query, params: _*)
    }
}

object ConnectionPool {
  private[this] val jdbcOptions: String = "?" + {
    val options = Map(
      "connectTimeout" -> 10.seconds,
      "socketTimeout" -> 10.seconds,
      "useServerPrepStmts" -> true,
      "cachePrepStmts" -> true,
      "cacheResultSetMetadata" -> true,
      "cacheServerConfiguration" -> true,
      "characterEncoding" -> "UTF-8",
      "useUnicode" -> true
      // "logger" -> classOf[MySQLLogger]
    )
    options.toList.map { case (option, value) =>
      option + "=" + (value match {
        case c: Class[_] => c.getName
        case d: Duration => d.inMilliseconds
        case _ => value
      })
    } mkString "&"
  }

  def newConnection(host: String, db: String, username: String, password: String) = {
    import java.sql.DriverManager
    DriverManager.getConnection("jdbc:mysql://" + host + "/" + db + jdbcOptions,
      username, password)
  }

  import java.sql.PreparedStatement

  def bindParameters(statement: PreparedStatement,
                     params: TraversableOnce[Any]) {
    bindParameters(statement, 1, params)
  }

  private[this] def bindParameters(statement: PreparedStatement,
                             startIndex: Int,
                             params: TraversableOnce[Any]): Int = {
    var index = startIndex
    for (param <- params) {
      param match {
        case i: Int => statement.setInt(index, i)
        case l: Long => statement.setLong(index, l)
        case s: String => statement.setString(index, s)
        case l: TraversableOnce[_] =>
          index = bindParameters(statement, index, l) - 1
        case p: Product =>
          index = bindParameters(statement, index, p.productIterator.toList) - 1
        case b: Array[Byte] => statement.setBytes(index, b)
        case b: Boolean => statement.setBoolean(index, b)
        case s: Short => statement.setShort(index, s)
        case f: Float => statement.setFloat(index, f)
        case d: Double => statement.setDouble(index, d)
        case t: java.sql.Timestamp => statement.setTimestamp(index, t)
        case _ =>
          throw new IllegalArgumentException("Unsupported data type "
            + param.asInstanceOf[AnyRef].getClass.getName + ": " + param)
      }
      index += 1
    }
    index
  }
}

case class UpdateResult(count: Int, lastInsertId: Option[Int])

private object NonBlocking {
  def executeQuery[A](c: Connection, query: String, params: Any*)(f: ResultSet => A) =
    Future(Blocking.executeQuery(c, query, params: _*)(f))

  def executeUpdate(c: Connection, query: String, params: Any*) =
    Future(Blocking.executeUpdate(c, query, params: _*))
}

private object Blocking extends Logging {
  def executeQuery[A](c: Connection, query: String, params: Any*)(f: ResultSet => A) = {
    val statement = c.prepareStatement(query)
    try {
      ConnectionPool.bindParameters(statement, params)
      trace(statement)
      val rs = statement.executeQuery()
      try {
        val results = new ArrayBuffer[A]
        while (rs.next()) {
          results += f(rs)
        }
        results
      } finally {
        rs.close()
      }
    } finally {
      statement.close()
    }
  }

  def executeUpdate(c: Connection, query: String, params: Any*) = {
    val statement = c.prepareStatement(query, java.sql.Statement.RETURN_GENERATED_KEYS)
    try {
      ConnectionPool.bindParameters(statement, params)
      trace(statement)
      val count = statement.executeUpdate()
      val rs = statement.getGeneratedKeys
      val lastInsertId = try {
        if (rs.next())
          Some(rs.getInt(1))
        else
          None
      } finally {
        rs.close()
      }
      UpdateResult(count, lastInsertId)
    } finally {
      statement.close()
    }
  }

}