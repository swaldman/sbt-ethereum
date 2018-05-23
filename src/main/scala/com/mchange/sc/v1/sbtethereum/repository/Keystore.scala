package com.mchange.sc.v1.sbtethereum.repository

import com.mchange.sc.v1.sbtethereum.repository

import java.io.File
import scala.collection._
import com.mchange.sc.v3.failable._
import com.mchange.sc.v3.failable.logging._
import com.mchange.sc.v1.consuela.ethereum.{clients, wallet}
import com.mchange.sc.v1.consuela.io.ensureUserOnlyDirectory

object Keystore extends PermissionsOverrideSource {

  val DirName = "keystore"

  private def repositoryToKeystore( repositoryDir : File ) : File = new File( repositoryDir, Keystore.DirName )

  private [repository]
  lazy val Directory_ExistenceAndPermissionsUnenforced : Failable[File] = repository.Directory_ExistenceAndPermissionsUnenforced.map( repositoryToKeystore  )

  lazy val Directory : Failable[File] = repository.Directory.map( repositoryToKeystore  ).flatMap( ensureUserOnlyDirectory )

  def userReadOnlyFiles   : immutable.Set[File] = V3.userReadOnlyFiles
  def userExecutableFiles : immutable.Set[File] = V3.userExecutableFiles

  final object V3 extends PermissionsOverrideSource {
    val DirName = "V3"

    private def keystoreToV3( keystoreDir : File ) : File = new File( keystoreDir, V3.DirName )

    private [repository]
    lazy val Directory_ExistenceAndPermissionsUnenforced : Failable[File] = Keystore.Directory_ExistenceAndPermissionsUnenforced.map( keystoreToV3 )

    lazy val Directory : Failable[File] = Keystore.Directory.map( keystoreToV3 ).flatMap( ensureUserOnlyDirectory )

    def userReadOnlyFiles  : immutable.Set[File] = {
      V3.Directory_ExistenceAndPermissionsUnenforced.map { dir =>
        if ( dir.exists() && dir.canRead() ) dir.listFiles.toSet else immutable.Set.empty[File]
      }.xwarn( "Failed to read keystore V3 dir.").getOrElse( immutable.Set.empty[File] )
    }
    val userExecutableFiles : immutable.Set[File] = immutable.Set.empty[File]

    def storeWallet( w : wallet.V3 ) : Failable[wallet.V3] = Directory.flatMap( clients.geth.KeyStore.add( _, w ) )
  }
}
