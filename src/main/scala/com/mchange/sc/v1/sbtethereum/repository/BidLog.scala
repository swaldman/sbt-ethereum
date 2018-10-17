package com.mchange.sc.v1.sbtethereum.repository

import com.mchange.sc.v2.ens

import com.mchange.sc.v3.failable._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{EthAddress, EthHash, EthTransaction}

final case class BidLogRecord( bid : ens.Bid, chainId : Int, tld : String, nameServiceAddress : EthAddress )

object BidLog extends RepositoryLog[ BidLogRecord ]("bid-log") {
  def toLine( timestamp : String, record : BidLogRecord ) : String = {
    val BidLogRecord( bid, chainId, tld, nameServiceAddress ) = record
    val a = s"${timestamp}:chainId=${chainId};tld=${tld};nameServiceAdress=${nameServiceAddress.hex}:"
    val b = s"bidHash=${bid.bidHash.hex};simpleName=${ bid.simpleName };simpleNameHash=${ ens.componentHash( bid.simpleName ).hex };bidderAddress=${ bid.bidderAddress.hex };valueInWei=${ bid.valueInWei };salt=${ bid.salt.hex }"
    a + b
  }

  def logBid( bid : ens.Bid, chainId : Int, tld : String, nameServiceAddress : EthAddress ) : Failable[Unit] = log( BidLogRecord( bid, chainId, tld, nameServiceAddress ) )
}

