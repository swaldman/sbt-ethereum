package com.mchange.sc.v1.sbtethereum.util

import Formatting._
import InteractiveQuery._

import com.mchange.sc.v1.sbtethereum.{CharsetUTF8, DefaultEphemeralChainId, PriceFeed, PrivateKeyFinder, SbtEthereumException}

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum._

import com.mchange.sc.v3.failable._
import com.mchange.sc.v2.literal._

import scala.collection._

import play.api.libs.json.Json

object CautiousSigner {
  type AbiOverridesForChainIdFinder = Int => immutable.Map[EthAddress, jsonrpc.Abi]
}

import CautiousSigner._

class CautiousSigner private [sbtethereum] (
  log          : sbt.Logger,
  is           : sbt.InteractionService,
  priceFeed    : PriceFeed,
  currencyCode : String,
  description  : Option[String]
)( privateKeyFinder : PrivateKeyFinder, abiOverridesForChainId : AbiOverridesForChainIdFinder ) extends EthSigner {

  // throws if the check fails
  private def doCheckDocument( documentBytes : Seq[Byte], mbChainId : Option[EthChainId] ) : Unit = {
    val address = privateKeyFinder.address
    val chainId = {
      mbChainId.fold( DefaultEphemeralChainId ){ ecid =>
        val bi = ecid.value.widen
        if ( bi.isValidInt ) bi.toInt else throw new SbtEthereumException( s"Chain IDs outside the range of Ints are not supported. Found ${bi}" )
      }
    }
    def handleSignTransaction( utxn : EthTransaction.Unsigned ) : Unit = {
      displayTransactionSignatureRequest( log, chainId, abiOverridesForChainId( chainId ), priceFeed, currencyCode, utxn, address, description )
      val ok = queryYN( is, s"Are you sure it is okay to sign this transaction as ${verboseAddress(chainId, address)}? [y/n] " )
      if (!ok) aborted( "User chose not to sign proposed transaction." )
    }
    def handleSignUnknown = {
      println()
      println(    "==> D O C U M E N T   S I G N A T U R E   R E Q U E S T")
      println(    "==>")

      description.foreach { desc =>
        println(   s"==> Signer: ${desc}" )
        println(    "==>")
      }

      println(   s"==> The signing address would be ${verboseAddress(chainId,address)}." )
      println(    "==>")
      println( s"""==> This data does not appear to be a transaction${if (chainId < 0 ) "." else " for chain with ID " + chainId + "."}""" )
      println(    "==>")
      println( s"""==> Raw data: ${hexString(documentBytes)}""" )
      println(    "==>")
      val rawString = new String( documentBytes.toArray, CharsetUTF8 )
      println( s"""==> Raw data interpreted as as UTF8 String: ${ StringLiteral.formatUnicodePermissiveStringLiteral( rawString ) }""" )
      println()
      Failable( Json.prettyPrint( Json.parse( documentBytes.toArray ) ) ).foreach { pretty =>
        println( s"The data can be interpreted as JSON. Pretty printing: ${pretty}" )
      }
      println()
      description.foreach( desc => println( s"Signer: ${desc}]" ) )
      val ok = queryYN( is, s"Are you sure it is okay to sign this data as ${verboseAddress(chainId, address)}? [y/n] " )
      if (!ok) aborted( "User chose not to sign the nontransaction data." )
    }
    EthTransaction.Unsigned.areSignableBytesForChainId( documentBytes, mbChainId ) match {
      case Some( utxn : EthTransaction.Unsigned ) => handleSignTransaction( utxn )
      case None                                   => handleSignUnknown
    }
  }
  private def doCheckHash( documentHash : EthHash, mbChainId : Option[EthChainId] ) : Unit = {
    val chainId = {
      mbChainId.fold( -1 ){ ecid =>
        val bi = ecid.value.widen
        if ( bi.isValidInt ) bi.toInt else throw new SbtEthereumException( s"Chain IDs outside the range of Ints are not supported. Found ${bi}" )
      }
    }
    val address = privateKeyFinder.address
      println()
      println(    "==> H A S H   S I G N A T U R E   R E Q U E S T")
      println(    "==>")

      description.foreach { desc =>
        println(   s"==> Signer: ${desc}" )
        println(    "==>")
      }

    println( s"==> The application is attempting to sign a hash of some document which sbt-ethereum cannot identify, as ${verboseAddress(chainId, address)}." )
    println(  "==>")
    println( s"==> Hash bytes: ${hexString( documentHash )}" )
    println()
    val ok = queryYN( is, "Do you understand the document whose hash the application proposes to sign, and trust the application to sign it?" )
    if (!ok) aborted( "User chose not to sign proposed document hash." )
  }

  override def sign( document : Array[Byte] ) : EthSignature.Basic = {
    this.sign( document.toImmutableSeq )
  }
  override def sign( document : Seq[Byte] )   : EthSignature.Basic = {
    doCheckDocument( document, None )
    val out = privateKeyFinder.find().sign( document )
    println( "Document successfully signed." )
    println( "----------------------------" )
    println()
    out
  }
  override def signPrehashed( documentHash : EthHash ) : EthSignature.Basic = {
    doCheckHash( documentHash, None )
    val out = privateKeyFinder.find().signPrehashed( documentHash )
    println( "Hash successfully signed." )
    println( "------------------------" )
    println()
    out
  }
  override def sign( document : Array[Byte], chainId : EthChainId ) : EthSignature.WithChainId = {
    doCheckDocument( document.toImmutableSeq, Some( chainId ) )
    privateKeyFinder.find().sign( document, chainId )
  }
  override def sign( document : Seq[Byte], chainId : EthChainId ) : EthSignature.WithChainId = {
    doCheckDocument( document, Some( chainId ) )
    privateKeyFinder.find().sign( document, chainId )
  }
  override def signPrehashed( documentHash : EthHash, chainId : EthChainId ) : EthSignature.WithChainId = {
    doCheckHash( documentHash, Some( chainId ) )
    signPrehashed( documentHash, chainId )
  }
  override def address : EthAddress = privateKeyFinder.address
}


