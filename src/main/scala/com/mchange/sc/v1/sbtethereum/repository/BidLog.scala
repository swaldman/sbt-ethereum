package com.mchange.sc.v1.sbtethereum.repository

import com.mchange.sc.v2.ens

import com.mchange.sc.v2.failable._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{EthAddress, EthHash, EthTransaction}

final case class BidLogRecord( bid : ens.Bid, blockchainId : String, tld : String, nameServiceAddress : EthAddress )

object BidLog extends RepositoryLog[ BidLogRecord ]("bid-log") {
  def toLine( timestamp : String, record : BidLogRecord ) : String = {
    val BidLogRecord( bid, blockchainId, tld, nameServiceAddress ) = record
    s"${timestamp}:blockchainId=${blockchainId};tld=${tld};nameServiceAdress=${nameServiceAddress.hex}:bidHash=${bid.bidHash};simpleName=${ bid.simpleName };bidderAddress=${ bid.bidderAddress.hex };valueInWei=${ bid.valueInWei };salt=${ bid.salt }"
  }

  def logBid( bid : ens.Bid, blockchainId : String, tld : String, nameServiceAddress : EthAddress ) : Failable[Unit] = log( BidLogRecord( bid, blockchainId, tld, nameServiceAddress ) )
}

