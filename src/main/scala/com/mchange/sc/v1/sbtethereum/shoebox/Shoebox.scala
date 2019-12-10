package com.mchange.sc.v1.sbtethereum.shoebox

import java.io.File

import scala.collection._

import com.mchange.sc.v1.consuela.io._
import com.mchange.sc.v1.sbtethereum.recursiveListIncluding
import com.mchange.sc.v2.util.Platform
import com.mchange.sc.v3.failable._
import com.mchange.sc.v3.failable.logging._

import com.mchange.sc.v1.log.MLevel._

private [sbtethereum]
object Shoebox {
  private implicit lazy val logger : com.mchange.sc.v1.log.MLogger = mlogger( this )

  val SystemProperty      = "sbt.ethereum.shoebox"
  val EnvironmentVariable = "SBT_ETHEREUM_SHOEBOX"

  private def mkdirsAndPermission( f : File ) : Boolean = {
    val check = f.mkdirs()
    if ( check ) {
      setUserOnlyDirectoryPermissions( f ).xwarn().isSucceeded
    }
    else {
      false
    }
  }

  // side-effecting!
  // tries to create the directory if it's not already there, so it can warn if not creatable
  def whyBadShoeboxDir( f : File ) : Option[String] = {
    if (f.exists()) {
      if (! f.isDirectory) {
        Some( s"Putative shoebox directory '${f}' exists but is not a directory!" )
      }
      else if (! (f.canRead() && f.canWrite())) {
        Some( s"Putative shoebox directory '${f}' exists but is not readable and writable by the current user!" )
      }
      else {
        None
      }
    }
    else if (! mkdirsAndPermission(f)) {
      Some( s"Putative shoebox directory '${f}' does not exist and could not be created!" )
    }
    else {
      None
    }
  }

  lazy val MaybeLocationByProperty = Option( System.getProperty( SystemProperty ) ).map { value =>
    WARNING.log( s"Platform default shoebox directory overridden by system property '${SystemProperty}' to '${value}'." )
    new File( value )
  }

  lazy val MaybeLocationByEnvVar = Option( System.getenv( EnvironmentVariable ) ).map { value =>
    WARNING.log( s"Platform default shoebox directory overridden by system property '${SystemProperty}' to '${value}'." )
    new File( value )
  }

  lazy val PlatformDefaultLocation = {
    Platform.Current
      .toFailable( "Could not detect the platform to determine the shoebox directory" )
      .flatMap( _.appSupportDirectory( "sbt-ethereum" ) )
  }

  def findWarnMaybeOverriddenPlatformDefaultDirectory : Failable[File] = {
    (MaybeLocationByProperty orElse MaybeLocationByEnvVar).fold( PlatformDefaultLocation )( dir => Failable.succeed( dir ) )
  }
}

private [sbtethereum]
class Shoebox( shoeboxDirPathOverride : Option[String] ) extends PermissionsOverrideSource {
  import Shoebox._

  private [shoebox]
  lazy val Directory_ExistenceAndPermissionsUnenforced : Failable[File] = {
    val raw = shoeboxDirPathOverride.fold( findWarnMaybeOverriddenPlatformDefaultDirectory )( dir => Failable.succeed( new File( dir ) ) )
    raw.flatMap { dir =>
      whyBadShoeboxDir( dir ) match {
        case Some( problem ) => Failable.fail( problem )
        case None            => Failable.succeed( dir )
      }
    }
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
