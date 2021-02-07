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
  final class InvokerContextNotAvailableException( msg : String, cause : Throwable = null) extends SbtEthereumException( msg, cause )
  final class SenderNotAvailableException( msg : String, cause : Throwable = null) extends SbtEthereumException( msg, cause )
  final class CannotReadDirectoryException( msg : String, cause : Throwable = null ) extends SbtEthereumException( msg, cause )
  final class OperationAbortedByUserException( msg : String ) extends SbtEthereumException( s"Aborted by user: ${msg}", null, noStackTrace = true )
  final class UnexpectedConfigurationException( config : sbt.Configuration ) extends SbtEthereumException( s"A task was executed with unexpected configuration '${config}'." )

  final class CantReadInteractionException extends SbtEthereumException("Failed to read from sbt.InteractionService! (Perhaps an interactive task was run noninteractively.)")

  final case class EthValue( wei : BigInt, denomination : Denomination ) {
    lazy val denominated : BigDecimal = denomination.fromWei( wei )
    lazy val formatted   : String = s"${denominated} ${denomination.unitName}"
  }

  private [sbtethereum] val SbtKeyLoggerNamePrefix = "sbtkey."

  private [sbtethereum] val Zero256 = Unsigned256( 0 )
  private [sbtethereum] val One256  = Unsigned256( 1 )

  private [sbtethereum] val MainnetChainId          =  1
  private [sbtethereum] val DefaultEphemeralChainId = -1 // negative chain IDs revert to non-identified pre-EIP-155 behavior and are ephemeral, do not permit aliases or compilation info to be stored

  private [sbtethereum] val DefaultSenderAlias        = "default-sender"
  private [sbtethereum] val HardConfiguredSenderAlias = "hard-configured-sender"

  private [sbtethereum] val HomeDir = new java.io.File( sys.props( "user.home" ) ).getAbsoluteFile

  private [sbtethereum] val LineSep = System.lineSeparator

  private [sbtethereum] val CharsetUTF8 = java.nio.charset.StandardCharsets.UTF_8
  private [sbtethereum] val CodecUTF8   = scala.io.Codec.UTF8

  private [sbtethereum] def rounded( bd : BigDecimal ) : BigInt = bd.setScale( 0, BigDecimal.RoundingMode.HALF_UP ).toBigInt

  private [sbtethereum] val EmptyStackTrace = Array.empty[StackTraceElement]

  private [sbtethereum] val StorageSlots = {
    immutable.Map[String,ByteSeqExact32] (
      "eip-1967:storage" -> ByteSeqExact32( "0x360894a13ba1a3210667c828492db98dca3e2076cc3735a920a3ca505d382bbc".decodeHexAsSeq ),
      "eip-1967:beacon"  -> ByteSeqExact32( "0xa3f0ad74e5423aebfd80d3ef4346578335a9a72aeaee59ff6cb3582b35133d50".decodeHexAsSeq ),
      "eip-1967:admin"   -> ByteSeqExact32( "0xb53127684a568b3173ae13b9f8a6016e243e63b6e8ee1178d6a717850b5d6103".decodeHexAsSeq )
    )
  }

  private [sbtethereum] def nst( t : Throwable ) : Throwable = {
    t.setStackTrace( EmptyStackTrace )
    t
  }

  private [sbtethereum]
  case class RichParserInfo(
    chainId                      : Int,
    mbJsonRpcUrl                 : Option[String],
    addressAliases               : immutable.SortedMap[String,EthAddress],
    abiAliases                   : immutable.SortedMap[String,EthHash],
    abiOverrides                 : immutable.Map[EthAddress,Abi],
    nameServiceAddress           : EthAddress,
    exampleNameServiceTld        : String,
    exampleNameServiceReverseTld : String
  )

  private [sbtethereum]
  def syncOut[T]( op : =>T ) : T = System.out.synchronized( op )

  private [sbtethereum]
  def syncOut[T]( newLineBefore : Boolean = false, newLineAfter : Boolean = false )( op : =>T ) : T = System.out.synchronized {
    if ( newLineBefore ) println
    val ret = op
    if ( newLineAfter ) println
    ret
  }

  // due to ClassLoader issues, we have to load the java.util.logging config file manually. grrrr.
  private [sbtethereum]
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
  private [sbtethereum]
  def recursiveListBeneath( dir : File ) : Array[File] = {
    assert( dir.isDirectory )
    if (! dir.canRead() ) throw new CannotReadDirectoryException( s"'${dir}' is not readable!" )
    val these = dir.listFiles
    these ++ these.filter(_.isDirectory).flatMap(recursiveListBeneath)
  }

  private [sbtethereum]
  def recursiveListIncluding( dir : File ) : Array[File] ={
    dir +: recursiveListBeneath( dir )
  }
}


