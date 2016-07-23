package com.mchange.sc.v1.sbtethereum

import java.io.{BufferedOutputStream,File,FileOutputStream,OutputStreamWriter,PrintWriter}
import java.text.SimpleDateFormat
import java.util.Date

import scala.io.Codec

import com.mchange.sc.v2.failable._
import com.mchange.sc.v2.lang.borrow

import com.mchange.sc.v2.util.Platform

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{clients,wallet,EthHash,EthTransaction}
import com.mchange.sc.v1.consuela.io.ensureUserOnlyDirectory


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

  final object KeyStore {
    val DirName = "keystore"
    lazy val Directory : Failable[File] = Repository.Directory.flatMap( mainDir => ensureUserOnlyDirectory( new File( mainDir, DirName ) ) )

    final object V3 {
      val DirName = "V3"
      lazy val Directory : Failable[File] = KeyStore.Directory.flatMap( mainDir => ensureUserOnlyDirectory( new File( mainDir, DirName ) ) )

      def storeWallet( w : wallet.V3 ) : Failable[wallet.V3] = Directory.flatMap( clients.geth.KeyStore.add( _, w ) )
    }
  }

  lazy val Directory : Failable[File] = {
    def defaultLocation = {
      Platform.Current
        .toFailable( "Could not detect the platform to determine the repository directory" )
        .flatMap( _.appSupportDirectory( "sbt-ethereum" ) )
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
