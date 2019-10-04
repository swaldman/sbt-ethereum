package com.mchange.sc.v1.sbtethereum.util

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration

import scala.collection._

import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v1.log.MLevel._

import com.mchange.sc.v1.sbtethereum._
import shoebox.TransactionLog
import compile.Compiler.overwriteSourceTimestamp

import com.mchange.sc.v2.jsonrpc.Exchanger

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{jsonrpc, specification, EthAddress, EthHash, EthPrivateKey, EthTransaction}
import jsonrpc.{Compilation, Client}
import specification.Denominations.Denomination // XXX: Ick! Refactor this in consuela!
import specification.Types.Unsigned256

import java.net.URL

object EthJsonRpc {

  private implicit lazy val logger = mlogger( this )

  private def doWithJsonClient[T]( efactory : Exchanger.Factory, exchangerConfig : Exchanger.Config, log : sbt.Logger, ec : ExecutionContext )( operation : jsonrpc.Client => T ) : T = {
    try {
      borrow( Client.forExchanger( efactory( exchangerConfig ) ) )( operation )
    }
    catch {
      case e : java.net.ConnectException => {
        log.error( s"Failed to connect to JSON-RPC client at '${exchangerConfig.httpUrl}': ${e}" )
        throw e
      }
    }
  }

  def doAsyncCompileSolidity( exchangerConfig : Exchanger.Config, log : sbt.Logger, source : String, sourceTimestamp : Option[Long] )( implicit efactory : Exchanger.Factory, ec : ExecutionContext ) : Future[Compilation] = {
    doWithJsonClient( efactory, exchangerConfig, log, ec )( client => client.eth.compileSolidity( source ).map( compilation => compilation.map( overwriteSourceTimestamp( sourceTimestamp ) ) ) )
  }

  def doGetBalance(
    exchangerConfig : Exchanger.Config,
    log             : sbt.Logger,
    awaitTimeout    : Duration,
    address         : EthAddress,
    blockNumber     : jsonrpc.Client.BlockNumber
  )( implicit efactory : Exchanger.Factory, ec : ExecutionContext ) : BigInt = {
    doWithJsonClient( efactory, exchangerConfig, log, ec )( client => Await.result( client.eth.getBalance( address, blockNumber ), awaitTimeout ) )
  }

  def doPrintingGetBalance(
    exchangerConfig : Exchanger.Config,
    log             : sbt.Logger,
    awaitTimeout    : Duration,
    address         : EthAddress,
    blockNumber     : jsonrpc.Client.BlockNumber,
    denomination    : Denomination
  )( implicit efactory : Exchanger.Factory, ec : ExecutionContext ) : EthValue = {
    import jsonrpc.Client.BlockNumber._

    val wei = doGetBalance( exchangerConfig, log, awaitTimeout, address, blockNumber )( efactory, ec )
    val out = EthValue( wei, denomination )
    val msg = blockNumber match {
      case Earliest       => s"${out.denominated} ${denomination.unitName} (at the earliest available block, address 0x${address.hex})"
      case Latest         => s"${out.denominated} ${denomination.unitName} (as of the latest incorporated block, address 0x${address.hex})"
      case Pending        => s"${out.denominated} ${denomination.unitName} (including currently pending transactions, address 0x${address.hex})"
      case Quantity( bn ) => s"${out.denominated} ${denomination.unitName} (at block #${bn}, address 0x${address.hex})"
    }
    syncOut( println(msg) )
    out
  }

  def doGetDefaultGasPrice( exchangerConfig : Exchanger.Config, log : sbt.Logger, timeout : Duration )( implicit efactory : Exchanger.Factory, ec : ExecutionContext ) : BigInt = {
    doWithJsonClient( efactory, exchangerConfig, log, ec )( client => Await.result( client.eth.gasPrice(), timeout ) )
  }

  def doGetTransactionCount(
    exchangerConfig : Exchanger.Config,
    log : sbt.Logger,
    timeout : Duration,
    address : EthAddress,
    blockNumber : jsonrpc.Client.BlockNumber
  )( implicit efactory : Exchanger.Factory, ec : ExecutionContext ) : BigInt = {
    doWithJsonClient( efactory, exchangerConfig, log, ec )( client => Await.result( client.eth.getTransactionCount( address, blockNumber ), timeout ) )
  }


  /*
  def doCodeForAddress(
    log : sbt.Logger,
    jsonRpcUrl : String,
    timeout : Duration,
    address : EthAddress,
    blockNumber : jsonrpc.Client.BlockNumber
  )( implicit clientFactory : jsonrpc.Client.Factory, ec : ExecutionContext ) : immutable.Seq[Byte] = {
    doWithJsonClient( log, jsonRpcUrl, clientFactory, ec )( client => Await.result( client.eth.getCode( address, blockNumber ), timeout ) )
  }
  */ 

  /*
  private [sbtethereum] def doEthCallEphemeral(
    log         : sbt.Logger,
    jsonRpcUrl  : String,
    timeout     : Duration,
    from        : Option[EthAddress],
    to          : EthAddress,
    gas         : Option[BigInt],
    gasPrice    : Option[BigInt],
    value       : Option[BigInt],
    data        : Option[Seq[Byte]],
    blockNumber : jsonrpc.Client.BlockNumber
  )( implicit clientFactory : jsonrpc.Client.Factory, ec : ExecutionContext ) : immutable.Seq[Byte] = {
    doWithJsonClient( log, jsonRpcUrl, clientFactory, ec )( client => Await.result( client.eth.call( from, Some(to), gas, gasPrice, value, data, blockNumber), timeout ) )
  }
  */ 

  /*
  def doEstimateGas(
    log : sbt.Logger,
    jsonRpcUrl : String,
    timeout : Duration,
    from : Option[EthAddress],
    to : Option[EthAddress],
    value : Option[BigInt],
    data : Option[Seq[Byte]],
    blockNumber : jsonrpc.Client.BlockNumber
  )( implicit clientFactory : jsonrpc.Client.Factory, ec : ExecutionContext ) : BigInt = {
    doWithJsonClient( log, jsonRpcUrl, clientFactory, ec )( client => Await.result( client.eth.estimateGas( from = from, to = to, value = value, data = data ), timeout ) )
  }
  */ 

  /*
   * // we use jsonrpc.Invoker rather than this method now... no longer maintaining.

  def doSignSendTransaction( log : sbt.Logger, jsonRpcUrl : String, signer : EthPrivateKey, unsigned : EthTransaction.Unsigned )( implicit clientFactory : jsonrpc.Client.Factory, ec : ExecutionContext ) : EthHash = {
    doWithJsonClient( log, jsonRpcUrl, clientFactory, ec ){ client =>
      val signed = unsigned.sign( signer )
      val hash = Await.result( client.eth.sendSignedTransaction( signed ), Duration.Inf )
      TransactionLog.logTransaction( signed, hash ).xwarn("Failed to append the transaction to the transaction log!")
      hash
    }
  }
   * 
   */

  /*
  def doEstimateAndMarkupGas(
    log : sbt.Logger,
    jsonRpcUrl : String,
    timeout : Duration,
    from : Option[EthAddress],
    to : Option[EthAddress],
    value : Option[BigInt],
    data : Option[Seq[Byte]],
    blockNumber : jsonrpc.Client.BlockNumber,
    markup : Double
  )( implicit clientFactory : jsonrpc.Client.Factory, ec : ExecutionContext ) : BigInt = {
    val rawEstimate = doEstimateGas( log, jsonRpcUrl, timeout, from, to, value, data, blockNumber )( clientFactory, ec )
    rounded(BigDecimal(rawEstimate) * BigDecimal(1 + markup))
  }
  */ 

  /*
  private [sbtethereum] def awaitTransactionReceipt(
    log : sbt.Logger,
    jsonRpcUrl : String,
    transactionHash : EthHash,
    pollSeconds : Int,
    maxPollAttempts : Int
  )( implicit clientFactory : jsonrpc.Client.Factory, ec : ExecutionContext ) : Option[Client.TransactionReceipt] = {
    doWithJsonClient( log, jsonRpcUrl, clientFactory, ec ){ client =>
      def doPoll( attemptNum : Int ) : Option[Client.TransactionReceipt] = {
        val mbReceipt = Await.result( client.eth.getTransactionReceipt( transactionHash ), Duration.Inf )
        ( mbReceipt, attemptNum ) match {
          case ( None, num ) if ( num < maxPollAttempts ) => {
            log.info(s"Receipt for transaction '0x${transactionHash.bytes.hex}' not yet available, will try again in ${pollSeconds} seconds. Attempt ${attemptNum + 1}/${maxPollAttempts}.")
            Thread.sleep( pollSeconds * 1000 )
            doPoll( num + 1 )
          }
          case ( None, _ ) => {
            log.warn(s"After ${maxPollAttempts} attempts (${(maxPollAttempts - 1) * pollSeconds} seconds), no receipt has yet been received for transaction '0x${transactionHash.bytes.hex}'.")
            None
          }
          case ( Some( receipt ), _ ) => {
            log.info(s"Receipt received for transaction '0x${transactionHash.bytes.hex}':\n${prettyClientTransactionReceipt(receipt)}")
            mbReceipt
          }
        }
      }
      doPoll( 0 )
    }
  }
   */ 

}
