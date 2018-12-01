package com.mchange.sc.v1.sbtethereum.shoebox

import com.mchange.sc.v1.sbtethereum.shoebox

import java.io.File
import scala.collection._
import com.mchange.sc.v3.failable._
import com.mchange.sc.v3.failable.logging._
import com.mchange.sc.v1.consuela.ethereum.{clients, wallet}
import com.mchange.sc.v1.consuela.io.ensureUserOnlyDirectory

object Keystore extends PermissionsOverrideSource with AutoResource.UserOnlyDirectory.Owner {

  val DirName = "keystore"

  private [shoebox]
  lazy val DirectoryManager = AutoResource.UserOnlyDirectory( rawParent=shoebox.Directory_ExistenceAndPermissionsUnenforced, enforcedParent=(() => shoebox.Directory), dirName=DirName )

  def reset() : Unit = {
    DirectoryManager.reset()
    V3.reset()
  }

  def userReadOnlyFiles   : immutable.Set[File] = V3.userReadOnlyFiles
  def userExecutableFiles : immutable.Set[File] = V3.userExecutableFiles

  final object V3 extends PermissionsOverrideSource with AutoResource.UserOnlyDirectory.Owner {
    val DirName = "V3"

    private [shoebox]
    lazy val DirectoryManager = AutoResource.UserOnlyDirectory( rawParent=Keystore.Directory_ExistenceAndPermissionsUnenforced, enforcedParent=(() => Keystore.Directory), dirName=DirName )

    def reset() : Unit = DirectoryManager.reset()

    def userReadOnlyFiles  : immutable.Set[File] = {
      V3.Directory_ExistenceAndPermissionsUnenforced.map { dir =>
        if ( dir.exists() && dir.canRead() ) dir.listFiles.toSet else immutable.Set.empty[File]
      }.xwarn( "Failed to read keystore V3 dir.").getOrElse( immutable.Set.empty[File] )
    }
    val userExecutableFiles : immutable.Set[File] = immutable.Set.empty[File]

    def storeWallet( w : wallet.V3 ) : Failable[wallet.V3] = Directory.flatMap( clients.geth.KeyStore.add( _, w ) )
  }
}
