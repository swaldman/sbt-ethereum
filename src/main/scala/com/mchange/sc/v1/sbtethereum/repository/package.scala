package com.mchange.sc.v1.sbtethereum

import java.io.File

import scala.collection._

import com.mchange.sc.v3.failable._

import com.mchange.sc.v1.consuela.io._

import com.mchange.sc.v2.util.Platform

import com.mchange.sc.v1.log.MLevel._

package object repository extends PermissionsOverrideSource {
  implicit lazy val logger : com.mchange.sc.v1.log.MLogger = mlogger( this )

  class CannotReadDirectoryException( msg : String, cause : Throwable = null ) extends SbtEthereumException( msg, cause )

  private val SystemProperty      = "sbt.ethereum.repository"
  private val EnvironmentVariable = "SBT_ETHEREUM_REPOSITORY"

  private [repository]
  lazy val Directory_ExistenceAndPermissionsUnenforced : Failable[File] = {
    def defaultLocation = {
      Platform.Current
        .toFailable( "Could not detect the platform to determine the repository directory" )
        .flatMap( _.appSupportDirectory( "sbt-ethereum" ) )
    }

    val mbProperty = Option( System.getProperty( SystemProperty ) )
    val mbEnvVar   = Option( System.getenv( EnvironmentVariable ) )

    (mbProperty orElse mbEnvVar).fold( defaultLocation )( dir => Failable.succeed( new File( dir ) ) )
  }


  lazy val Directory = Directory_ExistenceAndPermissionsUnenforced.flatMap( ensureUserOnlyDirectory )

  def userReadOnlyFiles   : immutable.Set[File] = Database.userReadOnlyFiles ++ Keystore.userReadOnlyFiles ++ SolcJ.userReadOnlyFiles
  def userExecutableFiles : immutable.Set[File] = Database.userExecutableFiles ++ Keystore.userExecutableFiles ++ SolcJ.userExecutableFiles

  def repairPermissions() : Failable[Unit] = Directory_ExistenceAndPermissionsUnenforced flatMap { repositoryDir =>
    val allFiles = recursiveListIncluding( repositoryDir )

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
        WARNING.log( s"Unexpectedly, the sbt-ethereum repository contains a file that is neither a directory or regular file: '${f}'. Ignoring." )
        Failable.succeed( f )
      }
    }

    val failables = allFiles.map( repair )
    Failable.sequence( failables ).map( _ => () )
  }

  // modified from Rex Kerr, https://stackoverflow.com/questions/2637643/how-do-i-list-all-files-in-a-subdirectory-in-scala
  private [repository]
  def recursiveListBeneath( dir : File ) : Array[File] = {
    assert( dir.isDirectory )
    if (! dir.canRead() ) throw new CannotReadDirectoryException( s"'${dir}' is not readable!" )
    val these = dir.listFiles
    these ++ these.filter(_.isDirectory).flatMap(recursiveListBeneath)
  }

  private [repository]
  def recursiveListIncluding( dir : File ) : Array[File] ={
    dir +: recursiveListBeneath( dir )
  }
}
