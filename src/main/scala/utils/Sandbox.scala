package org.stingray.contester.utils

import com.twitter.util.Future
import org.stingray.contester.common.{Module, ObjectStoreModule}
import org.stingray.contester.invokers.{RemoteFileName, Sandbox}

object SandboxUtil {
  /**
    * Copy a file from sandbox.
    *
    * @param sandbox
    * @param name
    * @param remote
    * @param moduleType
    * @return
    */
  def copyFromSandbox(sandbox: Sandbox, name: String, remote: RemoteFileName, moduleType: Option[String]): Future[Option[String]] =
    sandbox.getGridfs(Seq((remote, name, moduleType))).map { files =>
      files.headOption.map(_.checksum.getOrElse("deadbeef"))
    }

  /**
    * Put filename from sandbox as named module.
    * @param sandbox
    * @param name
    * @param remote
    * @param moduleType
    * @return
    */
  def putModule(sandbox: Sandbox, name: String, remote: RemoteFileName, moduleType: String): Future[Option[Module]] =
    copyFromSandbox(sandbox, name, remote, Some(moduleType)).map { checksum =>
      checksum.map(new ObjectStoreModule(name, moduleType, _))
    }
}