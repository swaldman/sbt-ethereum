package com.mchange.sc.v1.sbtethereum.repository

import com.mchange.sc.v1.sbtethereum.repository
import java.io.File
import com.mchange.sc.v1.consuela.io.ensureUserOnlyDirectory
import com.mchange.sc.v2.failable._

object SolcJ {
  val DirName = "solcJ"
  lazy val Directory : Failable[File] = repository.Directory.flatMap( mainDir => ensureUserOnlyDirectory( new File( mainDir, DirName ) ) )
}
