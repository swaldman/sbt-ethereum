package com.mchange.sc.v1.sbtethereum

import java.io.{BufferedOutputStream,File,FileOutputStream,OutputStreamWriter,PrintWriter}
import java.text.SimpleDateFormat
import java.util.Date

import scala.io.Codec

import com.mchange.sc.v2.lang.borrow

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{EthHash,EthTransaction}

import com.mchange.sc.v1.log.MLevel._
import com.mchange.sc.v1._

object Repository {
  private val TimestampPattern = "yyyy-MM-dd'T'HH-mm-ssZ"

  private lazy implicit val logger = mlogger( this )

  lazy val DefaultDirectory : Option[File] = {
    val tag = "sbt-ethereum: Repository"
    val osName = Option( System.getProperty("os.name") ).map( _.toLowerCase ).xwarn(s"${tag}: Couldn't detect OS, System property 'os.name' not available.")
    osName.flatMap { osn =>
      if ( osn.indexOf( "win" ) >= 0 ) {
        Option( System.getenv("APPDATA") ).map( ad => new java.io.File(ad, "sbt-ethereum") ).xwarn( s"${tag}: On Windows, but could not find environment variable 'APPDATA'" )
      } else if ( osn.indexOf( "mac" ) >= 0 ) {
        Option( System.getProperty("user.home") ).map( home => new java.io.File( s"${home}/Library/Application Support/sbt-ethereum" ) ).xwarn( s"${tag}: On Mac, but could not find System property 'user.home'" )
      } else {
        Option( System.getProperty("user.home") ).map( home => new java.io.File( s"${home}/.sbt-ethereum" ) ).xwarn( s"${tag}: On Unix, but could not find System property 'user.home'" )
      }
    }
  }

  val Default = new Repository( None )
}
class Repository( overrideDirectory : Option[File] ) {
  import Repository._

  def logTransaction( transactionHash : EthHash, transaction : EthTransaction.Signed ) : Unit = {
    transactionLog.foreach { file =>
      val df = new SimpleDateFormat(TimestampPattern)
      val timestamp = df.format( new Date() )
      val line = s"${timestamp}|0x${transactionHash.bytes.hex}|${transaction}"
      borrow( new PrintWriter( new OutputStreamWriter( new BufferedOutputStream( new FileOutputStream( file, true ) ), Codec.UTF8.charSet ) ) )( _.println( line ) )
    }
  }

  lazy val transactionLog : Option[File] = directory.flatMap { dir =>
    val logfile = new File(dir, "transaction-log")
    if ( logfile.exists() && !logfile.canWrite ) {
      WARNING.log( s"Transaction log file '${logfile}' is not writable!" )
      None
    } else {
      Some( logfile )
    }
  }

  lazy val directory : Option[File] = {
    val out = overrideDirectory orElse DefaultDirectory
    out.flatMap { dir => // ensure that the directory exists once it is referenced
      try {
        dir.mkdirs()
        if ( !dir.exists() || !dir.isDirectory ) {
          WARNING.log(s"Specified sbt-ethereum repository directory '${dir}' must be a directory or must be creatable as a directory!")
          None
        } else if ( !dir.canRead || !dir.canWrite ) {
          WARNING.log(s"Specified sbt-ethereum repository directory '${dir}' is must be readable and writable!")
          None
        } else {
          Some( dir )
        }
      } catch {
        case ioe : Exception => {
          WARNING.log(s"Failed to find or create sbt-ethereum repository directory '${dir}'", ioe)
          None
        }
      }
    }
  }
}
