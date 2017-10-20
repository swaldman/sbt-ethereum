package com.mchange.sc.v1.sbtethereum.repository

import com.mchange.sc.v1.sbtethereum.repository

import java.io.File
import com.mchange.sc.v2.failable._
import com.mchange.sc.v1.consuela.ethereum.{clients, wallet}
import com.mchange.sc.v1.consuela.io.ensureUserOnlyDirectory

object Keystore {
  val DirName = "keystore"
  lazy val Directory : Failable[File] =
    repository.Directory.flatMap( mainDir => ensureUserOnlyDirectory( new File( mainDir, DirName ) ) )

  final object V3 {
    val DirName = "V3"
    lazy val Directory : Failable[File] =
      Keystore.Directory.flatMap( mainDir => ensureUserOnlyDirectory( new File( mainDir, DirName ) ) )

    def storeWallet( w : wallet.V3 ) : Failable[wallet.V3] =
      Directory.flatMap( clients.geth.KeyStore.add( _, w ) )
  }
}
