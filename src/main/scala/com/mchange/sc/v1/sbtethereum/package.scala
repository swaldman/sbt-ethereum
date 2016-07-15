package com.mchange.sc.v1

import sbt._
import sbt.Keys._

import scala.io.{Codec,Source}
import scala.concurrent.{Await,ExecutionContext,Future}
import scala.concurrent.duration.Duration
import scala.util.Failure
import scala.annotation.tailrec

import java.io._
import scala.collection._

import com.mchange.sc.v1.log.MLevel._
import com.mchange.sc.v2.concurrent._

import com.mchange.sc.v2.lang.borrow

import com.mchange.sc.v1.consuela._

import com.mchange.sc.v1.consuela.ethereum.{jsonrpc20,wallet,EthAddress,EthHash,EthPrivateKey,EthTransaction}
import com.mchange.sc.v1.consuela.ethereum.jsonrpc20.{ClientTransactionReceipt,MapStringCompilationContractFormat}
import com.mchange.sc.v1.consuela.ethereum.encoding.RLP

import play.api.libs.json._

package object sbtethereum {
  private implicit val logger = mlogger( "com.mchange.sc.v1.sbtethereum.package" ) 

  final class NoSolidityCompilerException( msg : String ) extends Exception( msg )

  private val SolFileRegex = """(.+)\.sol""".r

  // XXX: hardcoded
  private val SolidityWriteBufferSize = 1024 * 1024; //1 MiB

  // XXX: geth seems not to be able to validate some subset of the signatures that we validate as good (and homestead compatible)
  //      to work around this, we just retry a few times when we get "Invalid sender" errors on sending a signed transaction
  val InvalidSenderRetries = 10

  private def doWithJsonClient[T]( log : sbt.Logger, jsonRpcUrl : String )( operation : jsonrpc20.Client => T )( implicit ec : ExecutionContext ) : T = {
    try {
      borrow( new jsonrpc20.Client.Simple( new URL( jsonRpcUrl ) ) )( operation )
    } catch {
      case e : java.net.ConnectException => {
        log.error( s"Failed to connect to JSON-RPC client at '${jsonRpcUrl}': ${e}" )
        throw e
      }
    }
  }

  private [sbtethereum] def doCompileSolidity( log : sbt.Logger, jsonRpcUrl : String, solSource : File, solDestination : File )( implicit ec : ExecutionContext ) : Unit = {
    def solToJson( filename : String ) : String = filename match {
      case SolFileRegex( base ) => base + ".json"
    }

    // TODO XXX: check imported files as well!
    def changed( destinationFile : File, sourceFile : File ) : Boolean = (! destinationFile.exists) || (sourceFile.lastModified() > destinationFile.lastModified() )

    def waitForSeq[T]( futs : Seq[Future[T]], errorMessage : Int => String ) : Unit = {
      val failures = awaitAndGatherFailures( futs )
      val failureCount = failures.size
      if ( failureCount > 0 ) {
        log.error( errorMessage( failureCount ) )
        failures.foreach {
          case jf : jsonrpc20.Exception => log.error( jf.message )
          case other                    => log.error( other.toString )
        }
        throw failures.head
      }
    }

    doWithJsonClient( log, jsonRpcUrl ){ client =>
      solDestination.mkdirs()
      val files = (solSource ** "*.sol").get

      val filePairs = files.map( file => ( file, new File( solDestination, solToJson( file.getName() ) ) ) ) // (sourceFile, destinationFile)
      val compileFiles = filePairs.filter( tup => changed( tup._2, tup._1 ) )

      val cfl = compileFiles.length
      if ( cfl > 0 ) {
        val mbS = if ( cfl > 1 ) "s" else ""
        log.info( s"Compiling ${compileFiles.length} Solidity source${mbS} to ${solDestination}..." )

        val compileFuts = compileFiles.map { tup =>
          val srcFile  = tup._1
          val destFile = tup._2
          borrow( Source.fromFile( srcFile )(Codec.UTF8) )( _.close() ){ source =>
            val code = source.foldLeft("")( _ + _ )
            client.eth.compileSolidity( code ).map( result => ( destFile, result ) )
          }
        }
        waitForSeq( compileFuts, count => s"compileSolidity failed. [${count} failures]" )

        val writerFuts = compileFuts.map { fut =>
          import Json._
          fut.map {
            case ( destFile, result ) => {
              borrow( new OutputStreamWriter( new BufferedOutputStream( new FileOutputStream( destFile ), SolidityWriteBufferSize ), Codec.UTF8.charSet ) )( _.write( stringify( toJson ( result ) ) ) )
            }
          }
        }
        waitForSeq( writerFuts, count => s"Failed to write the output of some compilations. [${count} failures]" )
      }
    }
  }

  private [sbtethereum] def doGetDefaultGasPrice( log : sbt.Logger, jsonRpcUrl : String )( implicit ec : ExecutionContext ) : BigInt = {
    doWithJsonClient( log, jsonRpcUrl )( client => Await.result( client.eth.gasPrice(), Duration.Inf ) )
  }

  private [sbtethereum] def doGetTransactionCount( log : sbt.Logger, jsonRpcUrl : String, address : EthAddress, blockNumber : jsonrpc20.Client.BlockNumber )( implicit ec : ExecutionContext ) : BigInt = {
    doWithJsonClient( log, jsonRpcUrl )( client => Await.result( client.eth.getTransactionCount( address, blockNumber ), Duration.Inf ) )
  }

  private [sbtethereum] def doEstimateGas( log : sbt.Logger, jsonRpcUrl : String, from : EthAddress, data : Seq[Byte], blockNumber : jsonrpc20.Client.BlockNumber )( implicit ec : ExecutionContext ) : BigInt = {
    doWithJsonClient( log, jsonRpcUrl )( client => Await.result( client.eth.estimateGas( from = Some(from), data = Some(data) ), Duration.Inf ) )
  }

  private [sbtethereum] def findPrivateKey( log : sbt.Logger, mbGethWallet : Option[wallet.V3], credential : String ) : EthPrivateKey = {
    mbGethWallet.fold {
      log.info( "No wallet available. Trying passphrase as hex private key." )
      EthPrivateKey( credential )
    }{ gethWallet =>
      try {
        wallet.V3.decodePrivateKey( gethWallet, credential )
      } catch {
        case v3e : wallet.V3.Exception => {
          log.warn("Credential is not correct geth wallet passphrase. Trying as hex private key.")
          EthPrivateKey( credential )
        }
      }
    }
  }

  private [sbtethereum] def doSignSendTransaction( log : sbt.Logger, jsonRpcUrl : String, signer : EthPrivateKey, unsigned : EthTransaction.Unsigned )( implicit ec : ExecutionContext ) : EthHash = {
    doWithJsonClient( log, jsonRpcUrl ){ client =>
      val signed = unsigned.sign( signer )
      val hash = Await.result( client.eth.sendSignedTransaction( signed ), Duration.Inf )
      Repository.logTransaction( signed, hash )
      hash
    }
  }

  private [sbtethereum] def awaitTransactionReceipt(
    log : sbt.Logger,
    jsonRpcUrl : String,
    transactionHash : EthHash,
    pollSeconds : Int,
    maxPollAttempts : Int
  )( implicit ec : ExecutionContext ) : Option[ClientTransactionReceipt] = {
    doWithJsonClient( log, jsonRpcUrl ){ client =>
      def doPoll( attemptNum : Int ) : Option[ClientTransactionReceipt] = {
        val mbReceipt = Await.result( client.eth.getTransactionReceipt( transactionHash ), Duration.Inf )
        ( mbReceipt, attemptNum ) match {
          case ( None, num ) if ( num < maxPollAttempts ) => {
            log.info(s"Receipt for transaction '0x${transactionHash.bytes.hex}' not yet available, will try again in ${pollSeconds} seconds. Attempt ${attemptNum + 1}/${maxPollAttempts}.")
            Thread.sleep( pollSeconds * 1000 )
            doPoll( num + 1 )
          }
          case ( None, _ ) => None
          case _           => mbReceipt
        }
      }
      doPoll( 0 )
    }
  }

  // No longer necessary. This worked around a consuela bug, which permitted non-Homestead compatible signatures to be generated.
  @tailrec
  private [sbtethereum] def retryingSignSendTransaction(
    client       : jsonrpc20.Client,
    signer       : EthPrivateKey,
    unsigned     : EthTransaction.Unsigned,
    maxRetries   : Int,
    currentRetry : Int = 0
  )( implicit ec : ExecutionContext) : EthHash = {
    val signed = unsigned.sign( signer )

    val out = {
      try { Await.result( client.eth.sendSignedTransaction( signed ), Duration.Inf ) }
      catch {
        case exc : jsonrpc20.Exception if ( currentRetry < maxRetries && considerInvalidSender( exc )) => {
          DEBUG.log( s"JSON-RPC client rejected signed transaction. Signed Transaction: ${signed}, Base transaction hex: 0x${RLP.encode[EthTransaction]( signed.base ).hex}. Attempt ${currentRetry+1} / ${maxRetries}." )
          null
        }
        case other : Throwable => throw other
      }
    }

    if ( out != null ) out else retryingSignSendTransaction( client, signer, unsigned, maxRetries, currentRetry + 1 )
  }

  // no longer necessary. workaround geth-not-accepting-all-signatures problem
  private def considerInvalidSender( exc : jsonrpc20.Exception ) : Boolean = {

    // we could use code == -32000, but I don't think these error codes
    // are consistent across clients, alas
    //
    // this is very, um, inexact

    exc.getMessage().toLowerCase.indexOf("sender") >= 0
  }
}


