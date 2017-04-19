package com.mchange.sc.v1.sbtethereum.util

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration

import scala.collection._

import com.mchange.sc.v2.lang.borrow

import com.mchange.sc.v1.sbtethereum._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{jsonrpc20, specification, EthAddress, EthHash, EthPrivateKey, EthTransaction}
import jsonrpc20.{Compilation, ClientTransactionReceipt}
import specification.Denominations.Denomination // XXX: Ick! Refactor this in consuela!


import com.mchange.sc.v1.sbtethereum.Repository

import java.net.URL

object EthJsonRpc {
  def doWithJsonClient[T]( log : sbt.Logger, jsonRpcUrl : String )( operation : jsonrpc20.Client => T )( implicit ec : ExecutionContext ) : T = {
    try {
      borrow( new jsonrpc20.Client.Simple( new URL( jsonRpcUrl ) ) )( operation )
    } catch {
      case e : java.net.ConnectException => {
        log.error( s"Failed to connect to JSON-RPC client at '${jsonRpcUrl}': ${e}" )
        throw e
      }
    }
  }

  def doAsyncCompileSolidity( log : sbt.Logger, jsonRpcUrl : String, source : String )( implicit ec : ExecutionContext ) : Future[Compilation] = {
    doWithJsonClient( log, jsonRpcUrl )( client => client.eth.compileSolidity( source ) )( ec )
  }

  def doGetBalance( log : sbt.Logger, jsonRpcUrl : String, address : EthAddress, blockNumber : jsonrpc20.Client.BlockNumber )( implicit ec : ExecutionContext ) : BigInt = {
    doWithJsonClient( log, jsonRpcUrl )( client => Await.result( client.eth.getBalance( address, blockNumber ), Duration.Inf ) )
  }

  def doPrintingGetBalance(
    log          : sbt.Logger,
    jsonRpcUrl   : String,
    address      : EthAddress,
    blockNumber  : jsonrpc20.Client.BlockNumber,
    denomination : Denomination
  )( implicit ec : ExecutionContext ) : EthValue = {
    import jsonrpc20.Client.BlockNumber._

    val wei = doGetBalance( log, jsonRpcUrl, address, blockNumber )( ec )
    val out = EthValue( wei, denomination.fromWei( wei ), denomination )
    val msg = blockNumber match {
      case Earliest       => s"${out.denominated} ${denomination.unitName} (at the earliest available block, address 0x${address.hex})"
      case Latest         => s"${out.denominated} ${denomination.unitName} (as of the latest incorporated block, address 0x${address.hex})"
      case Pending        => s"${out.denominated} ${denomination.unitName} (including currently pending transactions, address 0x${address.hex})"
      case Quantity( bn ) => s"${out.denominated} ${denomination.unitName} (at block #${bn}, address 0x${address.hex})"
    }
    log.info(msg)
    out
  }

  def doCodeForAddress( log : sbt.Logger, jsonRpcUrl : String, address : EthAddress, blockNumber : jsonrpc20.Client.BlockNumber )( implicit ec : ExecutionContext ) : immutable.Seq[Byte] = {
    doWithJsonClient( log, jsonRpcUrl )( client => Await.result( client.eth.getCode( address, blockNumber ), Duration.Inf ) )
  }

    private [sbtethereum] def doEthCallEphemeral(
    log         : sbt.Logger,
    jsonRpcUrl  : String,
    from        : Option[EthAddress],
    to          : EthAddress,
    gas         : Option[BigInt],
    gasPrice    : Option[BigInt],
    value       : Option[BigInt],
    data        : Option[Seq[Byte]],
    blockNumber : jsonrpc20.Client.BlockNumber
  )( implicit ec : ExecutionContext ) : immutable.Seq[Byte] = {
    doWithJsonClient( log, jsonRpcUrl )( client => Await.result( client.eth.call( from, Some(to), gas, gasPrice, value, data, blockNumber), Duration.Inf ) )
  }

  def doGetDefaultGasPrice( log : sbt.Logger, jsonRpcUrl : String )( implicit ec : ExecutionContext ) : BigInt = {
    doWithJsonClient( log, jsonRpcUrl )( client => Await.result( client.eth.gasPrice(), Duration.Inf ) )
  }

  def doGetTransactionCount( log : sbt.Logger, jsonRpcUrl : String, address : EthAddress, blockNumber : jsonrpc20.Client.BlockNumber )( implicit ec : ExecutionContext ) : BigInt = {
    doWithJsonClient( log, jsonRpcUrl )( client => Await.result( client.eth.getTransactionCount( address, blockNumber ), Duration.Inf ) )
  }

  def doEstimateGas( log : sbt.Logger, jsonRpcUrl : String, from : Option[EthAddress], to : Option[EthAddress], data : Seq[Byte], blockNumber : jsonrpc20.Client.BlockNumber )( implicit ec : ExecutionContext ) : BigInt = {
    doWithJsonClient( log, jsonRpcUrl )( client => Await.result( client.eth.estimateGas( from = from, to = to, data = Some(data) ), Duration.Inf ) )
  }

  def doSignSendTransaction( log : sbt.Logger, jsonRpcUrl : String, signer : EthPrivateKey, unsigned : EthTransaction.Unsigned )( implicit ec : ExecutionContext ) : EthHash = {
    doWithJsonClient( log, jsonRpcUrl ){ client =>
      val signed = unsigned.sign( signer )
      val hash = Await.result( client.eth.sendSignedTransaction( signed ), Duration.Inf )
      Repository.logTransaction( signed, hash )
      hash
    }
  }

  def doEstimateAndMarkupGas(
    log : sbt.Logger,
    jsonRpcUrl : String,
    from : Option[EthAddress],
    to : Option[EthAddress],
    data : Seq[Byte],
    blockNumber : jsonrpc20.Client.BlockNumber,
    markup : Double
  )( implicit ec : ExecutionContext ) : BigInt = {
    val rawEstimate = doEstimateGas( log, jsonRpcUrl, from, to, data, blockNumber )( ec )
    rounded(BigDecimal(rawEstimate) * BigDecimal(1 + markup)).toBigInt
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

  // TODO: pretty up logs output
  private def prettyClientTransactionReceipt( ctr : ClientTransactionReceipt ) : String = {
    s"""|Transaction Receipt:
        |       Transaction Hash:    0x${ctr.transactionHash.hex}
        |       Transaction Index:   ${ctr.transactionIndex.widen}
        |       Block Hash:          0x${ctr.blockHash.hex}
        |       Block Number:        ${ctr.blockNumber.widen}
        |       Cumulative Gas Used: ${ctr.cumulativeGasUsed.widen}
        |       Contract Address:    ${ctr.contractAddress.fold("None")( ea => "0x" + ea.hex )}
        |       Logs:                ${if (ctr.logs.isEmpty) "None" else ctr.logs.mkString(", ")}""".stripMargin     
  }
}
