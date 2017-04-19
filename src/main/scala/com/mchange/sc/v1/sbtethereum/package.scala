package com.mchange.sc.v1

import java.io.File

import com.mchange.sc.v1.log.MLevel._

import com.mchange.sc.v1.consuela._

import com.mchange.sc.v1.consuela.ethereum.{jsonrpc20,encoding,specification,wallet,EthAddress,EthHash,EthPrivateKey,EthTransaction}
import jsonrpc20.Abi
import specification.Denominations.Denomination // XXX: Ick! Refactor this in consuela!

import play.api.libs.json._

package object sbtethereum {
  private implicit lazy val logger = mlogger( "com.mchange.sc.v1.sbtethereum.package" )

  abstract class SbtEthereumException( msg : String, cause : Throwable = null ) extends Exception( msg, cause )

  final class NoSolidityCompilerException( msg : String )                                        extends SbtEthereumException( msg )
  final class DatabaseVersionException( msg : String )                                           extends SbtEthereumException( msg )
  final class ContractUnknownException( msg : String )                                           extends SbtEthereumException( msg )
  final class BadCodeFormatException( msg : String )                                             extends SbtEthereumException( msg )
  final class RepositoryException( msg : String )                                                extends SbtEthereumException( msg )
  final class CompilationFailedException( msg : String )                                         extends SbtEthereumException( msg )

  case class EthValue( wei : BigInt, denominated : BigDecimal, denomination : Denomination )

  val EmptyAbi = Abi.Definition.empty

  val MainnetIdentifier = "mainnet"

  // some formatting functions for ascii tables
  def emptyOrHex( str : String ) = if (str == null) "" else s"0x$str"
  def blankNull( str : String ) = if (str == null) "" else str
  def span( len : Int ) = (0 until len).map(_ => "-").mkString

  private [sbtethereum] def rounded( bd : BigDecimal ) = bd.round( bd.mc ) // work around absence of default rounded method in scala 2.10 BigDecimal

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


  private final val CantReadInteraction = "InteractionService failed to read"

  private [sbtethereum] def readConfirmCredential(  log : sbt.Logger, is : sbt.InteractionService, readPrompt : String, confirmPrompt: String = "Please retype to confirm: ", maxAttempts : Int = 3, attempt : Int = 0 ) : String = {
    if ( attempt < maxAttempts ) {
      val credential = is.readLine( readPrompt, mask = true ).getOrElse( throw new Exception( CantReadInteraction ) )
      val confirmation = is.readLine( confirmPrompt, mask = true ).getOrElse( throw new Exception( CantReadInteraction ) )
      if ( credential == confirmation ) {
        credential
      } else {
        log.warn("Entries did not match! Retrying.")
        readConfirmCredential( log, is, readPrompt, confirmPrompt, maxAttempts, attempt + 1 )
      }
    } else {
      throw new Exception( s"After ${attempt} attempts, provided credential could not be confirmed. Bailing." )
    }
  }

  private def parseAbi( abiString : String ) = Json.parse( abiString ).as[Abi.Definition]

  private [sbtethereum] def readAddressAndAbi( log : sbt.Logger, is : sbt.InteractionService ) : ( EthAddress, Abi.Definition ) = {
    val address = EthAddress( is.readLine( "Contract address in hex: ", mask = false ).getOrElse( throw new Exception( CantReadInteraction ) ) )
    val abi = parseAbi( is.readLine( "Contract ABI: ", mask = false ).getOrElse( throw new Exception( CantReadInteraction ) ) )
    ( address, abi )
  }

  private [sbtethereum] def readV3Wallet( is : sbt.InteractionService ) : wallet.V3 = {
    val jsonStr = is.readLine( "V3 Wallet JSON: ", mask = false ).getOrElse( throw new Exception( CantReadInteraction ) )
    val jsv = Json.parse( jsonStr )
    wallet.V3( jsv.as[JsObject] )
  }

  private [sbtethereum] def readCredential( is : sbt.InteractionService, address : EthAddress ) : String = {
    is.readLine(s"Enter passphrase or hex private key for address '0x${address.hex}': ", mask = true).getOrElse(throw new Exception("Failed to read a credential")) // fail if we can't get a credential
  }

  private [sbtethereum] def abiForAddress( blockchainId : String, address : EthAddress, defaultNotInDatabase : => Abi.Definition ) : Abi.Definition = {
    def findMemorizedAbi = {
      val mbAbi = Repository.Database.getMemorizedContractAbi( blockchainId, address ).get // again, throw if database problem
      mbAbi.getOrElse( defaultNotInDatabase )
    }
    val mbDeployedContractInfo = Repository.Database.deployedContractInfoForAddress( blockchainId, address ).get // throw an Exception if there's a database problem
    mbDeployedContractInfo.fold( findMemorizedAbi ) { deployedContractInfo =>
      deployedContractInfo.mbAbiDefinition match {  
        case Some( abiDefinition ) => abiDefinition
        case None                  => findMemorizedAbi
      }
    }
  }

  private [sbtethereum] def abiForAddress( blockchainId : String, address : EthAddress ) : Abi.Definition = {
    def defaultNotInDatabase = throw new ContractUnknownException( s"A contract at address ${address.hex} is not known in the sbt-ethereum repository." )
    abiForAddress( blockchainId, address, defaultNotInDatabase )
  }

  private [sbtethereum] def abiForAddressOrEmpty( blockchainId : String, address : EthAddress ) : Abi.Definition = {
    abiForAddress( blockchainId, address, EmptyAbi )
  }

  private [sbtethereum] def unknownWallet( loadDirs : Seq[File] ) : Nothing = {
    val dirs = loadDirs.map( _.getAbsolutePath() ).mkString(", ")
    throw new Exception( s"Could not find V3 wallet for the specified address in the specified keystore directories: ${dirs}}" )
  }
}


