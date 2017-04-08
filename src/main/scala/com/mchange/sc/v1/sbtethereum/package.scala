package com.mchange.sc.v1

import sbt._
import sbt.Keys._

import scala.io.{Codec,Source}
import scala.collection._
import scala.concurrent.{Await,ExecutionContext,Future}
import scala.concurrent.duration.Duration
import scala.math.max
import scala.util.Failure
import scala.util.matching.Regex.Match
import scala.annotation.tailrec

import java.io._
import java.nio.file.Files

import com.mchange.sc.v1.log.MLevel._
import com.mchange.sc.v2.concurrent._

import com.mchange.sc.v2.lang.borrow

import com.mchange.sc.v2.failable._
import com.mchange.sc.v2.failable.fail
import com.mchange.sc.v2.literal._

import com.mchange.sc.v1.consuela._

import com.mchange.sc.v1.consuela.ethereum.{jsonrpc20,wallet,EthAddress,EthHash,EthPrivateKey,EthTransaction}
import com.mchange.sc.v1.consuela.ethereum.jsonrpc20.{Abi,ClientTransactionReceipt,MapStringCompilationContractFormat}
import com.mchange.sc.v1.consuela.ethereum.encoding.RLP
import com.mchange.sc.v1.consuela.ethereum.specification.Denominations.Denomination // XXX: Ick! Refactor this in consuela!

import play.api.libs.json._


package object sbtethereum {
  private implicit lazy val logger = mlogger( "com.mchange.sc.v1.sbtethereum.package" )

  private val SEP = Option( System.getProperty("line.separator") ).getOrElse( "\n" )

  abstract class SbtEthereumException( msg : String, cause : Throwable = null ) extends Exception( msg, cause )

  final class NoSolidityCompilerException( msg : String )                                   extends SbtEthereumException( msg )
  final class DatabaseVersionException( msg : String )                                      extends SbtEthereumException( msg )
  final class ContractUnknownException( msg : String )                                      extends SbtEthereumException( msg )
  final class BadCodeFormatException( msg : String )                                        extends SbtEthereumException( msg )
  final class UnparsableFileException( msg : String, line : Int, col : Int )                extends SbtEthereumException( msg + s" [${line}:${col}]" )
  final class RepositoryException( msg : String )                                           extends SbtEthereumException( msg )
  final class CompilationFailedException( msg : String )                                    extends SbtEthereumException( msg )
  final class BadSolidityVersionException( badVersion : String )                            extends SbtEthereumException( s"Bad version string: '$badVersion'" )
  final class IncompatibleSolidityVersionsException( versions : Iterable[SemanticVersion] ) extends SbtEthereumException( s"""Can't reconcile: ${versions.map("^"+_).mkString(", ")}""" )

  private val SolFileRegex = """(.+)\.sol""".r

  // XXX: hardcoded
  private val SolidityWriteBufferSize = 1024 * 1024; //1 MiB

  // XXX: geth seems not to be able to validate some subset of the signatures that we validate as good (and homestead compatible)
  //      to work around this, we just retry a few times when we get "Invalid sender" errors on sending a signed transaction
  val InvalidSenderRetries = 10

  val EmptyAbi = Abi.Definition.empty

  val MainnetIdentifier = "mainnet"

  private [sbtethereum] def doWithJsonClient[T]( log : sbt.Logger, jsonRpcUrl : String )( operation : jsonrpc20.Client => T )( implicit ec : ExecutionContext ) : T = {
    try {
      borrow( new jsonrpc20.Client.Simple( new URL( jsonRpcUrl ) ) )( operation )
    } catch {
      case e : java.net.ConnectException => {
        log.error( s"Failed to connect to JSON-RPC client at '${jsonRpcUrl}': ${e}" )
        throw e
      }
    }
  }

  // some formatting functions for ascii tables
  def emptyOrHex( str : String ) = if (str == null) "" else s"0x$str"
  def blankNull( str : String ) = if (str == null) "" else str
  def span( len : Int ) = (0 until len).map(_ => "-").mkString

  @tailrec
  private def loadResolveSourceFile( allSourceLocations : Seq[SourceFile.Location], key : String, remainingSourceLocations : Seq[SourceFile.Location] ) : Failable[SourceFile] = {
    if ( remainingSourceLocations.isEmpty ){
      fail( s"""Could not resolve file for '${key}', checked source locations: '${allSourceLocations.mkString(", ")}'""" )
    } else {
      val nextSrcLoc = remainingSourceLocations.head
      def premessage( from : String = nextSrcLoc.toString ) = s"Failed to load '${key}' from '${from}': "
      val fsource = Failable( SourceFile( nextSrcLoc, key ) ).xdebug( premessage() )
      if ( fsource.isFailed ) {
        loadResolveSourceFile( allSourceLocations, key, remainingSourceLocations.tail )
      } else {
        substituteImports( allSourceLocations, fsource.get )
      }
    }
  }

  private def loadResolveSourceFile( allSourceLocations : Seq[SourceFile.Location], key : String ) : Failable[SourceFile] = loadResolveSourceFile( allSourceLocations, key, allSourceLocations )

  private def loadResolveSourceFile( file : File, includeLocations : Seq[SourceFile.Location] = Nil ) : Failable[SourceFile] = {
    loadResolveSourceFile( SourceFile.Location( file.getParentFile ) +: includeLocations, file.getName )
  }

  private val ImportRegex = """import\s+(.*)\;""".r
  private val GoodImportBodyRegex = """\s*(\042.*?\042)\s*""".r

  private def substituteImports( allSourceLocations : Seq[SourceFile.Location], input : SourceFile ) : Failable[SourceFile] = {

    var lastModified : Long = input.lastModified

    val ( normalized, tcq ) = TextCommentQuote.parse( input.rawText )
     
    def replaceMatch( m : Match ) : String = {
      if ( tcq.quote.containsPoint( m.start ) ) {          // if the word import is in a quote
        m.group(0)                                         //   replace the match with itself
      } else if ( tcq.comment.containsPoint( m.start ) ) { // if the word import is in a comment
        m.group(0)                                         //   replace the match with itself
      } else {                                             // otherwise, do the replacement
        m.group(1) match {
          case GoodImportBodyRegex( imported ) => {
            val key = StringLiteral.parsePermissiveStringLiteral( imported ).parsed
            val fimport = loadResolveSourceFile( input.immediateParent +: allSourceLocations, key ) // look first local to this file to resolve recursive imports
            val sourceFile = fimport.get // throw the Exception if resolution failed
            lastModified = max( lastModified, sourceFile.lastModified )
            sourceFile.rawText
          }
          case unkey => throw new Exception( s"""Unsupported import format: '${unkey}' [sbt-ethereum supports only simple 'import "<filespec>"', without 'from' or 'as' clauses.]""" )
        }
      }
    }

    val resolved = ImportRegex.replaceAllIn( normalized, replaceMatch _ )

    succeed( SourceFile( input.immediateParent, resolved, lastModified ) )
  }

  private val SolidityFileBadFirstChars = ".#~"

  def goodSolidityFileName( simpleName : String ) : Boolean =  simpleName.endsWith(".sol") && SolidityFileBadFirstChars.indexOf( simpleName.head ) < 0

  private [sbtethereum] def doCompileSolidity( log : sbt.Logger, compiler : Compiler.Solidity, includeSourceLocations : Seq[SourceFile.Location], solSourceDir : File, solDestDir : File )( implicit ec : ExecutionContext ) : Unit = {

    def solToJson( filename : String ) : String = filename match {
      case SolFileRegex( base ) => base + ".json"
    }

    // TODO XXX: check imported files as well!
    def changed( destinationFile : File, sourceFile : SourceFile ) : Boolean = (! destinationFile.exists) || (sourceFile.lastModified > destinationFile.lastModified() )

    def waitForFiles[T]( files : Iterable[(File,Future[T])], errorMessage : Int => String ) : Unit = {
      val labeledFailures = awaitAndGatherLabeledFailures( files )
      val failureCount = labeledFailures.size
      if ( failureCount > 0 ) {
        log.error( errorMessage( failureCount ) )
        labeledFailures.foreach { 
          case ( file, jf : jsonrpc20.Exception ) => log.error( s"File: ${file.getAbsolutePath}${SEP}${jf.message}" )
          case ( file, other                    ) => log.error( s"File: ${file.getAbsolutePath}${SEP}${other.toString}" )
        }
        throw labeledFailures.head._2
      }
    }

    solDestDir.mkdirs()
    val files = (solSourceDir ** "*.sol").get.filter( file => goodSolidityFileName( file.getName ) )

    val filePairs = files.map { file =>
       // ( rawSourceFile, sourceFile, destinationFile, debugDestinationFile ), exception if file can't load
      ( file, loadResolveSourceFile( file, includeSourceLocations ).get, new File( solDestDir, solToJson( file.getName() ) ), new File( solDestDir, file.getName() ) )
    }
    val compileFiles = filePairs.filter { case ( _, sourceFile, destFile, _ ) => changed( destFile, sourceFile ) }

    val cfl = compileFiles.length
    if ( cfl > 0 ) {
      val mbS = if ( cfl > 1 ) "s" else ""
      log.info( s"Compiling ${compileFiles.length} Solidity source${mbS} to ${solDestDir}..." )

      val compileLabeledFuts = compileFiles.map { case ( file, sourceFile, destFile, debugDestFile ) =>
        val combinedSource = {
          s"""|/*
              | * DO NOT EDIT! DO NOT EDIT! DO NOT EDIT!
              | *
              | * This is an automatically generated file. It will be overwritten.
              | *
              | * For the original source see 
              | *    '${file.getPath}'
              | */
              |
              |""".stripMargin + sourceFile.pragmaResolvedText
        }
        Files.write( debugDestFile.toPath(), combinedSource.getBytes( Codec.UTF8.charSet ) )
        log.info( s"Compiling '${file.getName()}'. (Debug source: '${debugDestFile.getPath()}')" )
        file -> compiler.compile( log, combinedSource ).map( result => ( destFile, result ) )
      }
      waitForFiles( compileLabeledFuts, count => s"compileSolidity failed. [${count} failures]" )

      // if we're here, all compilations succeeded
      val destFileResultPairs = compileLabeledFuts.map {
        case ( _, fut ) => fut.value.get.get
      }

      val writerLabeledFuts = destFileResultPairs.map {
        case ( destFile, result ) => {
          destFile -> Future {
            borrow( new OutputStreamWriter( new BufferedOutputStream( new FileOutputStream( destFile ), SolidityWriteBufferSize ), Codec.UTF8.charSet ) ){ writer =>
              writer.write( Json.stringify( Json.toJson ( result ) ) )
            }
          }
        }
      }
      waitForFiles( writerLabeledFuts, count => s"Failed to write the output of some compilations. [${count} failures]" )
    }
  }

  private [sbtethereum] def doGetBalance( log : sbt.Logger, jsonRpcUrl : String, address : EthAddress, blockNumber : jsonrpc20.Client.BlockNumber )( implicit ec : ExecutionContext ) : BigInt = {
    doWithJsonClient( log, jsonRpcUrl )( client => Await.result( client.eth.getBalance( address, blockNumber ), Duration.Inf ) )
  }

  private [sbtethereum] def doCodeForAddress( log : sbt.Logger, jsonRpcUrl : String, address : EthAddress, blockNumber : jsonrpc20.Client.BlockNumber )( implicit ec : ExecutionContext ) : immutable.Seq[Byte] = {
    doWithJsonClient( log, jsonRpcUrl )( client => Await.result( client.eth.getCode( address, blockNumber ), Duration.Inf ) )
  }

  case class EthValue( wei : BigInt, denominated : BigDecimal, denomination : Denomination )

  private [sbtethereum] def doPrintingGetBalance(
    log          : sbt.Logger,
    jsonRpcUrl   : String,
    address      : EthAddress,
    blockNumber  : jsonrpc20.Client.BlockNumber,
    denomination : Denomination
  )( implicit ec : ExecutionContext ) : EthValue = {
    import jsonrpc20.Client.BlockNumber._

    val wei = doGetBalance( log, jsonRpcUrl, address, blockNumber )( ec )
    val out = EthValue( wei, denomination.fromWei( wei ), denomination )
    val msg = blockNumber match {
      case Earliest       => s"${out.denominated} ${denomination.unitName} (at the earliest available block, address 0x${address.hex})"
      case Latest         => s"${out.denominated} ${denomination.unitName} (as of the latest incorporated block, address 0x${address.hex})"
      case Pending        => s"${out.denominated} ${denomination.unitName} (including currently pending transactions, address 0x${address.hex})"
      case Quantity( bn ) => s"${out.denominated} ${denomination.unitName} (at block #${bn}, address 0x${address.hex})"
    }
    log.info(msg)
    out
  }

  private [sbtethereum] def doEthCallEphemeral(
    log         : sbt.Logger,
    jsonRpcUrl  : String,
    from        : Option[EthAddress],
    to          : EthAddress,
    gas         : Option[BigInt],
    gasPrice    : Option[BigInt],
    value       : Option[BigInt],
    data        : Option[Seq[Byte]],
    blockNumber : jsonrpc20.Client.BlockNumber
  )( implicit ec : ExecutionContext ) : immutable.Seq[Byte] = {
    doWithJsonClient( log, jsonRpcUrl )( client => Await.result( client.eth.call( from, Some(to), gas, gasPrice, value, data, blockNumber), Duration.Inf ) )
  }

  private [sbtethereum] def doGetDefaultGasPrice( log : sbt.Logger, jsonRpcUrl : String )( implicit ec : ExecutionContext ) : BigInt = {
    doWithJsonClient( log, jsonRpcUrl )( client => Await.result( client.eth.gasPrice(), Duration.Inf ) )
  }

  private [sbtethereum] def doGetTransactionCount( log : sbt.Logger, jsonRpcUrl : String, address : EthAddress, blockNumber : jsonrpc20.Client.BlockNumber )( implicit ec : ExecutionContext ) : BigInt = {
    doWithJsonClient( log, jsonRpcUrl )( client => Await.result( client.eth.getTransactionCount( address, blockNumber ), Duration.Inf ) )
  }

  private [sbtethereum] def doEstimateGas( log : sbt.Logger, jsonRpcUrl : String, from : Option[EthAddress], to : Option[EthAddress], data : Seq[Byte], blockNumber : jsonrpc20.Client.BlockNumber )( implicit ec : ExecutionContext ) : BigInt = {
    doWithJsonClient( log, jsonRpcUrl )( client => Await.result( client.eth.estimateGas( from = from, to = to, data = Some(data) ), Duration.Inf ) )
  }

  private [sbtethereum] def rounded( bd : BigDecimal ) = bd.round( bd.mc ) // work around absence of default rounded method in scala 2.10 BigDecimal

  private [sbtethereum] def markupEstimateGas( log : sbt.Logger, jsonRpcUrl : String, from : Option[EthAddress], to : Option[EthAddress], data : Seq[Byte], blockNumber : jsonrpc20.Client.BlockNumber, markup : Double )( implicit ec : ExecutionContext ) : BigInt = {
    val rawEstimate = doEstimateGas( log, jsonRpcUrl, from, to, data, blockNumber )( ec )
    rounded(BigDecimal(rawEstimate) * BigDecimal(1 + markup)).toBigInt
  }

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

  private [sbtethereum] def doSignSendTransaction( log : sbt.Logger, jsonRpcUrl : String, signer : EthPrivateKey, unsigned : EthTransaction.Unsigned )( implicit ec : ExecutionContext ) : EthHash = {
    doWithJsonClient( log, jsonRpcUrl ){ client =>
      val signed = unsigned.sign( signer )
      val hash = Await.result( client.eth.sendSignedTransaction( signed ), Duration.Inf )
      Repository.logTransaction( signed, hash )
      hash
    }
  }

  private [sbtethereum] def awaitTransactionReceipt(
    log : sbt.Logger,
    jsonRpcUrl : String,
    transactionHash : EthHash,
    pollSeconds : Int,
    maxPollAttempts : Int
  )( implicit ec : ExecutionContext ) : Option[ClientTransactionReceipt] = {
    doWithJsonClient( log, jsonRpcUrl ){ client =>
      def doPoll( attemptNum : Int ) : Option[ClientTransactionReceipt] = {
        val mbReceipt = Await.result( client.eth.getTransactionReceipt( transactionHash ), Duration.Inf )
        ( mbReceipt, attemptNum ) match {
          case ( None, num ) if ( num < maxPollAttempts ) => {
            log.info(s"Receipt for transaction '0x${transactionHash.bytes.hex}' not yet available, will try again in ${pollSeconds} seconds. Attempt ${attemptNum + 1}/${maxPollAttempts}.")
            Thread.sleep( pollSeconds * 1000 )
            doPoll( num + 1 )
          }
          case ( None, _ ) => {
            log.warn(s"After ${maxPollAttempts} attempts (${(maxPollAttempts - 1) * pollSeconds} seconds), no receipt has yet been received for transaction '0x${transactionHash.bytes.hex}'.")
            None
          }
          case ( Some( receipt ), _ ) => {
            log.info(s"Receipt received for transaction '0x${transactionHash.bytes.hex}':\n${receipt}")
            mbReceipt
          }
        }
      }
      doPoll( 0 )
    }
  }

  private final val CantReadInteraction = "InteractionService failed to read"

  private [sbtethereum] def readConfirmCredential(  log : sbt.Logger, is : InteractionService, readPrompt : String, confirmPrompt: String = "Please retype to confirm: ", maxAttempts : Int = 3, attempt : Int = 0 ) : String = {
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

  private [sbtethereum] def readAddressAndAbi( log : sbt.Logger, is : InteractionService ) : ( EthAddress, Abi.Definition ) = {
    val address = EthAddress( is.readLine( "Contract address in hex: ", mask = false ).getOrElse( throw new Exception( CantReadInteraction ) ) )
    val abi = parseAbi( is.readLine( "Contract ABI: ", mask = false ).getOrElse( throw new Exception( CantReadInteraction ) ) )
    ( address, abi )
  }

  private [sbtethereum] def readV3Wallet( is : InteractionService ) : wallet.V3 = {
    val jsonStr = is.readLine( "V3 Wallet JSON: ", mask = false ).getOrElse( throw new Exception( CantReadInteraction ) )
    val jsv = Json.parse( jsonStr )
    wallet.V3( jsv.as[JsObject] )
  }

  private [sbtethereum] def readCredential( is : InteractionService, address : EthAddress ) : String = {
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

  // solc now generates code that includes a suffix of non-EVM-code metadata (currently a swarm hash of a metadata file)
  final object BaseCodeAndSuffix {
    private final val Regex = """(?i)^(?:0x)?(\p{XDigit}*?)(a165627a7a72305820\p{XDigit}*0029)?$""".r

    def apply( fullHex : String ) : BaseCodeAndSuffix= {
      fullHex match {
        case Regex( baseCodeHex, null )      => BaseCodeAndSuffix( baseCodeHex, "" )
        case Regex( baseCodeHex, suffixHex ) => BaseCodeAndSuffix( baseCodeHex, suffixHex )
        case _                               => throw new BadCodeFormatException( s"Unexpected code format: ${fullHex}" )
      }
    }
  }
  final case class BaseCodeAndSuffix( baseCodeHex : String, codeSuffixHex : String ) {
    lazy val baseCodeHash = EthHash.hash( baseCodeHex.decodeHex )
    lazy val fullCodeHash = EthHash.hash( (baseCodeHex + codeSuffixHex).decodeHex )
    lazy val fullCodeHex  = baseCodeHex + codeSuffixHex
  }
}


