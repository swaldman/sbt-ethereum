package com.mchange.sc.v1.sbtethereum

import java.io.File

import com.mchange.sc.v3.failable._

import com.mchange.sc.v2.util.Platform

import com.mchange.sc.v1.log.MLevel._

package object repository {
  implicit lazy val logger : com.mchange.sc.v1.log.MLogger = mlogger( this )

  private val SystemProperty      = "sbt.ethereum.repository"
  private val EnvironmentVariable = "SBT_ETHEREUM_REPOSITORY"

  lazy val Directory : Failable[File] = {
    def defaultLocation = {
      Platform.Current
        .toFailable( "Could not detect the platform to determine the repository directory" )
        .flatMap( _.appSupportDirectory( "sbt-ethereum" ) )
    }

    val out = {
      val mbProperty = Option( System.getProperty( SystemProperty ) )
      val mbEnvVar   = Option( System.getenv( EnvironmentVariable ) )

      (mbProperty orElse mbEnvVar).fold( defaultLocation )( dir => Failable.succeed( new File( dir ) ) )
    }

    def prepareDir( dir : File ) : Failable[File] = {
      try {
        dir.mkdirs()
        if ( !dir.exists() || !dir.isDirectory ) {
          Failable.fail(s"Specified sbt-ethereum repository directory '$dir' must be a directory or must be creatable as a directory!")
        } else if ( !dir.canRead || !dir.canWrite ) {
          Failable.fail(s"Specified sbt-ethereum repository directory '$dir' is must be readable and writable!")
        } else {
          Failable.succeed( dir )
        }
      } catch Failed.FromThrowable
    }

    // ensure that the directory exists once it is referenced
    out.flatMap( prepareDir )
  }
}
