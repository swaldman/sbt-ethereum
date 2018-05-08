package com.mchange.sc.v1.sbtethereum.repository

import com.mchange.sc.v3.failable._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{EthHash, EthTransaction}

object TransactionLog extends RepositoryLog[ ( String, String, EthTransaction.Signed, EthHash ) ]("transaction-log") {
  def toLine( timestamp : String, tuple : ( String, String, EthTransaction.Signed, EthHash ) ) : String = {
    val ( blockchainId, jsonRpcUrl, txn, transactionHash ) = tuple
    val ( ttype, payloadKey, payload ) = txn match {
      case m  : EthTransaction.Signed.Message          => ("Message", "data", m.data)
      case cc : EthTransaction.Signed.ContractCreation => ("ContractCreation", "init", cc.init)
    }
    val first  = s"$timestamp:blockchainId=${blockchainId};jsonRpcUrl=${jsonRpcUrl}:type=$ttype;nonce=${ txn.nonce.widen };gasPrice=${ txn.gasPrice.widen };gasLimit=${ txn.gasLimit.widen };value=${ txn.value.widen };"
    val middle = if ( payload.nonEmpty ) s"$payloadKey=${payload.hex};" else ""
    val last   = s"v=${txn.v.widen};r=${txn.r.widen};s=${txn.s.widen};transactionHash=${transactionHash.bytes.hex}"
    first + middle + last
  }

  def logTransaction( blockchainId : String, jsonRpcUrl : String, transaction : EthTransaction.Signed, transactionHash : EthHash ) : Failable[Unit] = this.log( ( blockchainId, jsonRpcUrl, transaction, transactionHash ) )
}

