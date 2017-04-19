package com.mchange.sc.v1

import com.mchange.sc.v1.log.MLevel._

import com.mchange.sc.v1.consuela.ethereum._
import jsonrpc20.Abi
import specification.Denominations.Denomination // XXX: Ick! Refactor this in consuela!

package object sbtethereum {

  private implicit lazy val logger = mlogger( "com.mchange.sc.v1.sbtethereum.package" )

  abstract class SbtEthereumException( msg : String, cause : Throwable = null ) extends Exception( msg, cause )

  final class NoSolidityCompilerException( msg : String ) extends SbtEthereumException( msg )
  final class DatabaseVersionException( msg : String )    extends SbtEthereumException( msg )
  final class ContractUnknownException( msg : String )    extends SbtEthereumException( msg )
  final class BadCodeFormatException( msg : String )      extends SbtEthereumException( msg )
  final class RepositoryException( msg : String )         extends SbtEthereumException( msg )
  final class CompilationFailedException( msg : String )  extends SbtEthereumException( msg )

  case class EthValue( wei : BigInt, denominated : BigDecimal, denomination : Denomination )

  val MainnetIdentifier = "mainnet"

  val EmptyAbi = Abi.Definition.empty

  def rounded( bd : BigDecimal ) = bd.round( bd.mc ) // work around absence of default rounded method in scala 2.10 BigDecimal

  def abiForAddress( blockchainId : String, address : EthAddress, defaultNotInDatabase : => Abi.Definition ) : Abi.Definition = {
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

  def abiForAddress( blockchainId : String, address : EthAddress ) : Abi.Definition = {
    def defaultNotInDatabase = throw new ContractUnknownException( s"A contract at address ${address.hex} is not known in the sbt-ethereum repository." )
    abiForAddress( blockchainId, address, defaultNotInDatabase )
  }

  def abiForAddressOrEmpty( blockchainId : String, address : EthAddress ) : Abi.Definition = {
    abiForAddress( blockchainId, address, EmptyAbi )
  }



}


