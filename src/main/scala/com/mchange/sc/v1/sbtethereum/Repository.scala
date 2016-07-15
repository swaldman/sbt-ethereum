package com.mchange.sc.v1.sbtethereum

import java.io.{BufferedOutputStream,File,FileOutputStream,OutputStreamWriter,PrintWriter}
import java.text.SimpleDateFormat
import java.util.Date

import scala.io.Codec

import com.mchange.sc.v2.failable._
import com.mchange.sc.v2.lang.borrow

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{EthHash,EthTransaction}

object Repository {
  private val TimestampPattern = "yyyy-MM-dd'T'HH-mm-ssZ"

  private val SystemProperty      = "sbt.ethereum.repository"
  private val EnvironmentVariable = "SBT_ETHEREUM_REPOSITORY"

  def logTransaction( transaction : EthTransaction.Signed, transactionHash : EthHash ) : Unit = {
    TransactionLog.File.flatMap { file =>
      Failable {
        val entry = TransactionLog.Entry( new Date(), transaction, transactionHash ) 
        borrow( new PrintWriter( new OutputStreamWriter( new BufferedOutputStream( new FileOutputStream( file, true ) ), Codec.UTF8.charSet ) ) )( _.println( entry ) )
      }
    }.get // Unit or vomit Exception
  }

  final object TransactionLog {
    lazy val File = Directory.map( dir => new java.io.File(dir, "transaction-log") )

    case class Entry( timestamp : Date, txn : EthTransaction.Signed, transactionHash : EthHash ) {
      override def toString() = {
        val ( ttype, payloadKey, payload ) = txn match {
          case m  : EthTransaction.Signed.Message          => ("Message", "data", m.data)
          case cc : EthTransaction.Signed.ContractCreation => ("ContractCreation", "init", cc.init)
        }
        val df = new SimpleDateFormat(TimestampPattern)
        val ts = df.format( timestamp )
        val first  = s"${ts}:type=${ttype},nonce=${txn.nonce.widen},gasPrice=${txn.gasPrice.widen},gasLimit=${txn.gasLimit.widen},value=${txn.value.widen},"
        val middle = if ( payload.length > 0 ) s"${payloadKey}=${payload.hex}," else ""
        val last   = s"v=${txn.v.widen},r=${txn.r.widen},s=${txn.s.widen},transactionHash=${transactionHash.bytes.hex}"
        first + middle + last
      }
    }
  }

  lazy val Directory : Failable[File] = {
    def defaultLocation = {
      val tag = "sbt-ethereum: Repository"
      val osName = Option( System.getProperty("os.name") ).map( _.toLowerCase ).toFailable(s"${tag}: Couldn't detect OS, System property 'os.name' not available.")
      osName.flatMap { osn =>
        if ( osn.indexOf( "win" ) >= 0 ) {
          Option( System.getenv("APPDATA") ).map( ad => new java.io.File(ad, "sbt-ethereum") ).toFailable("${tag}: On Windows, but could not find environment variable 'APPDATA'")
        } else if ( osn.indexOf( "mac" ) >= 0 ) {
          Option( System.getProperty("user.home") ).map( home => new java.io.File( s"${home}/Library/Application Support/sbt-ethereum" ) ).toFailable("${tag}: On Mac, but could not find System property 'user.home'")
        } else {
          Option( System.getProperty("user.home") ).map( home => new java.io.File( s"${home}/.sbt-ethereum" ) ).toFailable("${tag}: On Unix, but could not find System property 'user.home'")
        }
      }
    }

    val out = {
      val mbProperty = Option( System.getProperty( Repository.SystemProperty ) )
      val mbEnvVar   = Option( System.getenv( Repository.EnvironmentVariable ) )

      (mbProperty orElse mbEnvVar).fold( defaultLocation )( dir => succeed( new File( dir ) ) )
    }

    def prepareDir( dir : File ) : Failable[File] = {
      try {
        dir.mkdirs()
        if ( !dir.exists() || !dir.isDirectory ) {
          fail(s"Specified sbt-ethereum repository directory '${dir}' must be a directory or must be creatable as a directory!")
        } else if ( !dir.canRead || !dir.canWrite ) {
          fail(s"Specified sbt-ethereum repository directory '${dir}' is must be readable and writable!")
        } else {
          succeed( dir )
        }
      } catch ToFailable
    }

    // ensure that the directory exists once it is referenced
    out.flatMap( prepareDir )
  }
}
