package com.mchange.sc.v1.sbtethereum.util

import scala.collection._
import scala.concurrent.Future

import com.mchange.sc.v1.sbtethereum.{rounded, shoebox}

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{ethabi, EthAddress, EthHash, EthSigner}
import com.mchange.sc.v1.consuela.ethereum.ethabi.Encoder
import com.mchange.sc.v1.consuela.ethereum.jsonrpc.Invoker
import com.mchange.sc.v1.consuela.ethereum.specification.Types.{Unsigned8,Unsigned256}

final object Erc20 {
  private val Big10 = BigInt(10)
  private val Zero256 = Unsigned256( 0 )

  private lazy val StringEncoder  = Encoder.UTF8String
  private lazy val UInt8Encoder   = Encoder.encoderForSolidityType("uint8").get.asInstanceOf[Encoder[BigInt]]
  private lazy val UInt256Encoder = Encoder.UInt256

  private val DecimalsCallData    = "0x313ce567".decodeHexAsSeq
  private val NameCallData        = "0x06fdde03".decodeHexAsSeq
  private val SymbolCallData      = "0x95d89b41".decodeHexAsSeq
  private val TotalSupplyCallData = "0x18160ddd".decodeHexAsSeq

  // some contracts ( probably due to an overzealous linter https://github.com/protofire/solhint/issues/53 )
  // use ALL-CAPS versions of NAME / SYMBOL / DECIMALS
  private val DecimalsAllCapsCallData = "0x2e0f2625".decodeHexAsSeq
  private val NameAllCapsCallData     = "0xa3f4df7e".decodeHexAsSeq
  private val SymbolAllCapsCallData   = "0xf76f8d78".decodeHexAsSeq

  private lazy val TransferFunction = ethabi.abiFunctionForFunctionNameAndTypes( "transfer", immutable.Seq( "address", "uint" ), Erc20.Abi ).assert
  private lazy val ApproveFunction  = ethabi.abiFunctionForFunctionNameAndTypes( "approve", immutable.Seq( "address", "uint" ), Erc20.Abi ).assert

  private lazy val DecimalsFunction    = ethabi.abiFunctionForFunctionNameAndTypes( "decimals", Nil, Erc20.Abi ).assert
  private lazy val NameFunction        = ethabi.abiFunctionForFunctionNameAndTypes( "name", Nil, Erc20.Abi ).assert
  private lazy val SymbolFunction      = ethabi.abiFunctionForFunctionNameAndTypes( "symbol", Nil, Erc20.Abi ).assert
  private lazy val TotalSupplyFunction = ethabi.abiFunctionForFunctionNameAndTypes( "totalSupply", Nil, Erc20.Abi ).assert

  private lazy val BalanceOfFunction = ethabi.abiFunctionForFunctionNameAndTypes( "balanceOf", immutable.Seq( "address" ), Erc20.Abi ).assert

  private lazy val AllowanceFunction = ethabi.abiFunctionForFunctionNameAndTypes( "allowance", immutable.Seq( "address", "address" ), Erc20.Abi ).assert


  private [sbtethereum]
  val Abi = shoebox.StandardAbi.Erc20

  private [sbtethereum]
  def toValueInAtoms( numTokens : BigDecimal, decimals : Int ) : BigInt = rounded( numTokens * BigDecimal(Big10.pow( decimals )) )

  private [sbtethereum]
  def toValueInTokens( numAtoms : BigInt, decimals : Int ) : BigDecimal = BigDecimal( numAtoms ) / BigDecimal(Big10.pow( decimals ))

  private [sbtethereum]
  def doTransfer( tokenContractAddress : EthAddress, fromSigner : EthSigner, toAddress : EthAddress, numAtoms : BigInt, forceNonce : Option[Unsigned256] )( implicit ic : Invoker.Context ) : Future[EthHash] = {
    val f_calldata = Future {
      val reps = immutable.Seq( toAddress, numAtoms );
      ethabi.callDataForAbiFunctionFromEncoderRepresentations( reps, TransferFunction ).assert
    }( ic.econtext )
    f_calldata.flatMap( calldata => Invoker.transaction.sendMessage( fromSigner, tokenContractAddress, Zero256, calldata, forceNonce ) )( ic.econtext )
  }

  private [sbtethereum]
  def doApprove( tokenContractAddress : EthAddress, fromSigner : EthSigner, approvedAddress : EthAddress, numAtoms : BigInt, forceNonce : Option[Unsigned256] )( implicit ic : Invoker.Context ) : Future[EthHash] = {
    val f_calldata = Future {
      val reps = immutable.Seq( approvedAddress, numAtoms );
      ethabi.callDataForAbiFunctionFromEncoderRepresentations( reps, ApproveFunction ).assert
    }( ic.econtext )
    f_calldata.flatMap( calldata => Invoker.transaction.sendMessage( fromSigner, tokenContractAddress, Zero256, calldata, forceNonce ) )( ic.econtext )
  }

  private [sbtethereum]
  def lookupAtomBalance( tokenContractAddress : EthAddress, tokenHolderAddress : EthAddress )( implicit ic : Invoker.Context ) : Future[Unsigned256] = {
    implicit val ec = ic.econtext
    val f_calldata = Future {
      val reps = immutable.Seq( tokenHolderAddress );
      ethabi.callDataForAbiFunctionFromEncoderRepresentations( reps, BalanceOfFunction ).assert
    }
    f_calldata.flatMap { calldata =>
      Invoker.constant.sendMessage( EthAddress.Zero, tokenContractAddress, Zero256, calldata ).map { ret =>
        val bigint = ethabi.decodeReturnValuesForFunction( ret, BalanceOfFunction ).assert.head.value.asInstanceOf[BigInt]
        Unsigned256(bigint)
      }
    }
  }

  private [sbtethereum]
  def lookupAllowanceAtoms( tokenContractAddress : EthAddress, ownerAddress : EthAddress, allowedAddress : EthAddress )( implicit ic : Invoker.Context ) : Future[Unsigned256] = {
    implicit val ec = ic.econtext
    val f_calldata = Future {
      val reps = immutable.Seq( ownerAddress, allowedAddress );
      ethabi.callDataForAbiFunctionFromEncoderRepresentations( reps, AllowanceFunction ).assert
    }
    f_calldata.flatMap { calldata =>
      Invoker.constant.sendMessage( EthAddress.Zero, tokenContractAddress, Zero256, calldata ).map { ret =>
        val bigint = ethabi.decodeReturnValuesForFunction( ret, AllowanceFunction ).assert.head.value.asInstanceOf[BigInt]
        Unsigned256(bigint)
      }
    }
  }

  private [sbtethereum]
  def lookupDecimals( tokenContractAddress : EthAddress )( implicit ic : Invoker.Context ) : Future[Unsigned8] = {
    implicit val ec = ic.econtext
    Invoker.constant.sendMessage( EthAddress.Zero, tokenContractAddress, Zero256, DecimalsCallData ).recoverWith { case e : Exception =>
      Invoker.constant.sendMessage( EthAddress.Zero, tokenContractAddress, Zero256, DecimalsAllCapsCallData )
    }.map { ret =>
      val bigint = ethabi.decodeReturnValuesForFunction( ret, DecimalsFunction ).assert.head.value.asInstanceOf[BigInt]
      Unsigned8(bigint)
    }
  }

  private [sbtethereum]
  def lookupName( tokenContractAddress : EthAddress )( implicit ic : Invoker.Context ) : Future[String] = {
    implicit val ec = ic.econtext
    Invoker.constant.sendMessage( EthAddress.Zero, tokenContractAddress, Zero256, NameCallData ).recoverWith { case e : Exception =>
      Invoker.constant.sendMessage( EthAddress.Zero, tokenContractAddress, Zero256, NameAllCapsCallData )
    }.map { ret =>
      val arr = ethabi.decodeReturnValuesForFunction( ret, NameFunction ).assert.head.value.asInstanceOf[immutable.Seq[Byte]].toArray
      new String( arr, "UTF8" )
    }
  }

  private [sbtethereum]
  def lookupSymbol( tokenContractAddress : EthAddress )( implicit ic : Invoker.Context ) : Future[String] = {
    implicit val ec = ic.econtext
    Invoker.constant.sendMessage( EthAddress.Zero, tokenContractAddress, Zero256, SymbolCallData ).recoverWith { case e : Exception =>
      Invoker.constant.sendMessage( EthAddress.Zero, tokenContractAddress, Zero256, SymbolAllCapsCallData )
    }.map { ret =>
      val arr = ethabi.decodeReturnValuesForFunction( ret, SymbolFunction ).assert.head.value.asInstanceOf[immutable.Seq[Byte]].toArray
      new String( arr, "UTF8" )
    }
  }

  private [sbtethereum]
  def lookupTotalSupplyAtoms( tokenContractAddress : EthAddress )( implicit ic : Invoker.Context ) : Future[Unsigned256] = {
    implicit val ec = ic.econtext
    Invoker.constant.sendMessage( EthAddress.Zero, tokenContractAddress, Zero256, TotalSupplyCallData ).map { ret =>
      val bigint = ethabi.decodeReturnValuesForFunction( ret, TotalSupplyFunction ).assert.head.value.asInstanceOf[BigInt]
      Unsigned256(bigint)
    }
  }

  case class Balance( atoms : BigInt, decimals : Option[Int] ) {
    lazy val tokens : Option[BigDecimal] = decimals.map( d => toValueInTokens( atoms, d ) )
  }
}

