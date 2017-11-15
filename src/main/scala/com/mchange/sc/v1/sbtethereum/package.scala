package com.mchange.sc.v1

import com.mchange.sc.v1.log.MLevel._
import com.mchange.sc.v1.consuela.ethereum._
import com.mchange.sc.v1.log.MLogger
import jsonrpc.{Abi,Compilation}
import specification.Denominations.Denomination // XXX: Ick! Refactor this in consuela!
import scala.collection._

package object sbtethereum {

  private implicit lazy val logger: MLogger = mlogger( "com.mchange.sc.v1.sbtethereum.package" )

  class SbtEthereumException( msg : String, cause : Throwable = null ) extends Exception( msg, cause )

  final class NoSolidityCompilerException( msg : String ) extends SbtEthereumException( msg )
  final class SchemaVersionException( msg : String )      extends SbtEthereumException( msg )
  final class ContractUnknownException( msg : String )    extends SbtEthereumException( msg )
  final class BadCodeFormatException( msg : String )      extends SbtEthereumException( msg )
  final class RepositoryException( msg : String )         extends SbtEthereumException( msg )
  final class CompilationFailedException( msg : String )  extends SbtEthereumException( msg )
  final class SenderNotAvailableException( msg : String ) extends SbtEthereumException( msg )


  final case class EthValue( wei : BigInt, denomination : Denomination ) {
    lazy val denominated : BigDecimal = denomination.fromWei( wei ) 
  }

  val MainnetIdentifier = "mainnet"
  val TestrpcIdentifier = "testrpc"

  val DefaultSenderAlias = "defaultSender"

  val EmptyAbi: Abi = Abi.empty

  def rounded( bd : BigDecimal ): BigDecimal = bd.round( bd.mc ) // work around absence of default rounded method in scala 2.10 BigDecimal

  def abiForAddress( blockchainId : String, address : EthAddress, defaultNotInDatabase : => Abi ) : Abi = {
    def findMemorizedAbi = {
      val mbAbi = repository.Database.getMemorizedContractAbi( blockchainId, address ).get // again, throw if database problem
      mbAbi.getOrElse( defaultNotInDatabase )
    }

    val mbDeployedContractInfo = repository.Database.deployedContractInfoForAddress( blockchainId, address ).get // throw an Exception if there's a database problem
    mbDeployedContractInfo.fold( findMemorizedAbi ) { deployedContractInfo =>
      deployedContractInfo.mbAbi match {
        case Some( abi ) => abi
        case None        => findMemorizedAbi
      }
    }
  }

  def abiForAddress( blockchainId : String, address : EthAddress ) : Abi = {
    def defaultNotInDatabase = throw new ContractUnknownException( s"A contract at address ${ address.hex } is not known in the sbt-ethereum repository." )
    abiForAddress( blockchainId, address, defaultNotInDatabase )
  }

  def abiForAddressOrEmpty( blockchainId : String, address : EthAddress ) : Abi = {
    abiForAddress( blockchainId, address, EmptyAbi )
  }

  final object SpawnInstruction {
    final case object Auto                                                                                       extends SpawnInstruction
    final case class  UncompiledName( name : String )                                                            extends SpawnInstruction
    final case class  Full( deploymentAlias : String, args : immutable.Seq[String], seed : MaybeSpawnable.Seed ) extends SpawnInstruction
  }
  sealed trait SpawnInstruction

  object MaybeSpawnable {
    final case class Seed( contractName : String, codeHex : String, abi : Abi, currentCompilation : Boolean )

    implicit final object CompilationBindingIsMaybeSpawnable extends MaybeSpawnable[Tuple2[String,Compilation.Contract]] {
      def mbSeed( binding : ( String, Compilation.Contract ) ) : Option[Seed] = {
        val ( name, contract ) = binding
        contract.info.mbAbi.map( abi => Seed( name, contract.code, abi, true ) )
      }
    }
    implicit final object DatabaseCompilationInfoIsMaybeSpawnable extends MaybeSpawnable[repository.Database.CompilationInfo] {
      def mbSeed( dci : repository.Database.CompilationInfo ) : Option[Seed] = {
        for {
          abi <- dci.mbAbi
          name <- dci.mbName
        } yield {
          Seed( name, dci.code, abi, false )
        }
      }
    }
  }
  trait MaybeSpawnable[T] {
    def mbSeed( t : T ) : Option[MaybeSpawnable.Seed]
  }
}


