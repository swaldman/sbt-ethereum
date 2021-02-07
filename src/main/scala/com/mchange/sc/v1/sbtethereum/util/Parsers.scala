package com.mchange.sc.v1.sbtethereum.util

import com.mchange.sc.v1.sbtethereum._
import util.Abi._
import util.Spawn._
import util.InteractiveQuery.normalizeInteractedFilePath

import sbt.State

import sbt.complete.{FixedSetExamples,Parser}
import sbt.complete.DefaultParsers._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.bitcoin.BtcAddress
import com.mchange.sc.v1.consuela.ethereum.{jsonrpc,specification,EthAddress,EthChainId,EthHash}
import specification.Denominations
import jsonrpc.Client.BlockNumber

import com.mchange.sc.v1.consuela.slip.Slip44

import com.mchange.sc.v2.ens
import com.mchange.sc.v2.ens.NoResolverSetException

import com.mchange.sc.v3.failable._

import com.mchange.sc.v1.log.MLevel._

import scala.collection._

import scala.util.matching.Regex

import scala.util.control.NonFatal

import play.api.libs.json._

import java.time.Instant
import java.time.format.DateTimeFormatter.{ISO_INSTANT, ISO_OFFSET_DATE_TIME}

// XXX: This whole mess needs to be reorganized, using readable for-comprehension parsers,
//      and strict conventions about spaces and tokens

/*
 * A convention: 
 *    genXXX parsers all begin with a leading space.
 *    Others do not -- Any leading space must be specified at point-of-use
 * 
 */ 
private [sbtethereum]
object Parsers {
  private implicit lazy val logger = mlogger( this )

  private def transformOrFail[T]( raw : Parser[String], xform : String => T ) : Parser[T] = raw.flatMap( s => Failable( success( xform(s) ) ).recover( f => failure( f.message ) ).assert )

  private val ZWSP = "\u200B" // HACK: we add zero-width space to parser examples lists where we don't want autocomplete to apply to unique examples

  // use rawAddressParserMaybeWithChainId( None ) instead of RawAddressParser
  // private val RawAddressParser = ( literal("0x").? ~> Parser.repeat( HexDigit, 40, 40 ) ).map( chars => EthAddress.apply( chars.mkString ) )

  private val RawAddressParserAsString = ( literal("0x").? ~> Parser.repeat( HexDigit, 40, 40 ) ).map( _.mkString )

  private def rawAddressParserMaybeWithChainId( mbChainId : Option[EthChainId] ) = {
    transformOrFail( RawAddressParserAsString, addrStr => EthAddress.parse( addrStr, mbChainId, true ) )
  }

  //private val RawBtcAddressParser = NotSpace.flatMap( chars => Failable( success( BtcAddress( chars.mkString ) ) ).recover( f => failure( f.message ) ).assert )

  private val RawBtcAddressParser = transformOrFail( NotSpace, BtcAddress(_) )

  private [sbtethereum] val RawAddressAliasParser = ID

  private val HexByteAsCharSeq = Parser.repeat( HexDigit, 2, 2 )

  private val HexByteAsString = HexByteAsCharSeq.map( _.mkString )

  private val RawBytesAsHexStringParser = ((literal("0x") | HexByteAsString ) ~ HexByteAsString.*).map { case (a, b) => a ++ b.mkString }

  // this is a bad idea because it leaves tab completion "trapped", hitting tab does nothing, doesn't advance to next example
  // private val MaybeSpace = SpaceClass.*.examples("") // like OptSpace, but no default " " example

  def rawFixedLengthByteStringAsStringParser( len : Int ) = {
    val charLen = len * 2
    ( literal("0x").? ~> Parser.repeat( HexDigit, charLen, charLen ) ).map( chars => chars.mkString )
  }

  private def createSimpleAddressParser( tabHelp : String, mbChainId : Option[EthChainId] ) : Parser[EthAddress] = token( rawAddressParserMaybeWithChainId( mbChainId ).examples( tabHelp, ZWSP ) )

  private [sbtethereum]
  def createSimpleAddressParser( tabHelp : String ) : Parser[EthAddress] = createSimpleAddressParser( tabHelp, None )

  private def rawAddressAliasParser( aliases : SortedMap[String,EthAddress] ) : Parser[String] = {
    aliases.keys.foldLeft( failure("not a known alias") : Parser[String] )( ( nascent, next ) => nascent | literal( next ) )
  }

  private def rawAliasedAddressParser( aliases : SortedMap[String,EthAddress] ) : Parser[EthAddress] = rawAddressAliasParser( aliases ).map( aliases )

  private def rawAliasWithAddressParser( aliases : SortedMap[String,EthAddress] ) : Parser[(String,EthAddress)] = rawAddressAliasParser( aliases ).map( alias => (alias, aliases(alias)) )

  // this is perhaps (much) too permissive,
  // causing a lot of lookups against potential valid names and very long pauses on tab
  private val CouldBeNonTldEns : String => Boolean = _.indexOf('.') > 0 // ENS paths can't begin with dot, so greater than without equality

  private [sbtethereum]
  def createAddressParser( tabHelp : String, mbRpi : Option[RichParserInfo] ) : Parser[EthAddress] = {
    mbRpi match {
      case Some( rpi ) => {
        val mbChainId = if ( rpi.chainId >= 0 ) Some( EthChainId( rpi.chainId ) ) else None
        val aliases = rpi.addressAliases
        val tld = rpi.exampleNameServiceTld
        val ensParser = ensPathToAddressParserSelective( pathPredicate = CouldBeNonTldEns, pathPredicateFailureDesc = "Cannot be, but required to be, non-TLD ENS name" )( rpi ).examples( s"<ens-name>.${tld}" )

        // val allExamples = Vector( tabHelp, s"<ens-name>.${tld}" ) ++ aliases.keySet
        // token(OptSpace) ~> token( RawAddressParser | rawAliasedAddressParser( aliases ) | ensParser ).examples( allExamples : _* )

        // extra example ZWSP to prevent tab completion on just "<" if there are no aliases and all the tab-help looks like <whatever>
        val anyAddressParser = rawAddressParserMaybeWithChainId( mbChainId ).examples( tabHelp, ZWSP ) | rawAliasedAddressParser( aliases ).examples( aliases.keySet, false ) | ensParser
        token( anyAddressParser )!!!(s"Could not parse address as hex string (if mixed case, the checksum may have failed!), as a known address alias, or as an ENS name under top-level domain '${tld}'.")
      }
      case None => {
        createSimpleAddressParser( tabHelp, None )
      }
    }
  }

  private [sbtethereum] val RawIntParser = (Digit.+).map( chars => chars.mkString.toInt )

  private [sbtethereum] val RawLongParser = (Digit.+).map( chars => chars.mkString.toLong )

  private val HexAsUnsignedBigIntParser = literal( "0x" ) ~> HexByteAsString.*.map( hexBytes => BigInt(hexBytes.mkString,16) )

  private [sbtethereum] val RawUnsignedBigIntParser = (Digit.+).map( chars => BigInt( chars.mkString ) ) | HexAsUnsignedBigIntParser

  private [sbtethereum] def unsignedBigIntParser( tabHelp : String ) = token(RawUnsignedBigIntParser, tabHelp)

  private [sbtethereum] val RawAmountParser = ((Digit|literal('.')).+).map( chars => BigDecimal( chars.mkString ) )

  private [sbtethereum] val RawByteParser = HexByteAsCharSeq.map( _.mkString.decodeHexAsSeq.head )

  private [sbtethereum] val RawBytesParser = RawBytesAsHexStringParser.map( _.mkString.decodeHexAsSeq )

  private [sbtethereum] val RawUrlParser = NotSpace

  private [sbtethereum] val RawEtherscanApiKeyParser = NotSpace

  private [sbtethereum] val RawMLogDetailPrefixParser = StringBasic

  private [sbtethereum] val RawIsoInstantEpochMillisParser     = transformOrFail( NotSpace, s => Instant.from( ISO_INSTANT.parse(s) ).toEpochMilli() )

  private [sbtethereum] val RawIsoOffsetLocalEpochMillisParser = transformOrFail( NotSpace, s => Instant.from( ISO_OFFSET_DATE_TIME.parse(s) ).toEpochMilli() )

  private [sbtethereum] val RawPermissiveIsoEpochMillisParser  = RawIsoInstantEpochMillisParser | RawIsoOffsetLocalEpochMillisParser

  private [sbtethereum] val RawBlockNumberParser = {
    literal("earliest").map( _ => BlockNumber.Earliest) | literal("latest").map( _ => BlockNumber.Latest ) | literal("pending").map( _ => BlockNumber.Pending ) | RawUnsignedBigIntParser.map( value => BlockNumber.Quantity( value ) )
  }

  private [sbtethereum] def intParser( tabHelp : String ) = token( RawIntParser, tabHelp )

  private [sbtethereum] def etherscanApiKeyParser( tabHelp : String ) = token( RawEtherscanApiKeyParser, tabHelp )

  //private [sbtethereum] def amountParser( tabHelp : String ) = token(OptSpace ~> (Digit|literal('.')).+, tabHelp).map( chars => BigDecimal( chars.mkString ) )
  private [sbtethereum] def amountParser( tabHelp : String ) = token(RawAmountParser, tabHelp)

  private [sbtethereum] def bytesParser( tabHelp : String ) = token(RawBytesParser, tabHelp)

  private [sbtethereum] def urlParser( tabHelp : String ) = token(RawUrlParser, tabHelp)

  private [sbtethereum] val UnitParser = {
    val ( w, gw, s, f, e ) = ( "wei", "gwei", "szabo", "finney", "ether" );
    token( literal(w) | literal(gw) | literal(s) | literal(f) | literal(e) )
  }

  private [sbtethereum] def toValueInWei( amount : BigDecimal, unit : String ) : BigInt = rounded(amount * BigDecimal(Denominations.Multiplier.BigInt( unit )))

  private [sbtethereum] def valueInWeiParser( tabHelp : String ) : Parser[BigInt] = {
    (amountParser( tabHelp ) ~ (Space ~> UnitParser)).map { case ( amount, unit ) => toValueInWei( amount, unit ) }
  }

  private [sbtethereum] val SolcJVersionParser : Parser[Option[String]] = {
    val mandatory = compile.SolcJInstaller.SupportedVersions.foldLeft( failure("No supported versions") : Parser[String] )( ( nascent, next ) => nascent | literal(next) )
    token(mandatory.?)
  }

  private [sbtethereum]
  final object DurationParsers {
    import java.time.{Duration => JDuration}
    import java.time.temporal.ChronoUnit

    val ChronoUnits = {
      def binding( unit : ChronoUnit ) : Tuple2[String,ChronoUnit] = unit.toString.toLowerCase -> unit
      immutable.Map[String,ChronoUnit](
        binding(ChronoUnit.SECONDS),
        binding(ChronoUnit.MINUTES),
        binding(ChronoUnit.HOURS),
        binding(ChronoUnit.DAYS),
        binding(ChronoUnit.WEEKS),
        binding(ChronoUnit.MONTHS),
        binding(ChronoUnit.YEARS)
      )
    }

    val AllUnits = immutable.SortedSet.empty[String] ++ ChronoUnits.keySet ++ ChronoUnits.keySet.map( _.init.mkString )

    def findUnit( unitStr : String ) : Option[ChronoUnit] = {
      val key = unitStr.toLowerCase
      ChronoUnits.get( key ) orElse ChronoUnits.get( key + 's' )
    }

    final case class SecondsViaUnit( seconds : Long, unitProvided : ChronoUnit ) {
      def numUnits = seconds.toDouble / unitProvided.getDuration.getSeconds
      def formattedNumUnits = s"${numUnits} ${unitProvided.toString.toLowerCase}"
    }

    val JDurationParser = {
      for {
        amount  <- RawLongParser
        _       <- Space
        unitStr <- ID.examples( AllUnits )
        unit    <- findUnit( unitStr ).fold( failure( s"Unknown unit: ${unitStr}" ) : Parser[ChronoUnit] )( u => success( u ) )
      }
      yield {
        // XXX: a bit icky
        // See https://stackoverflow.com/questions/26454129/getting-duration-using-the-new-datetime-api

        //( JDuration.of( amount, unit ), unit )
        ( unit.getDuration.multipliedBy(amount), unit )
      }
    }

    val DurationInSecondsParser = JDurationParser.map { case ( jduration, unit ) => SecondsViaUnit( jduration.getSeconds, unit ) }
  }

  private [sbtethereum] val EnsPathClass = charClass( c => isIDChar(c) || c == '.', "valid for ENS paths" )

  private [sbtethereum] val RawEnsPath = {
    for {
      firstChar <- IDChar
      rest <- EnsPathClass.*.map( _.mkString )
    }
    yield {
      "" + firstChar + rest
    }
  }

  private [sbtethereum] def ensPathParser( exampleTld : String, desc : String = "ens-name" ) : Parser[ens.ParsedPath] = {
    token( RawEnsPath ).examples( s"<${desc}>.${exampleTld}", ZWSP ).map { rawPath =>
      ens.ParsedPath( rawPath )
    }
  }

  private [sbtethereum] def ensEnsureForward( epp : ens.ParsedPath ) : Parser[ens.ParsedPath.Forward] = {
    epp match {
      case ok : ens.ParsedPath.Forward => success( ok )
      case _                           => failure( s"${epp} is not a ens.ParsedPath.Forward as required." )
    }
  }
  private [sbtethereum] def ensEnsureTld( epp : ens.ParsedPath ) : Parser[ens.ParsedPath.Tld] = {
    epp match {
      case ok : ens.ParsedPath.Tld => success( ok )
      case _                       => failure( s"${epp} is not a ens.ParsedPath.Tld as required." )
    }
  }
  private [sbtethereum] def ensEnsureBaseNameTld( epp : ens.ParsedPath ) : Parser[ens.ParsedPath.BaseNameTld] = {
    epp match {
      case ok : ens.ParsedPath.BaseNameTld => success( ok )
      case _                               => failure( s"${epp} is not a ens.ParsedPath.BaseNameTld as required." )
    }
  }
  private [sbtethereum] def ensEnsureSubnode( epp : ens.ParsedPath ) : Parser[ens.ParsedPath.Subnode] = {
    epp match {
      case ok : ens.ParsedPath.Subnode => success( ok )
      case _                           => failure( s"${epp} is not a ens.ParsedPath.SubNode as required." )
    }
  }
  private [sbtethereum] def ensEnsureHasBaseName( epp : ens.ParsedPath ) : Parser[ens.ParsedPath.HasBaseName] = {
    epp match {
      case ok : ens.ParsedPath.HasBaseName => success( ok )
      case _                               => failure( s"${epp} is not a ens.ParsedPath.HasBaseName as required." )
    }
  }
  private [sbtethereum] def ensEnsureReverse( epp : ens.ParsedPath ) : Parser[ens.ParsedPath.Reverse] = {
    epp match {
      case ok : ens.ParsedPath.Reverse => success( ok )
      case _                           => failure( s"${epp} is not a ens.ParsedPath.Reverse as required." )
    }
  }

  private [sbtethereum] def ensPathToAddressParser( rpi : RichParserInfo ) : Parser[EthAddress] = {
    ensPathParser( rpi.exampleNameServiceTld ).flatMap { epp =>
      val faddress = SbtEthereumPlugin.ensAddressCache.lookup( rpi, epp.fullName )
      if ( faddress.isSucceeded ) success( faddress.get ) else failure( faddress.assertFailed.toString )
    }
  }

  private def ensPathToAddressParserSelective(
    pathPredicate : String => Boolean = _ => true,
    pathPredicateFailureDesc : String = "No further description available",
    parsedPathPredicate : ens.ParsedPath => Boolean = _ => true,
    parsedPathPredicateFailureDesc : String = "No further description available"
  )( rpi : RichParserInfo ) : Parser[EthAddress] = {
    for {
      rawPath <- RawEnsPath
      _       <- if (pathPredicate( rawPath )) success( rawPath ) else failure(s"ENS address ruled out by simple path predicate -- ${pathPredicateFailureDesc}.")
      epp      = ens.ParsedPath( rawPath )
      _       <- if (parsedPathPredicate( epp )) success( epp ) else failure(s"ENS address ruled out by parsed path predicate -- ${parsedPathPredicateFailureDesc}.")
      faddress = SbtEthereumPlugin.ensAddressCache.lookup( rpi, epp.fullName )
      address <- if (faddress.isSucceeded) success( faddress.get ) else failure("Failed to find an address for putative ENS path.")
    }
    yield {
      address
    }
  }

  private [sbtethereum] def genEnsPathParser(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) : Parser[ens.ParsedPath] = {
    mbRpi.map { rpi =>
      token(Space) ~> ensPathParser( rpi.exampleNameServiceTld )
    }.getOrElse( failure( "Failed to retrieve RichParserInfo." ) )
  }

  private [sbtethereum] def genEnsSubnodeParser(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) : Parser[ens.ParsedPath.Subnode] = {
    for {
      epp <- genEnsPathParser( state, mbRpi )
      sn <- ensEnsureSubnode( epp )
    }
    yield {
      sn
    }
  }

  private [sbtethereum] def genEnsSubnodeOwnerSetParser(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) : Parser[Tuple2[ens.ParsedPath.Subnode,EthAddress]] = {
    for {
      sn <- genEnsSubnodeParser( state, mbRpi )
      _ <- Space
      owner <- createAddressParser( "<subnode-owner-hex>", mbRpi )
    }
    yield {
      ( sn, owner )
    }
  }

  // XXX: Can't get any useful tab-completion working on this parser
  private [sbtethereum] def genEnsPathMbAddressMbSecretParser(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) : Parser[Tuple3[ens.ParsedPath,Option[EthAddress],Option[immutable.Seq[Byte]]]] = {
    for {
      epp       <- genEnsPathParser( state, mbRpi )
      mbAddress <- genAddressParser("[optional-registrant-address]")( state, mbRpi ).?
      mbSecret  <- (Space ~> rawFixedLengthByteStringAsStringParser(32).map( _.decodeHexAsSeq )).?
    }
    yield {
      ( epp, mbAddress, mbSecret )
    }
  }

  private val StorageSlotParser : Parser[BigInt] = {
    val NamedSlotParser = {
      StorageSlots.foldLeft( failure("nonnumeric, unknown named storage slot") : Parser[BigInt] ) { (accum, next) =>
        val (nextKey, nextVal) = next
        accum | literal(nextKey).map( _ => nextVal )
      }
    }
    unsignedBigIntParser("<storage-slot>") | NamedSlotParser
  }

  private [sbtethereum] def genContractStorageLookupParser(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) : Parser[Tuple3[EthAddress,BigInt,Option[BlockNumber]]] = {
    for {
      contractAddress <- genAddressParser("<contract-address>")( state, mbRpi )
      storageSlot     <- Space ~> token(StorageSlotParser)
      blockNumber     <- (Space ~> token(RawBlockNumberParser)).?
    }
    yield {
      ( contractAddress, storageSlot, blockNumber )
    }
  }

  private [sbtethereum] def ethHashParser( exampleStr : String ) : Parser[EthHash] = token(literal("0x").? ~> Parser.repeat( HexDigit, 64, 64 ), exampleStr).map( chars => EthHash.withBytes( chars.mkString.decodeHex ) )

  private [sbtethereum] final object FunctionFilter {
    val RestrictToConstants    : jsonrpc.Abi.Function => Boolean = fcn => fcn.constant
    val NoFilter               : jsonrpc.Abi.Function => Boolean = fcn => true
    val RestrictToNonconstants : jsonrpc.Abi.Function => Boolean = fcn => !fcn.constant
  }

  private val DummyFunction = jsonrpc.Abi.Function("<dummy-function-you-should-never-see>",immutable.Seq.empty,immutable.Seq.empty,true,false,"pure")

  private [sbtethereum] def functionParser( abi : jsonrpc.Abi, functionFilter : jsonrpc.Abi.Function => Boolean ) : Parser[jsonrpc.Abi.Function] = {
    val namesToFunctions           = abi.functions.groupBy( _.name )

    val overloadedNamesToFunctions = namesToFunctions.filter( _._2.length > 1 )
    val nonoverloadedNamesToFunctions : Map[String,jsonrpc.Abi.Function] = (namesToFunctions -- overloadedNamesToFunctions.keySet).map( tup => ( tup._1, tup._2.head ) )

    def createQualifiedNameForOverload( function : jsonrpc.Abi.Function ) : String = function.name + "(" + function.inputs.map( _.`type` ).mkString(",") + ")"

    def createOverloadBinding( function : jsonrpc.Abi.Function ) : ( String, jsonrpc.Abi.Function ) = ( createQualifiedNameForOverload( function ), function )

    val qualifiedOverloadedNamesToFunctions : Map[String, jsonrpc.Abi.Function] = overloadedNamesToFunctions.values.flatMap( _.map( createOverloadBinding ) ).toMap

    val processedNamesToFunctions = {
      val raw = (qualifiedOverloadedNamesToFunctions ++ nonoverloadedNamesToFunctions).toMap
      raw.filter( tup => functionFilter(tup._2 ) )
    }

    // use ZWSP so if all function names start with one letter, or there's just one, we don't begin completion
    val baseParser = processedNamesToFunctions.keySet.foldLeft( literal(ZWSP) )( ( nascent, next ) => nascent | literal( next ) ) 

    // but ZWSP must "succeed" (even though it should be pretty difficult to type). so we map it to a dummy function
    baseParser.flatMap( name => processedNamesToFunctions.get( name ).fold( success( DummyFunction ) : Parser[jsonrpc.Abi.Function] )( success ) )
  }

  // modified from DQuote parser in https://github.com/sbt/sbt/blob/develop/internal/util-complete/src/main/scala/sbt/internal/util/complete/Parsers.scala
  val SQuoteChar  = '\''
  val SQuoteClass = charClass(_ == SQuoteChar, "single-quote character")
  val NotSQuoteClass = charClass({ c: Char => (c != SQuoteChar) }, "non-single-quote character")
  val NotAnyQuoteSpaceClass = charClass({ c: Char => (c != SQuoteChar && c!= DQuoteChar && !c.isWhitespace) }, "not any-quote-initiating not whitespace character")

  val SingleQuoteStringVerbatim = SQuoteChar ~> NotSQuoteClass.*.map( _.mkString ) <~ SQuoteChar

  val NotAnyQuoted = (NotAnyQuoteSpaceClass ~ OptNotSpace) map { case (c, s) => c.toString + s }

  private val RawStringInputParser : Parser[String] = {
    ( StringVerbatim | StringEscapable | SingleQuoteStringVerbatim | NotAnyQuoted ).map( str => s""""${str}"""")
  }

  private val BytesN_Regex = """bytes(\d+)""".r

  private def inputParser( input : jsonrpc.Abi.Parameter, mbRpi : Option[RichParserInfo] ) : Parser[String] = {
    val displayName = if ( input.name.length == 0 ) "mapping key" else input.name
    val sample = s"<${displayName}, of type ${input.`type`}>"
    val defaultExamples = FixedSetExamples( immutable.Set( sample, ZWSP ) )
    input.`type` match {
      case "address" if mbRpi.nonEmpty => createAddressParser( sample, mbRpi ).map( _.hex.toLowerCase )
      case BytesN_Regex( len )         => token( rawFixedLengthByteStringAsStringParser( len.toInt ) ).examples( defaultExamples )
      case "bytes"                     => token( RawBytesAsHexStringParser ).examples( defaultExamples )
      case "string"                    => token( RawStringInputParser ).examples( defaultExamples )
      case _                           => token( (StringEscapable.map( str => s""""${str}"""") | NotQuoted) ).examples( defaultExamples ) 
    }
  }

  private def inputsParser( inputs : immutable.Seq[jsonrpc.Abi.Parameter], mbRpi : Option[RichParserInfo] ) : Parser[immutable.Seq[String]] = {
    val parserMaker : jsonrpc.Abi.Parameter => Parser[String] = param => inputParser( param, mbRpi )
    inputs.map( parserMaker ).foldLeft( success( immutable.Seq.empty[String] ) )( (nascent, next) => nascent.flatMap( partial => token(Space) ~> next.map( str => partial :+ str ) ) )
  }

  private def functionAndInputsParser(
    abi : jsonrpc.Abi,
    functionFilter : jsonrpc.Abi.Function => Boolean,
    mbRpi : Option[RichParserInfo]
  ) : Parser[(jsonrpc.Abi.Function, immutable.Seq[String])] = {
    token( functionParser( abi, functionFilter ) ).flatMap( function => inputsParser( function.inputs, mbRpi ).map( seq => ( function, seq ) ) )
  }

  private [sbtethereum] val DbQueryParser : Parser[String] = (any.*).map( _.mkString.trim )

  private [sbtethereum] val AnyFilePathParser : Parser[String] = any.*.string.map( raw => normalizeInteractedFilePath( raw.trim ) )

  // XXX: We add case-insensitive flags only to "naive" regexs when defaultToCaseInsensitive is true.
  //      The intent is that users who explicitly set flags should have them unmolested. But we don't
  //      actually test for setting flags. We test for the presence of "(?", which would include flag-setting,
  //      but also non-capturing groups and other constructs.
  //
  //      We should clean this up, and carefully check for the setting of flags to decide whether or not 
  //      it is safe for us to set our own flags.
  
  private [sbtethereum] def regexParser( defaultToCaseInsensitive : Boolean ) : Parser[Option[Regex]] = {
    def normalizeStr( s : String ) : Option[Regex] = {
      val trimmed = s.trim
      val out = {
        if ( defaultToCaseInsensitive && trimmed.indexOf( "(?" ) < 0 ) "(?i)" + trimmed else trimmed
      }
      if ( out.isEmpty ) None else Some( out.r )
    }
    def normalize( ss : Seq[Char] ) : Option[Regex] = normalizeStr( ss.mkString )

    token( (any.*).map( normalize ) ).examples( "[<regular expression or simple substring to filter>]" )
  }

  // delayed parsers
  private def constructorFromAbi( abi : jsonrpc.Abi ) : jsonrpc.Abi.Constructor = {
    abi.constructors.length match {
      case 0 => jsonrpc.Abi.Constructor.noArgNoEffect
      case 1 => abi.constructors.head
      case _ => throw new Exception( s"""Constructor overloading not supprted (nor legal in solidity). Found multiple constructors: ${abi.constructors.mkString(", ")}""" )
    }
  }

  // note that this function is used directly in createAutoQuintets() in SbtEthereumPlugin!
  private [sbtethereum] def ctorArgsMaybeValueInWeiParser( seed : MaybeSpawnable.Seed ) : Parser[SpawnInstruction.Full] = {
    val ctor = constructorFromAbi( seed.abi )
    val simpleInputsParser = inputsParser( ctor.inputs, None )
    val withMaybeValueParser = simpleInputsParser.flatMap { seq =>
      if ( ctor.payable ) {
        (Space ~> valueInWeiParser("[ETH to pay, optional]")).?.flatMap( mbv => success(  ( seq, mbv ) ) ) // useless flatmap rather than map
      } else {
        success( ( seq, None ) )
      }
    }

    withMaybeValueParser.map { case ( seq, mbv ) =>
      SpawnInstruction.Full( seed.contractName, seq, mbv.getOrElse(0), seed )
    }
  }

  private [sbtethereum] def genContractSpawnParser(
    state   : State,
    mbSeeds : Option[immutable.Map[String,MaybeSpawnable.Seed]]
  ) : Parser[SpawnInstruction] = {
    val seeds = mbSeeds.getOrElse( immutable.Map.empty )
    val contractNames = immutable.TreeSet( seeds.keys.toSeq : _* )( Ordering.comparatorToOrdering( String.CASE_INSENSITIVE_ORDER ) )
    val exSet = if ( contractNames.isEmpty ) immutable.Set("<contract-name>", ZWSP) else contractNames // non-breaking space to prevent autocompletion to dummy example
    val argsParser = token( NotSpace examples exSet ).flatMap { name =>
      seeds.get( name ) match {
        case None         => success( SpawnInstruction.UncompiledName( name ) )
        case Some( seed ) => ctorArgsMaybeValueInWeiParser( seed )
      }
    }
    val autoParser = success( SpawnInstruction.Auto )
    token(OptSpace) ~> ( argsParser | autoParser ) // OptSpace here, because an empty-string should work to cause auto-deploy contracts to deploy
  }

  private [sbtethereum] def genAddressAliasParser(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) = {
    token(Space) ~> mbRpi.map( rpi => token( rawAddressAliasParser( rpi.addressAliases ).examples( rpi.addressAliases.keySet, false ) ) ).getOrElse( failure( "Failed to retrieve RichParserInfo." ) )
  }

  private [sbtethereum] def genAliasWithAddressParser(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) = {
    token(Space) ~> mbRpi.map( rpi => token( rawAliasWithAddressParser( rpi.addressAliases ).examples( rpi.addressAliases.keySet, false ) ) ).getOrElse( failure( "Failed to retrieve RichParserInfo." ) )
  }

  private [sbtethereum] def genPermissiveAddressAliasOrAddressAsStringParser(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) : Parser[String] = {
    token(Space) ~> (
      mbRpi.map { rpi =>
        val mbChainId = if ( rpi.chainId >= 0 ) Some( EthChainId( rpi.chainId ) ) else None
        token( ( rawAddressParserMaybeWithChainId( mbChainId ).map( _.hex.toLowerCase ) | rawAddressAliasParser( rpi.addressAliases ) ) | ID ).examples( rpi.addressAliases.keySet + "<eth-address-hex>", false )
      }.getOrElse( failure( "Failed to retrieve RichParserInfo." ) )
    )
  }

  private [sbtethereum] def genEnsPathOwnerAddressParser( state : State, mbRpi : Option[RichParserInfo] ) : Parser[(ens.ParsedPath,EthAddress)] = {
    _genEnsPathXxxAddressParser("<owner-address-hex>")( state, mbRpi )
  }

  private [sbtethereum] def genEnsPathAddressParser( state : State, mbRpi : Option[RichParserInfo] ) : Parser[(ens.ParsedPath,EthAddress)] = {
    _genEnsPathXxxAddressParser("<address-hex>")( state, mbRpi )
  }

  private [sbtethereum] def genEnsPathResolverAddressParser( state : State, mbRpi : Option[RichParserInfo] ) : Parser[(ens.ParsedPath,EthAddress)] = {
    _genEnsPathXxxAddressParser("<resolver-address-hex>")( state, mbRpi )
  }

  private [sbtethereum] def genEnsPathResolverMaybeAddressParser( state : State, mbRpi : Option[RichParserInfo] ) : Parser[(ens.ParsedPath,Option[EthAddress])] = {
    _genEnsPathXxxMaybeAddressParser("[optional-resolver-address-hex]")( state, mbRpi )
  }

  private [sbtethereum] def genEnsPathTransfereeAddressParser( state : State, mbRpi : Option[RichParserInfo] ) : Parser[(ens.ParsedPath,EthAddress)] = {
    _genEnsPathXxxAddressParser("<transferee-address-hex>")( state, mbRpi )
  }

  private def _genEnsPathXxxAddressParser( example : String )( state : State, mbRpi : Option[RichParserInfo] ) : Parser[(ens.ParsedPath,EthAddress)] = {
    mbRpi.map { rpi =>
      for {
        _ <- Space
        ensPath <- ensPathParser( rpi.exampleNameServiceTld )
        _ <- Space.examples(" ")
        address <- createAddressParser( example, mbRpi )
      }
      yield {
        Tuple2( ensPath, address )
      }
    } getOrElse {
      failure( "Failed to retrieve RichParserInfo." )
    }
  }

  private def _genEnsPathXxxMaybeAddressParser( example : String )( state : State, mbRpi : Option[RichParserInfo] ) : Parser[(ens.ParsedPath,Option[EthAddress])] = {
    mbRpi.map { rpi =>
      for {
        _ <- Space
        ensPath <- ensPathParser( rpi.exampleNameServiceTld )
        mbAddress <- (Space ~> createAddressParser( example, mbRpi )).?
      }
      yield {
        Tuple2( ensPath, mbAddress )
      }
    } getOrElse {
      failure( "Failed to retrieve RichParserInfo." )
    }
  }

  private val MultichainSupportedSymbols = "ETH" :: "BTC" :: Nil

  private val Slip44CoinParser = {
    val special = {
      for {
        symbol <- (MultichainSupportedSymbols ++ MultichainSupportedSymbols.map( _.toLowerCase )).map( literal ).reduceLeft( _ | _ )
      }
      yield {
        symbol.toLowerCase match {
          // see https://github.com/satoshilabs/slips/blob/master/slip-0044.md
          case "eth" => ( Some("ETH"), Slip44.Index.Ethereum )
          case "btc" => ( Some("BTC"), Slip44.Index.Bitcoin )
          case _     => sys.error( s"Cannot parse unexpected coin symbol '${symbol}'" )
        }
      }
    }
    special | RawIntParser.map( i => ( None, i ) )
  }

  private val TokenedBinaryAddressFormatParser = token(literal("binary-format:") ~> RawBytesParser.map( bytes => ( None, bytes ) )).examples( "binary-format:<hex>", "bc1<bech32-data>", "<base58-data>" )

  private def multichainAddressParserFor( slip44Index : Int, rpi : RichParserInfo ) : Parser[Tuple2[Option[AnyRef],immutable.Seq[Byte]]] = {
    slip44Index match {
      case Slip44.Index.Ethereum => createAddressParser( "<eth-address>", Some(rpi) ).map( addr => ( Some(addr), addr.bytes.widen ) ) | TokenedBinaryAddressFormatParser
      case Slip44.Index.Bitcoin  =>  RawBtcAddressParser.examples("<btc-address>").map( addr => ( Some(addr), addr.toScriptPubKey ) ) | TokenedBinaryAddressFormatParser
      case _ =>  TokenedBinaryAddressFormatParser
    }
  }

  private [sbtethereum]
  def genMultichainEnsPathParser( state : State, mbRpi : Option[RichParserInfo] ) : Parser[(ens.ParsedPath,Option[String],Int)] = {
    mbRpi.map { rpi =>

      // grr... Parser doesn't support pattern matches in for
      for {
        _ <- token(Space)
        coinTup  <- token( Slip44CoinParser ).examples( MultichainSupportedSymbols : _*)
        mbCoinSymbol = coinTup._1
        coinId       = coinTup._2
        _ <- token(Space)
        ensPath <- ensPathParser( rpi.exampleNameServiceTld )
      }
      yield {
        Tuple3( ensPath, mbCoinSymbol, coinId )
      }
    } getOrElse {
      failure( "Failed to retrieve RichParserInfo." )
    }
  }

  private [sbtethereum]
  def genMultichainEnsPathAddressParser( state : State, mbRpi : Option[RichParserInfo] ) : Parser[(ens.ParsedPath,Option[String],Int,Option[AnyRef],immutable.Seq[Byte])] = {
    mbRpi.map { rpi =>

      // grr... Parser doesn't support pattern matches in for
      for {
        mainTup <- genMultichainEnsPathParser( state, mbRpi )
        ensPath      = mainTup._1
        mbCoinSymbol = mainTup._2
        coinId       = mainTup._3
        _ <- token(Space)
        addressTup <- multichainAddressParserFor( coinId, rpi )
      }
      yield {
        val ( mbAddress, addressBinary ) = addressTup
        Tuple5( ensPath, mbCoinSymbol, coinId, mbAddress, addressBinary )
      }
    } getOrElse {
      failure( "Failed to retrieve RichParserInfo." )
    }
  }
  

  private [sbtethereum] def genAddressParser(tabHelp : String)( state : State, mbRpi : Option[RichParserInfo] ) : Parser[EthAddress] = {
    Space ~> createAddressParser( tabHelp, mbRpi )
  }

  private [sbtethereum] def genGenericAddressParser( state : State, mbRpi : Option[RichParserInfo] ) : Parser[EthAddress] = {
    Space ~> createAddressParser( "<address-hex>", mbRpi )
  }

  private [sbtethereum] def genCompleteErc20TokenContractAddressParser( state : State, mbRpi : Option[RichParserInfo] ) : Parser[EthAddress] = {
    Space ~> createAddressParser( "<erc20-token-address-hex>", mbRpi )
  }

  private [sbtethereum] def genCompleteErc20TokenTransferParser( state : State, mbRpi : Option[RichParserInfo] ) : Parser[Tuple3[EthAddress, EthAddress, String]] = {
    (Space ~> createAddressParser( "<erc20-token-contract-address>", mbRpi )).flatMap { contractAddress =>
      (Space ~> createAddressParser( "<transfer-to-address>", mbRpi )).flatMap { toAddress =>
        (Space ~> amountParser( "<number-of-tokens>" ).map( _.toString )).map { numTokensStr =>
          ( contractAddress, toAddress, numTokensStr )
        }
      }
    }
  }

  private [sbtethereum] def genCompleteErc20TokenApproveParser( state : State, mbRpi : Option[RichParserInfo] ) : Parser[Tuple3[EthAddress, EthAddress, String]] = {
    (Space ~> createAddressParser( "<erc20-token-contract-address>", mbRpi )).flatMap { contractAddress =>
      (Space ~> createAddressParser( "<approved-address>", mbRpi )).flatMap { toAddress =>
        (Space ~> amountParser( "<number-of-tokens>" ).map( _.toString )).map { numTokensStr =>
          ( contractAddress, toAddress, numTokensStr )
        }
      }
    }
  }

  private [sbtethereum] def genCompleteErc20TokenAllowanceParser( state : State, mbRpi : Option[RichParserInfo] ) : Parser[Tuple3[EthAddress, EthAddress, EthAddress]] = {
    (Space ~> createAddressParser( "<erc20-token-contract-address>", mbRpi )).flatMap { contractAddress =>
      (Space ~> createAddressParser( "<token-owner-address>", mbRpi )).flatMap { ownerAddress =>
        (Space ~> createAddressParser( "<allowed-address>", mbRpi )).map { allowedAddress =>
          ( contractAddress, ownerAddress, allowedAddress )
        }
      }
    }
  }

  private [sbtethereum] def genCompleteErc20TokenBalanceParser( state : State, mbRpi : Option[RichParserInfo] ) : Parser[Tuple2[EthAddress, Option[EthAddress]]] = {
    (Space ~> createAddressParser( "<erc20-token-contract-address>", mbRpi )).flatMap { contractAddress =>
      (Space ~> createAddressParser( "[optional-tokenholder-address]", mbRpi )).?.map { mbTokenHolderAddress =>
        ( contractAddress, mbTokenHolderAddress )
      }
    }
  }

  private [sbtethereum] def genCompleteErc20TokenConvertTokensToAtomsParser( state : State, mbRpi : Option[RichParserInfo] ) : Parser[Tuple2[EthAddress, BigDecimal]] = {
    (Space ~> createAddressParser( "<erc20-token-contract-address>", mbRpi )).flatMap { contractAddress =>
      (Space ~> amountParser( "<amount-in-tokens>" )).map { numTokens =>
        ( contractAddress, numTokens )
      }
    }
  }

  private [sbtethereum] def genCompleteErc20TokenConvertAtomsToTokensParser( state : State, mbRpi : Option[RichParserInfo] ) : Parser[Tuple2[EthAddress, BigInt]] = {
    (Space ~> createAddressParser( "<erc20-token-contract-address>", mbRpi )).flatMap { contractAddress =>
      (Space ~> unsignedBigIntParser( "<amount-in-atoms>" )).map { numAtoms =>
        ( contractAddress, numAtoms )
      }
    }
  }

  private [sbtethereum] def genOptionalGenericAddressParser(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) : Parser[Option[EthAddress]] = {
    genGenericAddressParser( state, mbRpi ).?
  }

  private [sbtethereum] def parsesAsAddressAlias( putativeAlias : String ) : Boolean = Parser.parse( putativeAlias, ID ).isRight

  private [sbtethereum] def genNewAddressAliasParser(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) = {
    for {
      _       <- token(Space)
      alias   <- token(RawAddressAliasParser, "<alias>")
      address <- genGenericAddressParser( state, mbRpi ) // includes a leading space
    }
    yield {
      ( alias, address )
    }
  }

  private [sbtethereum] def genRecipientAddressParser(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) = {
    Space ~> createAddressParser( "<recipient-address>", mbRpi )
  }

  // for some reason, using a flatMap(...) dependent parser explcitly seems to yield more relable tab completion
  // otherwise we'd just use
  //     genRecipientAddressParser( state, mbRpi ) ~ valueInWeiParser("<amount>")
  private [sbtethereum] def genEthSendEtherParser(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) : Parser[( EthAddress, BigInt )] = {
    genRecipientAddressParser( state, mbRpi ).flatMap( addr => (Space ~> valueInWeiParser("<amount>")).map( valueInWei => Tuple2( addr, valueInWei ) ) )
  }

  private [sbtethereum] def _genContractAddressOrCodeHashParser( prefix : String )(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) : Parser[Either[EthAddress,EthHash]] = {
    val chp = ethHashParser( s"<${prefix}contract-code-hash>" )
    Space ~> (createAddressParser( s"<${prefix}address-hex>", mbRpi ).map( addr => Left[EthAddress,EthHash]( addr ) ) | chp.map( ch => Right[EthAddress,EthHash]( ch ) ))
  }

  private [sbtethereum] def genContractAddressOrCodeHashParser(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) : Parser[Either[EthAddress,EthHash]] = _genContractAddressOrCodeHashParser( "" )( state, mbRpi )


  private [sbtethereum] def genAbiMaybeWarningFunctionInputsParser( functionFilter : jsonrpc.Abi.Function => Boolean )(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) : Parser[(jsonrpc.Abi, Option[String], jsonrpc.Abi.Function, immutable.Seq[String])] = {
    val abiMaybeWarningParser = {
      val maybeAbiTupleParser = genAnyAbiSourceParser( state, mbRpi ).map( abiFromAbiSource )
      maybeAbiTupleParser.flatMap { maybeAbiTuple =>
        maybeAbiTuple match {
          case Some( Tuple2(abi, mbLookup) ) => success( Tuple2( abi, mbLookup.flatMap( _.genericShadowWarningMessage ) ) )
          case None                          => failure( "Could not resolve an ABI." )
        }
      }
    }
    abiMaybeWarningParser.flatMap {
      case ( abi, mbWarning ) => token(Space) ~> functionAndInputsParser(abi, functionFilter, mbRpi ).map { case ( function, inputs ) => ( abi, mbWarning, function, inputs ) }
    }
  }

  private [sbtethereum] def genAddressFunctionInputsAbiParser( functionFilter : jsonrpc.Abi.Function => Boolean )(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) : Parser[(EthAddress, jsonrpc.Abi.Function, immutable.Seq[String], jsonrpc.Abi, AbiLookup)] = {
    mbRpi match {
      case Some( rpi ) => {
        for {
          address              <- genGenericAddressParser( state, mbRpi )
          _                    <- Space
          abiLookup            =  abiLookupForAddressDefaultEmpty( rpi.chainId, address, rpi.abiOverrides )
          abi                  =  abiLookup.resolveAbi( None ).get
          functionInputsTuple  <- functionAndInputsParser( abi, functionFilter, mbRpi ) // Parser doesn't support withFilter for pattern matching
        }
        yield {
          val ( function, inputs ) = functionInputsTuple
          ( address, function, inputs, abi, abiLookup )
        }
      }
      case None => {
        WARNING.log("Failed to load RichParserInfo for address, function, inputs, abi parser")
        failure( "RichParserInfo is unavailable, can't parse ABI" )
      }
    }
  }
  private [sbtethereum] def genAddressFunctionInputsAbiMbValueInWeiParser( functionFilter : jsonrpc.Abi.Function => Boolean  )(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) : Parser[((EthAddress, jsonrpc.Abi.Function, immutable.Seq[String], jsonrpc.Abi, AbiLookup), Option[BigInt])] = {
    genAddressFunctionInputsAbiParser( functionFilter )( state, mbRpi ).flatMap { afia =>
      if ( afia._2.payable ) {
        (Space ~> valueInWeiParser("[ETH to pay, optional]")).?.flatMap( mbv => success(  ( afia, mbv ) ) ) // useless flatmap rather than map
      } else {
        success( ( afia, None ) )
      }
    }
  }
  private [sbtethereum] def genToAddressBytesAmountOptionalNonceParser(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) : Parser[Tuple4[EthAddress,immutable.Seq[Byte],BigInt,Option[BigInt]]] = {
    for {
      _       <- Space
      to      <- createAddressParser( "<to-address>", mbRpi )
      _       <- Space
      bytes   <- bytesParser("<txn-data-hex>")
      _       <- Space
      amount  <- valueInWeiParser("<amount-to-pay>")
      mbNonce <- (Space ~> unsignedBigIntParser("[optional nonce]")).?
    }
    yield {
      Tuple4(to, bytes.toVector, amount, mbNonce )
    }
  }
  private [sbtethereum] def genToAddressBytesAmountParser(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) : Parser[Tuple3[EthAddress,immutable.Seq[Byte],BigInt]] = {
    for {
      _       <- Space
      to      <- createAddressParser( "<to-address>", mbRpi )
      _       <- Space
      bytes   <- bytesParser("<txn-data-hex>")
      _       <- Space
      amount  <- valueInWeiParser("<amount-to-pay>")
    }
    yield {
      Tuple3(to, bytes.toVector, amount)
    }
  }
  private [sbtethereum] def genLiteralSetParser(
    state : State,
    mbLiterals : Option[immutable.Set[String]]
  ) : Parser[String] = {
    Space ~> token( mbLiterals.fold( failure("Failed to load acceptable values") : Parser[String] )( _.foldLeft( failure("No acceptable values") : Parser[String] )( ( nascent, next ) => nascent | literal(next) ) ) )
  }

  private def _aliasAbiSourceParser(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) : Parser[AbiSource] = {
    def deAbiPrefix( abiAlias : String ) = {
      if (abiAlias.startsWith("abi:")) abiAlias.drop(4) else abiAlias
    }
    mbRpi.fold( failure("ABI aliases not available!" ) : Parser[AbiSource] ) { rpi =>
      ( token( NotSpace ).examples( rpi.abiAliases.keySet.map( "abi:" + _ ), true ).map( str => AliasSource( rpi.chainId, deAbiPrefix(str) ) ) )
    }
  }

  private [sbtethereum] def genAnyAbiSourceParser(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) : Parser[AbiSource] = {
    mbRpi.fold( failure("ABI aliases not available!" ) : Parser[AbiSource] ) { rpi =>
      def tryHash = ethHashParser( s"<contract-code-or-abi-hash>" ).map( HashSource.apply )
      def tryAddress = createAddressParser( s"<contract-address-hex-or-alias>", mbRpi ).map( addr => AddressSource( rpi.chainId, addr, rpi.abiOverrides ) )
      def tryAbiAlias = _aliasAbiSourceParser(state, mbRpi)
      Space ~> ( tryAddress | tryHash | tryAbiAlias )
    }
  }

  private [sbtethereum] def genAddressAnyAbiSourceParser(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) : Parser[Tuple2[EthAddress, AbiSource]] = {
    (Space ~> createAddressParser( "<address-to-associate-with-abi>", mbRpi )).flatMap( addr => genAnyAbiSourceParser( state, mbRpi ).map( abiSource => (addr, abiSource) ) )
  }

  private [sbtethereum] def genAnyAbiSourceHexBytesParser(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) : Parser[Tuple2[AbiSource, immutable.Seq[Byte]]] = {
    genAnyAbiSourceParser( state, mbRpi ).flatMap { abiSource =>
      ( (token(Space) ~> token( (literal("0x").?) ~> token(HexDigit.*) ) ).map( chars => chars.mkString.decodeHexAsSeq ) ).map( hexSeq => ( abiSource, hexSeq ) )
    }
  }

  private [sbtethereum] val newAbiAliasParser : Parser[String] = {
    literal("abi:").? ~> token(ID, "<new-abi-alias>")
  }

  private [sbtethereum] def genNewAbiAliasAbiSourceParser(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) : Parser[Tuple2[String, AbiSource]] = {
    Space ~> (newAbiAliasParser ~ genAnyAbiSourceParser( state, mbRpi ))
  }

  // yields the parsed alias without the "abi:" prefix!
  private [sbtethereum] def genExistingAbiAliasParser(
    state : State,
    mbRpi : Option[RichParserInfo]
  ) : Parser[String] = {
    mbRpi.fold( failure( "Could not find RichParserInfo for abiAliases." ) : Parser[String] ) { rpi =>
      token(Space) ~> (literal("abi:") ~> token(NotSpace)).examples( rpi.abiAliases.keySet.map( "abi:" + _ ) )
    }
  }
}



