package com.mchange.sc.v1.sbtethereum.shoebox

import com.mchange.sc.v1.sbtethereum.shoebox

import java.io.File
import scala.collection._
import com.mchange.sc.v3.failable._
import com.mchange.sc.v3.failable.logging._
import com.mchange.sc.v1.consuela.ethereum.{clients, wallet}
import com.mchange.sc.v1.consuela.io.ensureUserOnlyDirectory

import com.mchange.sc.v1.log.MLevel._

object Keystore {
  implicit lazy val logger : com.mchange.sc.v1.log.MLogger = mlogger( this )
}
class Keystore( parent : Shoebox ) extends PermissionsOverrideSource with AutoResource.UserOnlyDirectory.Owner {
  import Keystore.logger

  val DirName = "keystore"

  private [shoebox]
  lazy val DirectoryManager = AutoResource.UserOnlyDirectory( rawParent=parent.Directory_ExistenceAndPermissionsUnenforced, enforcedParent=(() => parent.Directory), dirName=DirName )

  def reset() : Unit = {
    DirectoryManager.reset()
    V3.reset()
  }

  def userReadOnlyFiles   : immutable.Set[File] = V3.userReadOnlyFiles
  def userExecutableFiles : immutable.Set[File] = V3.userExecutableFiles

  final object V3 extends PermissionsOverrideSource with AutoResource.UserOnlyDirectory.Owner {
    val DirName = "V3"

    private [shoebox]
    lazy val DirectoryManager = AutoResource.UserOnlyDirectory( rawParent=Keystore.this.Directory_ExistenceAndPermissionsUnenforced, enforcedParent=(() => Keystore.this.Directory), dirName=DirName )

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
