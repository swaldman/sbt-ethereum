package com.mchange.sc.v1.sbtethereum.util

import com.mchange.sc.v1.sbtethereum._

import sbt.State

import sbt.complete.{FixedSetExamples,Parser}
import sbt.complete.DefaultParsers._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{jsonrpc,specification,EthAddress,EthHash}
import specification.Denominations
import jsonrpc.Abi

import com.mchange.sc.v2.ens
import com.mchange.sc.v2.ens.NoResolverSetException

import com.mchange.sc.v3.failable._

import com.mchange.sc.v1.log.MLevel._

import scala.collection._

import scala.util.matching.Regex

import scala.util.control.NonFatal

import play.api.libs.json._

object Parsers {
  private implicit lazy val logger = mlogger( this )

  private val ZWSP = "\u200B" // we add zero-width space to parser examples lists where we don't want autocomplete to apply to unique examples

  private val RawAddressParser = ( literal("0x").? ~> Parser.repeat( HexDigit, 40, 40 ) ).map( chars => EthAddress.apply( chars.mkString ) )

  private val EmptyAddressAliasMap = immutable.SortedMap.empty[String,EthAddress]

  private final object EnsAddressCache {
    private val TTL     = 300000 // 300 secs, 5 mins, maybe someday make this sensitive to ENS TTLs
    private val MaxSize = 100

    private final case class Key( chainId : Int, jsonRpcUrl : String, nameServiceAddress : EthAddress, nameServiceTld : String, nameServiceReverseTld : String, name : String )

    // MT: synchronized on EnsAddressCache's lock
    private val cache = mutable.HashMap.empty[Key,Tuple2[Failable[EthAddress],Long]]

    private def doLookup( key : Key ) : ( Failable[EthAddress], Long ) = {
      TRACE.log( s"doLookup( $key )" )
      val ensClient = ens.Client( jsonRpcUrl = key.jsonRpcUrl, nameServiceAddress = key.nameServiceAddress, tld = key.nameServiceTld, reverseTld = key.nameServiceReverseTld )
      val name = key.name
      val ts = System.currentTimeMillis()
      try {
        Tuple2( ensClient.address( name ).toFailable( s"No address has been associated with ENS name '${name}'." ), ts )
      }
      catch {
        case NonFatal( nfe ) => ( Failable.fail( s"Exception while looking up ENS name '${name}': ${nfe}", includeStackTrace = false ), ts )
      }
    }

    // called only from synchronized lookup(...)
    private def update( key : Key ) : Tuple2[Failable[EthAddress],Long] = {
      val updated = doLookup( key )
      cache += Tuple2( key, updated )
      updated
    }

    def lookup( api : AddressParserInfo, name : String ) : Failable[EthAddress] = this.synchronized {
      val key = Key( api.chainId, api.jsonRpcUrl, api.nameServiceAddress, api.nameServiceTld, api.nameServiceReverseTld, name )
      val ( result, timestamp ) = {
        cache.get( key ) match {
          case Some( tup ) => if ( System.currentTimeMillis() > tup._2 + TTL ) update( key ) else tup
          case None        => update( key )
        }
      }
      if ( cache.size > MaxSize ) { // an ugly, but easy, way to bound the size of th cache
        cache.clear()
        cache += Tuple2( key, Tuple2( result, timestamp ) )
      }
      result
    }

    def reset() : Unit = this.synchronized( cache.clear() )
  }

  private [sbtethereum]
  def reset() : Unit = EnsAddressCache.reset()

  private def createSimpleAddressParser( tabHelp : String ) = Space.* ~> token( RawAddressParser, tabHelp )

  private def rawAddressAliasParser( aliases : SortedMap[String,EthAddress] ) : Parser[String] = {
    aliases.keys.foldLeft( failure("not a known alias") : Parser[String] )( ( nascent, next ) => nascent | literal( next ) )
  }

  private def rawAliasedAddressParser( aliases : SortedMap[String,EthAddress] ) : Parser[EthAddress] = rawAddressAliasParser( aliases ).map( aliases )

  private def createAddressParser( tabHelp : String, mbApi : Option[AddressParserInfo] ) : Parser[EthAddress] = {
    mbApi match {
      case Some( api ) => {
        val aliases = api.mbAliases.getOrElse( EmptyAddressAliasMap )
        val tld = api.nameServiceTld
        val allExamples = Vector( tabHelp, s"<ens-name>.${tld}" ) ++ aliases.keySet
        token(Space.*) ~> token( RawAddressParser | rawAliasedAddressParser( aliases ) | ensNameToAddressParser( api ) ).examples( allExamples : _* )
        //Space.* ~> token( RawAddressParser.examples( tabHelp ) | rawAliasedAddressParser( aliases ).examples( aliases.keySet, false ) | ensNameToAddressParser( api ).examples( s"<ens-name>.${tld}" ) )
      }
      case None => {
        createSimpleAddressParser( tabHelp )
      }
    }
  }

  private [sbtethereum] val RawIntParser = (Digit.+).map( chars => chars.mkString.toInt )

  private [sbtethereum] val RawBigIntParser = (Digit.+).map( chars => BigInt( chars.mkString ) )

  private [sbtethereum] def bigIntParser( tabHelp : String ) = token(Space.* ~> RawBigIntParser, tabHelp)

  private [sbtethereum] val RawAmountParser = ((Digit|literal('.')).+).map( chars => BigDecimal( chars.mkString ) )

  private [sbtethereum] val RawByteParser = Parser.repeat( HexDigit, 2, 2 ).map( _.mkString.decodeHexAsSeq.head )

  private [sbtethereum] val RawBytesParser = literal("0x") ~> RawByteParser.*

  //private [sbtethereum] def amountParser( tabHelp : String ) = token(Space.* ~> (Digit|literal('.')).+, tabHelp).map( chars => BigDecimal( chars.mkString ) )
  private [sbtethereum] def amountParser( tabHelp : String ) = token(Space.*) ~> token(RawAmountParser, tabHelp)

  private [sbtethereum] def bytesParser( tabHelp : String ) = token(Space.*) ~> token(literal("0x")) ~> token(RawByteParser.*, tabHelp)

  private [sbtethereum] val UnitParser = {
    val ( w, gw, s, f, e ) = ( "wei", "gwei", "szabo", "finney", "ether" );
    token(Space.*) ~> token( literal(w) | literal(gw) | literal(s) | literal(f) | literal(e) )
  }

  private [sbtethereum] def toValueInWei( amount : BigDecimal, unit : String ) : BigInt = rounded(amount * BigDecimal(Denominations.Multiplier.BigInt( unit )))

  private [sbtethereum] def valueInWeiParser( tabHelp : String ) : Parser[BigInt] = {
    (amountParser( tabHelp ) ~ UnitParser).map { case ( amount, unit ) => toValueInWei( amount, unit ) }
  }

  private [sbtethereum] val SolcJVersionParser : Parser[Option[String]] = {
    val mandatory = compile.SolcJInstaller.SupportedVersions.foldLeft( failure("No supported versions") : Parser[String] )( ( nascent, next ) => nascent | literal(next) )
    Space.* ~> token(mandatory.?)
  }

  private [sbtethereum] def rawEnsNameParser( tld : String ) : Parser[String] = {
    val suffix = s".${tld}";
    (NotSpace <~ literal( suffix )).map( _ + suffix )
  }

  private [sbtethereum] def ensNameParser( tld : String ) : Parser[String] = token( Space.* ) ~> token( rawEnsNameParser( tld ) ).examples( s"<ens-name>.${tld}" )

  private [sbtethereum] def ensNameToAddressParser( api : AddressParserInfo ) : Parser[EthAddress] = {
    ensNameParser( api.nameServiceTld ).flatMap { name =>
      val faddress = EnsAddressCache.lookup( api, name )
      if ( faddress.isSucceeded ) success( faddress.get ) else failure( faddress.assertFailed.toString )
    }
  }

  private [sbtethereum] def ensNameNumDiversionParser( tld : String ) : Parser[(String, Option[Int])] = {
    token( Space.* ) ~> token( rawEnsNameParser( tld ) ).examples( s"<ens-name>.${tld}" ) ~ ( token( Space.+ ) ~> token(RawIntParser).examples("[<optional number of diversion auctions>]") ).?
  }

  private [sbtethereum] def ensPlaceNewBidParser( tld : String ) : Parser[(String, BigInt, Option[BigInt])] = {
    val baseParser = token(Space.*) ~> token( rawEnsNameParser( tld ) ).examples( s"<ens-name>.${tld}" ) ~ ( token(Space.+) ~> valueInWeiParser( "<amount to bid>" ) ) ~ ( token(Space.*) ~> valueInWeiParser( "[<optional-overpayment-amount>]" ).? )
    baseParser.map { case ( (name, amount), mbOverpayment ) => ( name, amount, mbOverpayment ) }
  }

  private [sbtethereum] def ethHashParser( exampleStr : String ) : Parser[EthHash] = token(Space.* ~> literal("0x").? ~> Parser.repeat( HexDigit, 64, 64 ), exampleStr).map( chars => EthHash.withBytes( chars.mkString.decodeHex ) )

  private [sbtethereum] def bidHashOrNameParser( tld : String ) : Parser[Either[EthHash,String]] = {
    ethHashParser("<bid-hash>").map( hash => (Left(hash) : Either[EthHash,String]) ) | ensNameParser( tld ).map( name => (Right(name) : Either[EthHash,String]) )
  }

  private [sbtethereum] def functionParser( abi : Abi, restrictToConstants : Boolean ) : Parser[Abi.Function] = {
    val namesToFunctions           = abi.functions.groupBy( _.name )

    val overloadedNamesToFunctions = namesToFunctions.filter( _._2.length > 1 )
    val nonoverloadedNamesToFunctions : Map[String,Abi.Function] = (namesToFunctions -- overloadedNamesToFunctions.keySet).map( tup => ( tup._1, tup._2.head ) )

    def createQualifiedNameForOverload( function : Abi.Function ) : String = function.name + "(" + function.inputs.map( _.`type` ).mkString(",") + ")"

    def createOverloadBinding( function : Abi.Function ) : ( String, Abi.Function ) = ( createQualifiedNameForOverload( function ), function )

    val qualifiedOverloadedNamesToFunctions : Map[String, Abi.Function] = overloadedNamesToFunctions.values.flatMap( _.map( createOverloadBinding ) ).toMap

    val processedNamesToFunctions = {
      val raw = (qualifiedOverloadedNamesToFunctions ++ nonoverloadedNamesToFunctions).toMap
      if ( restrictToConstants ) {
        raw.filter( _._2.constant )
      } else {
        raw
      }
    }

    val baseParser = processedNamesToFunctions.keySet.foldLeft( failure("not a function name") : Parser[String] )( ( nascent, next ) => nascent | literal( next ) )

    baseParser.map( processedNamesToFunctions )
  }

  private def inputParser( input : Abi.Parameter, mbApi : Option[AddressParserInfo] ) : Parser[String] = {
    val displayName = if ( input.name.length == 0 ) "mapping key" else input.name
    val sample = s"<${displayName}, of type ${input.`type`}>"
    if ( input.`type` == "address" && mbApi.nonEmpty ) { // special case
      createAddressParser( sample, mbApi ).map( _.hex )
    } else {
      token( (StringEscapable.map( str => s""""${str}"""") | NotQuoted).examples( FixedSetExamples( immutable.Set( sample, ZWSP ) ) ) )
    }
  }

  private def inputsParser( inputs : immutable.Seq[Abi.Parameter], mbApi : Option[AddressParserInfo] ) : Parser[immutable.Seq[String]] = {
    val parserMaker : Abi.Parameter => Parser[String] = param => inputParser( param, mbApi )
    inputs.map( parserMaker ).foldLeft( success( immutable.Seq.empty[String] ) )( (nascent, next) => nascent.flatMap( partial => Space.* ~> next.map( str => partial :+ str ) ) )
  }

  private def functionAndInputsParser( abi : Abi, restrictToConstants : Boolean, mbApi : Option[AddressParserInfo] ) : Parser[(Abi.Function, immutable.Seq[String])] = {
    token( functionParser( abi, restrictToConstants ) ).flatMap( function => inputsParser( function.inputs, mbApi ).map( seq => ( function, seq ) ) )
  }

  private [sbtethereum] val DbQueryParser : Parser[String] = (any.*).map( _.mkString.trim )

  // XXX: We add case-insensitive flags only to "naive" regexs when defaultToCaseInsensitive is true.
  //      The intent is that users who explicitly set flags should have them unmolested. But we don't
  //      actually test for setting flags. We test for th presence of "(?", which would include flag-setting,
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
  private def constructorFromAbi( abi : Abi ) : Abi.Constructor = {
    abi.constructors.length match {
      case 0 => Abi.Constructor.noArgNoEffect
      case 1 => abi.constructors.head
      case _ => throw new Exception( s"""Constructor overloading not supprted (or legal in solidity). Found multiple constructors: ${abi.constructors.mkString(", ")}""" )
    }
  }

  private def fullFromSeed( contractName : String, seed : MaybeSpawnable.Seed ) : Parser[SpawnInstruction.Full] = {
    val ctor = constructorFromAbi( seed.abi )
    inputsParser( ctor.inputs, None ).map( seq => SpawnInstruction.Full( contractName, seq, seed ) )
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
        case Some( seed ) => fullFromSeed( name, seed )
      }
    }
    val autoParser = Space.* map { _ => SpawnInstruction.Auto }
    Space.* ~> ( argsParser | autoParser )
  }

  private [sbtethereum] def genAddressAliasParser(
    state : State,
    mbApi : Option[AddressParserInfo]
  ) = {
    // XXX: we accept ID (sbt's built-in identifier parser) when we don't have aliases,
    //      bc maybe there was just a problem getting the aliases but they exist. (kind of weak?)
    Space.* ~> mbApi.flatMap( _.mbAliases ).fold( ID )( aliases => token( rawAddressAliasParser( aliases ).examples( aliases.keySet, false ) ) )
  }

  private [sbtethereum] def genEnsNameOwnerAddressParser( state : State, mbApi : Option[AddressParserInfo] ) : Parser[(String,EthAddress)] = {
    _genEnsNameXxxAddressParser("<owner-address-hex>")( state, mbApi )
  }

  private [sbtethereum] def genEnsNameAddressParser( state : State, mbApi : Option[AddressParserInfo] ) : Parser[(String,EthAddress)] = {
    _genEnsNameXxxAddressParser("<address-hex>")( state, mbApi )
  }

  private [sbtethereum] def genEnsNameResolverAddressParser( state : State, mbApi : Option[AddressParserInfo] ) : Parser[(String,EthAddress)] = {
    _genEnsNameXxxAddressParser("<resolver-address-hex>")( state, mbApi )
  }

  private def _genEnsNameXxxAddressParser( example : String )( state : State, mbApi : Option[AddressParserInfo] ) : Parser[(String,EthAddress)] = {
    mbApi.map { api =>
      (ensNameParser( api.nameServiceTld ) ~ (token(Space.+) ~> createAddressParser( example, mbApi )))
    } getOrElse {
      failure( "Failed to retrieve AddressParserInfo." )
    }
  }

  private [sbtethereum] def genGenericAddressParser( state : State, mbApi : Option[AddressParserInfo] ) : Parser[EthAddress] = {
    createAddressParser( "<address-hex>", mbApi )
  }

  private [sbtethereum] def genOptionalGenericAddressParser(
    state : State,
    mbApi : Option[AddressParserInfo]
  ) : Parser[Option[EthAddress]] = {
    genGenericAddressParser( state, mbApi ).?
  }

  private [sbtethereum] def parsesAsAddressAlias( putativeAlias : String ) : Boolean = Parser.parse( putativeAlias, ID ).isRight

  private [sbtethereum] def genNewAddressAliasParser(
    state : State,
    mbApi : Option[AddressParserInfo]
  ) = {
    token(Space.*) ~> token(ID, "<alias>") ~ genGenericAddressParser( state, mbApi )
  }

  private [sbtethereum] def genRecipientAddressParser(
    state : State,
    mbApi : Option[AddressParserInfo]
  ) = {
    createAddressParser( "<recipient-address>", mbApi )
  }

  // for some reason, using a flatMap(...) dependent parser explcitly seems to yield more relable tab completion
  // otherwise we'd just use
  //     genRecipientAddressParser( state, mbApi ) ~ valueInWeiParser("<amount>")
  private [sbtethereum] def genEthSendEtherParser(
    state : State,
    mbApi : Option[AddressParserInfo]
  ) : Parser[( EthAddress, BigInt )] = {
    genRecipientAddressParser( state, mbApi ).flatMap( addr => valueInWeiParser("<amount>").map( valueInWei => Tuple2( addr, valueInWei ) ) )
  }

  private [sbtethereum] def _genContractAddressOrCodeHashParser( prefix : String )(
    state : State,
    mbApi : Option[AddressParserInfo]
  ) : Parser[Either[EthAddress,EthHash]] = {
    val chp = ethHashParser( s"<${prefix}contract-code-hash>" )
    createAddressParser( s"<${prefix}address-hex>", mbApi ).map( addr => Left[EthAddress,EthHash]( addr ) ) | chp.map( ch => Right[EthAddress,EthHash]( ch ) )
  }

  private [sbtethereum] def genContractAddressOrCodeHashParser(
    state : State,
    mbApi : Option[AddressParserInfo]
  ) : Parser[Either[EthAddress,EthHash]] = _genContractAddressOrCodeHashParser( "" )( state, mbApi )


  private [sbtethereum] def genContractAbiMatchParser(
    state : State,
    mbApi : Option[AddressParserInfo]
  ) : Parser[Tuple2[EthAddress,Either[EthAddress,EthHash]]] = {
    createAddressParser( "<address-to-associate-with-abi>", mbApi ).flatMap( addr => (token(Space.+) ~> _genContractAddressOrCodeHashParser("abi-source-")(state, mbApi)).map( either => Tuple2( addr, either ) ) )
  }

  private [sbtethereum] def genAddressFunctionInputsAbiParser( restrictedToConstants : Boolean )(
    state : State,
    mbApi : Option[AddressParserInfo]
  ) : Parser[(EthAddress, Abi.Function, immutable.Seq[String], Abi, AbiLookup)] = {
    mbApi match {
      case Some( api ) => {
        genGenericAddressParser( state, mbApi ).map { a =>
          val abiLookup = abiLookupForAddressDefaultEmpty( api.chainId, a, api.abiOverrides )
          Tuple3( a, abiLookup, abiLookup.resolveAbi( None ).get )
        }.flatMap { case ( address, abiLookup, abi ) => ( Space.* ~> functionAndInputsParser( abi, restrictedToConstants, mbApi ) ).map { case ( function, inputs ) => ( address, function, inputs, abi, abiLookup ) } }
      }
      case None => {
        WARNING.log("Failed to load blockchain ID for address, function, inputs, abi parser")
        failure( "Blockchain ID is unavailable, can't parse ABI" )
      }
    }
  }
  private [sbtethereum] def genAddressFunctionInputsAbiMbValueInWeiParser( restrictedToConstants : Boolean  )(
    state : State,
    mbApi : Option[AddressParserInfo]
  ) : Parser[((EthAddress, Abi.Function, immutable.Seq[String], Abi, AbiLookup), Option[BigInt])] = {
    genAddressFunctionInputsAbiParser( restrictedToConstants )( state, mbApi ).flatMap { afia =>
      if ( afia._2.payable ) {
        valueInWeiParser("[ETH to pay, optional]").?.flatMap( mbv => success(  ( afia, mbv ) ) ) // useless flatmap rather than map
      } else {
        success( ( afia, None ) )
      }
    }
  }
  private [sbtethereum] def genToAddressBytesAmountOptionalNonceParser(
    state : State,
    mbApi : Option[AddressParserInfo]
  ) = {
    val raw = createAddressParser( "<to-address>", mbApi ).flatMap( addr => success(addr) ~ bytesParser("<txn-data-hex>") ~ valueInWeiParser("<amount-to-pay>") ~ bigIntParser("[optional nonce]").? )
    raw.map { case ((( to, bytes ), amount), mbNonce ) => (to, bytes.toVector, amount, mbNonce ) }
  }
  private [sbtethereum] def genLiteralSetParser(
    state : State,
    mbLiterals : Option[immutable.Set[String]]
  ) : Parser[String] = {
    Space.* ~> token( mbLiterals.fold( failure("Failed to load acceptable values") : Parser[String] )( _.foldLeft( failure("No acceptable values") : Parser[String] )( ( nascent, next ) => nascent | literal(next) ) ) )
  }
}
