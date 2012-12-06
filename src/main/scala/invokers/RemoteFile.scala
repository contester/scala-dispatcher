package org.stingray.contester.invokers

import org.apache.commons.io.FilenameUtils
import org.stingray.contester.proto.Local.FileStat

trait RemoteFile {
  def **(s: String): RemoteFile
  def **(s: Iterable[String]): Iterable[RemoteFile]
  def name: String
  def parent: String
  def basename: String
  def ext: String

  def hasStat: Boolean = false
  def isDir: Boolean = false
  def size: Long = 0
  def isFile = hasStat && !isDir

  def i: InvokerId

  override def toString = name
}

class InvokerRemoteFile(val i: InvokerId, val name: String) extends RemoteFile {
  def **(s: String) =
    new InvokerRemoteFile(i, (name :: i.pathSeparator :: s :: Nil).mkString)

  def **(s: Iterable[String]) =
    s.map(**(_))

  def basename =
    FilenameUtils.getName(name)

  def parent =
    FilenameUtils.getFullPath(name)

  def ext =
    FilenameUtils.getExtension(name)
}

class InvokerRemoteWithStats(i: InvokerId, st: FileStat) extends InvokerRemoteFile(i, st.getName) {
  override val hasStat = true
  override val isDir = st.getIsDirectory
  override val size = st.getSize
}

object InvokerRemoteFile {
  def apply(i: InvokerId, name: String) =
    new InvokerRemoteFile(i, name)

  def apply(i: InvokerId, st: FileStat) =
    new InvokerRemoteWithStats(i, st)
}

final class FileListOps(val repr: Iterable[RemoteFile]) {
  def **(d: List[String]): Iterable[RemoteFile] =
    d.flatMap(**(_))

  def **(d: String): Iterable[RemoteFile] =
    repr.map(_ ** d)

  def isDir =
    repr.filter(f => f.hasStat && f.isDir)

  def isFile =
    repr.filter(f => f.hasStat && !f.isDir)

  def firstFile =
    isFile.headOption

  def firstDir =
    isDir.headOption
}
