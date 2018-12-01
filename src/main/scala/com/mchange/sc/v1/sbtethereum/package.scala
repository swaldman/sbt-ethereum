package com.mchange.sc.v1

import com.mchange.sc.v1.log.MLevel._
import com.mchange.sc.v3.failable._
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v2.ens
import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum._
import ethabi._
import jsonrpc.{Abi,Compilation,Client}
import specification.Denominations.Denomination // XXX: Ick! Refactor this in consuela!
import specification.Types.Unsigned256
import scala.collection._
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import java.io.File
import java.time.{Instant, ZoneId}
import java.time.format.{FormatStyle, DateTimeFormatter}
import play.api.libs.json._

package object sbtethereum {

  private implicit lazy val logger = mlogger( "com.mchange.sc.v1.sbtethereum.package" )

  class SbtEthereumException( msg : String, cause : Throwable = null, noStackTrace : Boolean = false ) extends Exception( msg, cause ) {
    if ( noStackTrace ) this.setStackTrace( EmptyStackTrace )
  }

  final class NoSolidityCompilerException( msg : String )      extends SbtEthereumException( msg )
  final class SchemaVersionException( msg : String )           extends SbtEthereumException( msg )
  final class AbiUnknownException( msg : String )              extends SbtEthereumException( msg )
  final class ContractUnknownException( msg : String )         extends SbtEthereumException( msg )
  final class BadCodeFormatException( msg : String )           extends SbtEthereumException( msg )
  final class ShoeboxException( msg : String )                 extends SbtEthereumException( msg )
  final class CompilationFailedException( msg : String )       extends SbtEthereumException( msg )
  final class SenderNotAvailableException( msg : String )      extends SbtEthereumException( msg )
  final class NoSuchCompilationException( msg : String )       extends SbtEthereumException( msg )
  final class FailureOnInitializationException( msg : String ) extends SbtEthereumException( msg, null, noStackTrace = true )

  final class CannotReadDirectoryException( msg : String, cause : Throwable = null ) extends SbtEthereumException( msg, cause )
  final class OperationAbortedByUserException( msg : String ) extends SbtEthereumException( s"Aborted by user: ${msg}", null, noStackTrace = true )
  final class NotCurrentlyUnderAuctionException( name : String, status : ens.NameStatus ) extends SbtEthereumException( s"ENS name '${name}' is not currently under auction. Its status is '${status}'." )


  final case class EthValue( wei : BigInt, denomination : Denomination ) {
    lazy val denominated : BigDecimal = denomination.fromWei( wei ) 
  }

  val Zero256 = Unsigned256( 0 )
  val One256  = Unsigned256( 1 )

  val MainnetChainId          =  1
  val DefaultEphemeralChainId = -1 // negative chain IDs revert to non-identified pre-EIP-155 behavior and are ephemeral, do not permit aliases or compilation info to be stored

  val DefaultSenderAlias = "defaultSender"

  val HomeDir = new java.io.File( sys.props( "user.home" ) ).getAbsoluteFile

  def rounded( bd : BigDecimal ) : BigInt = bd.setScale( 0, BigDecimal.RoundingMode.HALF_UP ).toBigInt

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
    implicit final object DatabaseCompilationInfoIsMaybeSpawnable extends MaybeSpawnable[shoebox.Database.CompilationInfo] {
      def mbSeed( dci : shoebox.Database.CompilationInfo ) : Option[Seed] = {
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
  // XXX: "Events" will show "None" when the ABI is missing, even if there were events. Fix this!
  def prettyClientTransactionReceipt( mbabi : Option[Abi], ctr : Client.TransactionReceipt ) : String = {
    val events = {
      val seq_f_events = {
        mbabi.fold( immutable.Seq.empty[Failable[SolidityEvent]] ){ abi =>
          val interpretor = SolidityEvent.Interpretor( abi )
          ctr.logs map { interpretor.interpret(_) }
        }
      }
      val f_seq_events = {
        Failable.sequence( seq_f_events ) recover { failed =>
          WARNING.log( s"Failed to interpret events! Failure: $failed" )
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
        |       From:                ${if (ctr.from.isEmpty) "Unknown" else hexString(ctr.from.get)}
        |       To:                  ${if (ctr.to.isEmpty) "Unknown" else hexString(ctr.to.get)}
        |       Cumulative Gas Used: ${ctr.cumulativeGasUsed.widen}
        |       Gas Used:            ${ctr.gasUsed.widen}
        |       Contract Address:    ${ctr.contractAddress.fold("None")( ea => "0x" + ea.hex )}
        |       Logs:                ${if (ctr.logs.isEmpty) "None" else ctr.logs.mkString("\n                            ")}
        |       Events:              ${if (events.isEmpty) "None" else events.mkString("\n                            ")}""".stripMargin     
  }

  def prettyPrintEval( log : sbt.Logger, mbabi : Option[Abi], ctr : Client.TransactionReceipt ) : Client.TransactionReceipt = {
    log.info( prettyClientTransactionReceipt( mbabi, ctr ) )
    ctr
  }

  def prettyPrintEval( log : sbt.Logger, mbabi : Option[Abi], txnHash : EthHash, timeout : Duration, mbctr : Option[Client.TransactionReceipt] ) : Option[Client.TransactionReceipt] = {
    mbctr match {
      case Some( ctr ) => log.info( prettyClientTransactionReceipt( mbabi, ctr ) )
      case None        => log.warn( s"Failed to mine transaction with hash '${hexString(txnHash)}' within timeout of ${timeout}!" )
    }
    mbctr
  }

  def prettyPrintEval( log : sbt.Logger, mbabi : Option[Abi], txnHash : EthHash, timeout : Duration, ctr : Client.TransactionReceipt ) : Client.TransactionReceipt = {
    log.info( prettyClientTransactionReceipt( mbabi, ctr ) )
    ctr
  }

  private val InstantFormatter = DateTimeFormatter.RFC_1123_DATE_TIME.withZone( ZoneId.systemDefault() )
  private val TimeFormatter    = DateTimeFormatter.ofLocalizedTime( FormatStyle.SHORT ).withZone( ZoneId.systemDefault() )

  def formatInstant( instant : Instant ) : String = InstantFormatter.format( instant )

  def formatInstant( l : Long ) : String = formatInstant( Instant.ofEpochMilli( l ) )

  def formatTime( l : Long ) : String = TimeFormatter.format( Instant.ofEpochMilli( l ) )

  def hexString( bytes : Seq[Byte]    ) = s"0x${bytes.hex}"
  def hexString( bytes : Array[Byte]  ) = s"0x${bytes.hex}"
  def hexString( address : EthAddress ) = s"0x${address.hex}"
  def hexString( hash : EthHash )       = s"0x${hash.hex}"

  val EmptyStackTrace = Array.empty[StackTraceElement]

  def nst( t : Throwable ) : Throwable = {
    t.setStackTrace( EmptyStackTrace )
    t
  }

  case class RichParserInfo(
    chainId               : Int,
    jsonRpcUrl            : String,
    addressAliases        : immutable.SortedMap[String,EthAddress],
    abiAliases            : immutable.SortedMap[String,EthHash],
    abiOverrides          : immutable.Map[EthAddress,Abi],
    nameServiceAddress    : EthAddress,
    nameServiceTld        : String,
    nameServiceReverseTld : String
  )

  // due to ClassLoader issues, we have to load the java.util.logging config file manually. grrrr.
  def initializeLoggingConfig() : Unit = {
    import java.util.logging._

    try {
      borrow( this.getClass().getResourceAsStream("/logging.properties") ) { is =>
        LogManager.getLogManager().readConfiguration( is )
      }
    }
    catch {
      case NonFatal(e) => {
        Console.err.println("A problem occurred while initializing the logging system. Logs may not function.")
        e.printStackTrace()
      }
    }
  }

  // modified from Rex Kerr, https://stackoverflow.com/questions/2637643/how-do-i-list-all-files-in-a-subdirectory-in-scala
  // should I use sbt.Path stuff instead?
  def recursiveListBeneath( dir : File ) : Array[File] = {
    assert( dir.isDirectory )
    if (! dir.canRead() ) throw new CannotReadDirectoryException( s"'${dir}' is not readable!" )
    val these = dir.listFiles
    these ++ these.filter(_.isDirectory).flatMap(recursiveListBeneath)
  }

  def recursiveListIncluding( dir : File ) : Array[File] ={
    dir +: recursiveListBeneath( dir )
  }
}


