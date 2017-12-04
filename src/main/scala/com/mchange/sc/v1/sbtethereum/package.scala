package com.mchange.sc.v1

import com.mchange.sc.v1.log.MLevel._
import com.mchange.sc.v2.failable._
import com.mchange.sc.v1.consuela.ethereum._
import ethabi._
import jsonrpc.{Abi,Compilation,Client}
import specification.Denominations.Denomination // XXX: Ick! Refactor this in consuela!
import specification.Types.Unsigned256
import scala.collection._

package object sbtethereum {

  private implicit lazy val logger = mlogger( "com.mchange.sc.v1.sbtethereum.package" )

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

  val Zero256 = Unsigned256( 0 )
  val One256  = Unsigned256( 1 )

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


  private def decodeStatus( status : Option[Unsigned256] ) : String = status.fold( "Unknown" ){ swrapped =>
    swrapped match {
      case Zero256 => "FAILED"
      case One256  => "SUCCEEDED"
      case _       => s"Unexpected status ${swrapped.widen}"
    }
  }

  // TODO: pretty up logs output
  def prettyClientTransactionReceipt( mbabi : Option[Abi], ctr : Client.TransactionReceipt ) : String = {
    val events = {
      val seq_f_events = {
        mbabi.fold( immutable.Seq.empty[Failable[SolidityEvent]] ){ abi =>
          val interpretor = SolidityEvent.Interpretor( abi )
          ctr.logs map { interpretor.interpret(_) }
        }
      }
      val f_seq_events = {
        Failable.sequence( seq_f_events ) recover { fail : Fail =>
          WARNING.log( s"Failed to interpret events! Failure: $fail" )
          Nil
        }
      }
      f_seq_events.get
    }

    s"""|Transaction Receipt:
        |       Transaction Hash:    0x${ctr.transactionHash.hex}
        |       Transaction Index:   ${ctr.transactionIndex.widen}
        |       Transaction Status:  ${ decodeStatus( ctr.status ) }
        |       Block Hash:          0x${ctr.blockHash.hex}
        |       Block Number:        ${ctr.blockNumber.widen}
        |       Cumulative Gas Used: ${ctr.cumulativeGasUsed.widen}
        |       Contract Address:    ${ctr.contractAddress.fold("None")( ea => "0x" + ea.hex )}
        |       Logs:                ${if (ctr.logs.isEmpty) "None" else ctr.logs.mkString("\n                            ")}
        |       Events:              ${if (events.isEmpty) "None" else events.mkString("\n                            ")}""".stripMargin     
  }

  def prettyPrintEval( log : sbt.Logger, mbabi : Option[Abi], ctr : Client.TransactionReceipt ) : Client.TransactionReceipt = {
    log.info( prettyClientTransactionReceipt( mbabi, ctr ) )
    ctr
  }

  def prettyPrintEval( log : sbt.Logger, mbabi : Option[Abi], mbctr : Option[Client.TransactionReceipt] ) : Option[Client.TransactionReceipt] = {
    mbctr.foreach { ctr =>
      log.info( prettyClientTransactionReceipt( mbabi, ctr ) )
    }
    mbctr
  }

}


