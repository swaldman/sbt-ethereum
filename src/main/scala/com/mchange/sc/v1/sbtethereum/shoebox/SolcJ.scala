package com.mchange.sc.v1.sbtethereum.shoebox

import com.mchange.sc.v1.sbtethereum.shoebox
import com.mchange.sc.v1.sbtethereum.recursiveListBeneath

import java.io.File
import scala.collection._
import com.mchange.sc.v1.consuela.io.ensureUserOnlyDirectory
import com.mchange.sc.v3.failable._
import com.mchange.sc.v3.failable.logging._

import com.mchange.sc.v1.log.MLevel._

object SolcJ {
  implicit lazy val logger : com.mchange.sc.v1.log.MLogger = mlogger( this )
}
class SolcJ( parent : Shoebox ) extends PermissionsOverrideSource with AutoResource.UserOnlyDirectory.Owner {
  import SolcJ.logger

  val DirName = "solcJ"

  private [shoebox]
  lazy val DirectoryManager = AutoResource.UserOnlyDirectory( parent.Directory_ExistenceAndPermissionsUnenforced, () => parent.Directory, DirName )

  def userReadOnlyFiles   : immutable.Set[File] = immutable.Set.empty[File]

  val userExecutableFiles : immutable.Set[File] = {
    def solcExecutable( f : File ) = {
      def goodName = {
        val name = f.getName()
        name.equals( "solc" ) || name.equals( "solc.exe" )
      }
      f.exists() && f.isFile() && goodName
    }
    this.Directory_ExistenceAndPermissionsUnenforced.map { dir =>
      if ( dir.exists() && dir.canRead() ) recursiveListBeneath(dir).filter( solcExecutable ).toSet else immutable.Set.empty[File]
    }.xwarn( "Failed to read SolcJ directory.").getOrElse( immutable.Set.empty[File] )
  }

  def reset() : Unit = DirectoryManager.reset()
}
