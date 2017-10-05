package org.stingray.contester.invokers

import org.apache.commons.io.FilenameUtils
import org.stingray.contester.proto.FileStat

case class StorageFileName(s: String) extends AnyVal

case class CopyToStorage(local: RemoteFileName, storage: StorageFileName, moduleType: Option[String])

class RemoteFileName(val components: Seq[String], pathSeparator: Option[String]) {
  def parent: RemoteFileName =
    new RemoteFileName(components.take(components.length - 1), pathSeparator)

  def basename =
    FilenameUtils.getName(components.last)

  def ext =
    FilenameUtils.getExtension(components.last)

  def /(s: String): RemoteFileName =
    new RemoteFileName(components :+ s, pathSeparator)

  def /(s: Iterable[String]): Iterable[RemoteFileName] =
    s.map(this /)

  def this(name: String, pathSeparator: Option[String]) =
    this(RemoteFileName.parse(name), pathSeparator)

  def this(components: Seq[String]) =
    this(components, None)

  def this(name: String) =
    this(name, None)

  def name(separator: String): String =
    components.mkString(separator)

  def name: String =
    name(pathSeparator.getOrElse("\\"))
}

object RemoteFileName {
  def parse(name: String) = FilenameUtils.separatorsToUnix(name).split('/')
}

class InvokerRemoteFile(val invoker: InvokerAPI, st: FileStat)
  extends RemoteFileName(RemoteFileName.parse(st.name), Some(invoker.pathSeparator)) {

  def isDir = st.isDirectory
  def isFile = !isDir
  def size = st.size

  def checksum = if (st.checksum.isEmpty) None else Some(st.checksum.toLowerCase)
}

final class FileListOps(val repr: Iterable[RemoteFileName]) {
  def /(d: Iterable[String]): Iterable[RemoteFileName] =
    d.flatMap(this / _)

  def /(d: String): Iterable[RemoteFileName] =
    repr.map(_ / d)

  private def needStat(f: InvokerRemoteFile => Boolean): Iterable[InvokerRemoteFile] =
    repr.flatMap { item =>
      item match {
        case x: InvokerRemoteFile if f(x) =>
          Some(x)
      }
    }

  def isDir =
    needStat(_.isDir)

  def isFile =
    needStat(_.isFile)

  def firstFile =
    isFile.headOption

  def firstDir =
    isDir.headOption
}
