package com.mchange.sc.v1.sbtethereum.shoebox

import com.mchange.sc.v1.sbtethereum.shoebox
import com.mchange.sc.v1.sbtethereum.recursiveListBeneath

import java.io.File
import scala.collection._
import com.mchange.sc.v1.consuela.io.ensureUserOnlyDirectory
import com.mchange.sc.v3.failable._
import com.mchange.sc.v3.failable.logging._

object SolcJ extends PermissionsOverrideSource with AutoResource.UserOnlyDirectory.Owner {
  val DirName = "solcJ"

  private [shoebox] lazy val DirectoryManager = AutoResource.UserOnlyDirectory( shoebox.Directory_ExistenceAndPermissionsUnenforced, () => shoebox.Directory, DirName )

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

  def reset() : Unit = DirectoryManager.reset()
}
