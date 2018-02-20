package com.mchange.sc.v1.sbtethereum.repository

import com.mchange.sc.v2.failable._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{EthHash, EthTransaction}

object TransactionLog extends RepositoryLog[ ( EthTransaction.Signed, EthHash ) ]("transaction-log") {
  def toLine( timestamp : String, tuple : ( EthTransaction.Signed, EthHash ) ) : String = {
    val ( txn, transactionHash ) = tuple
    val ( ttype, payloadKey, payload ) = txn match {
      case m  : EthTransaction.Signed.Message          => ("Message", "data", m.data)
      case cc : EthTransaction.Signed.ContractCreation => ("ContractCreation", "init", cc.init)
    }
    val first  = s"$timestamp:type=$ttype,nonce=${ txn.nonce.widen },gasPrice=${ txn.gasPrice.widen },gasLimit=${ txn.gasLimit.widen },value=${ txn.value.widen },"
    val middle = if ( payload.nonEmpty ) s"$payloadKey=${payload.hex}," else ""
    val last   = s"v=${txn.v.widen},r=${txn.r.widen},s=${txn.s.widen},transactionHash=${transactionHash.bytes.hex}"
    first + middle + last
  }

  def logTransaction( transaction : EthTransaction.Signed, transactionHash : EthHash ) : Failable[Unit] = this.log( ( transaction, transactionHash ) )
}

