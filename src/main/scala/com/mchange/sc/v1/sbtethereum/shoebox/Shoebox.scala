package com.mchange.sc.v1.sbtethereum.shoebox

import java.io.File

import scala.collection._

import com.mchange.sc.v1.consuela.io._
import com.mchange.sc.v1.sbtethereum.recursiveListIncluding
import com.mchange.sc.v2.util.Platform
import com.mchange.sc.v3.failable._

import com.mchange.sc.v1.log.MLevel._

private [sbtethereum]
object Shoebox {
  implicit lazy val logger : com.mchange.sc.v1.log.MLogger = mlogger( this )
}

private [sbtethereum]
class Shoebox( shoeboxDirPathOverride : Option[String] ) extends PermissionsOverrideSource {
  import Shoebox.logger

  private val SystemProperty      = "sbt.ethereum.shoebox"
  private val EnvironmentVariable = "SBT_ETHEREUM_SHOEBOX"

  private [shoebox]
  lazy val Directory_ExistenceAndPermissionsUnenforced : Failable[File] = {
    def defaultLocation = {
      Platform.Current
        .toFailable( "Could not detect the platform to determine the shoebox directory" )
        .flatMap( _.appSupportDirectory( "sbt-ethereum" ) )
    }

    val mbProperty = Option( System.getProperty( SystemProperty ) )
    val mbEnvVar   = Option( System.getenv( EnvironmentVariable ) )

    (shoeboxDirPathOverride orElse mbProperty orElse mbEnvVar).fold( defaultLocation )( dir => Failable.succeed( new File( dir ) ) )
  }

  lazy val Directory = Directory_ExistenceAndPermissionsUnenforced.flatMap( ensureUserOnlyDirectory )

  // resettable resources
  lazy val database = new Database( this )
  lazy val keystore = new Keystore( this )
  lazy val solcJ    = new SolcJ( this )

  // not resettable resources
  lazy val abiAliasHashManager = new AbiAliasHashManager( this )
  lazy val addressAliasManager = new AddressAliasManager( this )
  lazy val backupManager       = new BackupManager( this )
  lazy val bidLog              = new BidLog( this )
  lazy val transactionLog      = new TransactionLog( this )

  def reset() : Unit = {
    database.reset()
    keystore.reset()
    solcJ.reset()
  }

  def userReadOnlyFiles   : immutable.Set[File] = database.userReadOnlyFiles ++ keystore.userReadOnlyFiles ++ solcJ.userReadOnlyFiles
  def userExecutableFiles : immutable.Set[File] = database.userExecutableFiles ++ keystore.userExecutableFiles ++ solcJ.userExecutableFiles

  def repairPermissions() : Failable[Unit] = Directory_ExistenceAndPermissionsUnenforced flatMap { shoeboxDir =>
    val allFiles = recursiveListIncluding( shoeboxDir )

    val urof = userReadOnlyFiles.map( _.getCanonicalFile )
    val uef  = userExecutableFiles.map( _.getCanonicalFile )

    def repair( f : File ) : Failable[File] = {
      if ( f.isDirectory ) {
        setUserOnlyDirectoryPermissions( f )
      }
      else if (f.isFile ) {
        val cf = f.getCanonicalFile
        if ( urof( cf ) ) setUserReadOnlyFilePermissions( cf )
        else {
          val out = setUserOnlyFilePermissions( cf )
          if ( uef( cf ) ) cf.setExecutable( true, true )
          out
        }
      }
      else {
        WARNING.log( s"Unexpectedly, the sbt-ethereum shoebox contains a file that is neither a directory or regular file: '${f}'. Ignoring." )
        Failable.succeed( f )
      }
    }

    val failables = allFiles.map( repair )
    Failable.sequence( failables ).map( _ => () )
  }

}
