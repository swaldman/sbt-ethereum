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
import specification.Types.{ByteSeqExact32,Unsigned256}
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
  final class NoSuchCompilationException( msg : String )       extends SbtEthereumException( msg )
  final class FailureOnInitializationException( msg : String ) extends SbtEthereumException( msg, null, noStackTrace = true )

  final class PingFailedException( msg : String, cause : Throwable = null ) extends SbtEthereumException( msg )
  final class NodeUrlNotAvailableException( msg : String, cause : Throwable = null) extends SbtEthereumException( msg, cause )
  final class SenderNotAvailableException( msg : String, cause : Throwable = null) extends SbtEthereumException( msg, cause )
  final class CannotReadDirectoryException( msg : String, cause : Throwable = null ) extends SbtEthereumException( msg, cause )
  final class OperationAbortedByUserException( msg : String ) extends SbtEthereumException( s"Aborted by user: ${msg}", null, noStackTrace = true )
  final class NotCurrentlyUnderAuctionException( name : String, status : ens.NameStatus ) extends SbtEthereumException( s"ENS name '${name}' is not currently under auction. Its status is '${status}'." )
  final class UnexpectedConfigurationException( config : sbt.Configuration ) extends SbtEthereumException( s"A task was executed with unexpected configuration '${config}'." )

  final class CantReadInteractionException extends SbtEthereumException("Failed to read from sbt.InteractionService! (Perhaps an interactive task was run noninteractively.")


  final case class EthValue( wei : BigInt, denomination : Denomination ) {
    lazy val denominated : BigDecimal = denomination.fromWei( wei ) 
  }

  val Zero256 = Unsigned256( 0 )
  val One256  = Unsigned256( 1 )

  val MainnetChainId          =  1
  val DefaultEphemeralChainId = -1 // negative chain IDs revert to non-identified pre-EIP-155 behavior and are ephemeral, do not permit aliases or compilation info to be stored

  val DefaultSenderAlias = "default-sender"

  val HomeDir = new java.io.File( sys.props( "user.home" ) ).getAbsoluteFile

  val LineSep = System.lineSeparator

  def throwCantReadInteraction : Nothing = throw new CantReadInteractionException

  def rounded( bd : BigDecimal ) : BigInt = bd.setScale( 0, BigDecimal.RoundingMode.HALF_UP ).toBigInt

  def aborted( msg : String ) : Nothing = throw new OperationAbortedByUserException( msg )

  final object SpawnInstruction {
    final case object Auto                                                                                       extends SpawnInstruction
    final case class  UncompiledName( name : String )                                                            extends SpawnInstruction
    final case class  Full( deploymentAlias : String, args : immutable.Seq[String], valueInWei : BigInt, seed : MaybeSpawnable.Seed ) extends SpawnInstruction
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
  def prettyClientTransactionReceipt( mbabi : Option[Abi], ctr : Client.TransactionReceipt ) : String = {
    val f_events = {
      val seq_f_events = {
        mbabi.fold( immutable.Seq.empty[Failable[SolidityEvent]] ){ abi =>
          val interpretor = SolidityEvent.Interpretor( abi )
          ctr.logs map { interpretor.interpret(_) }
        }
      }
      Failable.sequence( seq_f_events )
    }

    def decoded( d : Decoded ) : String = {
      d match {
        case dv : Decoded.Value => s"${dv.parameter.name} (of type ${dv.parameter.`type`}): ${dv.stringRep}"
        case dh : Decoded.Hash  => s"${dh.parameter.name} (of type ${dh.parameter.`type`}), whose value hashes to ${hexString(dh.hash)}}"
      }
    }

    def indentedNamedSolidityEvent( num : Int, named : SolidityEvent.Named, indent : Int ) : String = {
      val prespaces = " " * indent

      val numStr = s"${num} => "

      val fullspaces = prespaces + (" " * numStr.length)

      val sb = new StringBuilder()

      sb.append( s"${prespaces}${numStr}${named.name} [source=${hexString(named.address)}] (${LineSep}" )
      val len = named.inputs.length
      (0 until len).foreach { i =>
        sb.append( s"${fullspaces}  ${decoded( named.inputs(i) )}" )
        if ( i != len - 1 ) sb.append(',')
        sb.append( LineSep )
      }
      sb.append( s"${fullspaces})" )

      sb.toString
    }

    def indentedAnonymousSolidityEvent( num : Int, anon : SolidityEvent.Anonymous, indent : Int ) : String = {
      val prespaces = " " * indent

      s"${prespaces}${num} => Anonymous Event [source=${hexString(anon.address)}]"
    }

    def indentedSolidityEvent( num : Int, evt : SolidityEvent, indent : Int ) : String = {
      evt match {
        case named : SolidityEvent.Named => indentedNamedSolidityEvent( num, named, indent ).trim
        case anon  : SolidityEvent.Anonymous => indentedAnonymousSolidityEvent( num, anon, indent ).trim
      }
    }

    def indentedEvents( events : immutable.Seq[SolidityEvent], indent : Int ) : String = {
      val sb = new StringBuilder()
      val len = events.length
      (0 until len).foreach { i =>
        sb.append( indentedSolidityEvent( i, events(i), indent ) )
        if ( i != len-1 ) {
          sb.append(',')
          sb.append( LineSep )
        }
      }
      sb.toString
    }


    def indentedData( data : immutable.Seq[Byte], indent : Int ) : String = {
      val prespaces = " " * indent

      data.grouped(32).map( rowBytes => s"${prespaces}${rowBytes.hex}" ).mkString(s"${LineSep}")
    }

    def indentedLog( num : Int, log : EthLogEntry, indent : Int ) : String = {
      val prespaces = " " * indent

      val sb = new StringBuilder()
      sb.append( prespaces + s"${num} => EthLogEntry [source=${hexString(log.address)}] (${LineSep}" )
      sb.append( prespaces + s"            topics=[${LineSep}" )

      def appendTopic( topic : EthLogEntry.Topic, last : Boolean ) = sb.append( prespaces +  s"""              ${hexString(topic)}${if (!last) "," else ""}${LineSep}""" )

      val len = log.topics.length
      (0 until len).foreach { index =>
        appendTopic( log.topics(index), index == len - 1 )
      }

      sb.append( prespaces + s"            ],${LineSep}" )
      sb.append( prespaces + s"            data=${indentedData(log.data, indent+17).trim}${LineSep}" )
      sb.append( prespaces + s"          )" )

      sb.toString
    }

    def indentedLogs( indent : Int ) : String = {
      val sb = new StringBuilder()
      val len = ctr.logs.length
      (0 until len).foreach { i =>
        sb.append( indentedLog( i, ctr.logs(i), indent ) )
        if ( i != len-1 ) {
          sb.append(',')
          sb.append( LineSep )
        }
      }
      sb.toString
    }

    val withoutEventsStr = {
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
          |       Logs:                ${if (ctr.logs.isEmpty) "None" else indentedLogs(23).trim}""".stripMargin
    }

    f_events match {
      case Succeeded( events ) => {
        mbabi match {
          case Some( abi )  => withoutEventsStr + LineSep + s"""       Events:              ${if (events.isEmpty) "None" else indentedEvents(events, 28).trim}"""
          case None         => withoutEventsStr + LineSep + s"""       Events:              ${if (events.isEmpty) "None" else "<no abi available to interpret logs as events>"}"""
        }
      }
      case oops : Failed[_] => withoutEventsStr + LineSep + s"""       Events:              Something went wrong interpreting events! ${oops}"""
    }
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

  def hexString( bytes : Seq[Byte]    )   = s"0x${bytes.hex}"
  def hexString( bytes : Array[Byte]  )   = s"0x${bytes.hex}"
  def hexString( address : EthAddress )   = s"0x${address.hex}"
  def hexString( hash : EthHash )         = s"0x${hash.hex}"
  def hexString( bytes : ByteSeqExact32 ) = s"0x${bytes.widen.hex}"

  val EmptyStackTrace = Array.empty[StackTraceElement]

  def nst( t : Throwable ) : Throwable = {
    t.setStackTrace( EmptyStackTrace )
    t
  }

  case class RichParserInfo(
    chainId               : Int,
    mbJsonRpcUrl          : Option[String],
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


