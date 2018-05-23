package com.mchange.sc.v1.sbtethereum.repository

import com.mchange.sc.v1.sbtethereum.repository

import java.io.File
import scala.collection._
import com.mchange.sc.v1.consuela.io.ensureUserOnlyDirectory
import com.mchange.sc.v3.failable._
import com.mchange.sc.v3.failable.logging._

object SolcJ extends PermissionsOverrideSource {
  val DirName = "solcJ"

  private def repositoryToSolcJ( repositoryDir : File ) : File = new File( repositoryDir, DirName )

  private [repository]
  lazy val Directory_ExistenceAndPermissionsUnenforced : Failable[File] = repository.Directory_ExistenceAndPermissionsUnenforced.map( repositoryToSolcJ  )

  lazy val Directory : Failable[File] = repository.Directory.map( repositoryToSolcJ ).flatMap( ensureUserOnlyDirectory )

  def userReadOnlyFiles   : immutable.Set[File] = immutable.Set.empty[File]
  val userExecutableFiles : immutable.Set[File] = {
    def solcExecutable( f : File ) = {
      def goodName = {
        val name = f.getName()
        name.equals( "solc" ) || name.equals( "solc.exe" )
      }
      f.exists() && f.isFile() && goodName
    }

    SolcJ.Directory_ExistenceAndPermissionsUnenforced.map { dir =>
      if ( dir.exists() && dir.canRead() ) recursiveListBeneath(dir).filter( solcExecutable ).toSet else immutable.Set.empty[File]
    }.xwarn( "Failed to read SolcJ directory.").getOrElse( immutable.Set.empty[File] )
  }

  
}
