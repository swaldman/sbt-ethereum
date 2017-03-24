package com.mchange.sc.v1.sbtethereum

import sbt.State

import sbt.complete.{FixedSetExamples,Parser}
import sbt.complete.DefaultParsers._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{jsonrpc20,specification,EthAddress,EthHash}
import specification.Denominations
import jsonrpc20.Abi

import com.mchange.sc.v2.failable._

import com.mchange.sc.v1.log.MLevel._

import scala.collection._

import play.api.libs.json._

object Parsers {
  private implicit lazy val logger = mlogger( this )

  private val ZWSP = "\u200B" // we add zero-width space to parser examples lists where we don't want autocomplete to apply to unique examples

  private val RawAddressParser = ( literal("0x").? ~> Parser.repeat( HexDigit, 40, 40 ) ).map( chars => EthAddress.apply( chars.mkString ) )

  private def createSimpleAddressParser( tabHelp : String ) = Space.* ~> token( RawAddressParser, tabHelp )

  private def rawAliasParser( aliases : SortedMap[String,EthAddress] ) : Parser[String] = {
    aliases.keys.foldLeft( failure("not a known alias") : Parser[String] )( ( nascent, next ) => nascent | literal( next ) )
  }

  private def rawAliasedAddressParser( aliases : SortedMap[String,EthAddress] ) : Parser[EthAddress] = rawAliasParser( aliases ).map( aliases )

  private def createAddressParser( tabHelp : String, aliases : immutable.SortedMap[String,EthAddress] ) : Parser[EthAddress] = {
    if ( aliases.isEmpty ) {
      createSimpleAddressParser( tabHelp )
    } else {
      // println("CREATING COMPOUND PARSER")
      Space.* ~> token( RawAddressParser.examples( tabHelp ) | rawAliasedAddressParser( aliases ).examples( aliases.keySet, false ) )
    }
  }

  private def createAddressParser( tabHelp : String ) : Parser[EthAddress] = {
    val faliases = Repository.Database.findAllAliases
    if ( faliases.isFailed ) {
      WARNING.log("Could not select address aliases from the repository database, so aliases cannot be parsed")
      createSimpleAddressParser( tabHelp )
    } else {
      createAddressParser( tabHelp, faliases.get )
    }
  }

  private [sbtethereum] val NewAliasParser = token(Space.* ~> ID, "<alias>") ~ createSimpleAddressParser("<hex-address>")

  private [sbtethereum] val RawAmountParser = ((Digit|literal('.')).+).map( chars => BigDecimal( chars.mkString ) )

  //private [sbtethereum] def amountParser( tabHelp : String ) = token(Space.* ~> (Digit|literal('.')).+, tabHelp).map( chars => BigDecimal( chars.mkString ) )
  private [sbtethereum] def amountParser( tabHelp : String ) = token(Space.* ~> RawAmountParser, tabHelp)

  private [sbtethereum] val UnitParser = {
    val ( w, s, f, e ) = ( "wei", "szabo", "finney", "ether" );
    //(Space.* ~>(literal(w) | literal(s) | literal(f) | literal(e))).examples(w, s, f, e)
    Space.* ~> token(literal(w) | literal(s) | literal(f) | literal(e))
  }

  private [sbtethereum] def toValueInWei( amount : BigDecimal, unit : String ) = rounded(amount * BigDecimal(Denominations.Multiplier.BigInt( unit ))).toBigInt

  private [sbtethereum] def valueInWeiParser( tabHelp : String ) = {
    (amountParser( tabHelp ) ~ UnitParser).map { case ( amount, unit ) => toValueInWei( amount, unit ) }
  }

  private [sbtethereum] def ethSendEtherParser : Parser[( EthAddress, BigInt )] = { // def not val so that ideally they'd pick up new aliases, but doesn't work
    recipientAddressParser ~ valueInWeiParser("<amount>")
  }

  private [sbtethereum] def functionParser( abi : Abi.Definition, restrictToConstants : Boolean ) : Parser[Abi.Function] = {
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

  private def inputParser( input : Abi.Parameter, mbAliases : Option[immutable.SortedMap[String,EthAddress]] ) : Parser[String] = {
    val displayName = if ( input.name.length == 0 ) "mapping key" else input.name
    val sample = s"<${displayName}, of type ${input.`type`}>"
    if ( input.`type` == "address" && !mbAliases.isEmpty ) { // special case
      createAddressParser( sample, mbAliases.get ).map( _.hex )
    } else {
      token( (StringEscapable.map( str => s""""${str}"""") | NotQuoted).examples( FixedSetExamples( immutable.Set( sample, ZWSP ) ) ) )
    }
  }

  private def inputsParser( inputs : immutable.Seq[Abi.Parameter], mbAliases : Option[immutable.SortedMap[String,EthAddress]] ) : Parser[immutable.Seq[String]] = {
    val parserMaker : Abi.Parameter => Parser[String] = param => inputParser( param, mbAliases )
    inputs.map( parserMaker ).foldLeft( success( immutable.Seq.empty[String] ) )( (nascent, next) => nascent.flatMap( partial => Space.* ~> next.map( str => partial :+ str ) ) )
  }

  private def functionAndInputsParser( abi : Abi.Definition, restrictToConstants : Boolean, mbAliases : Option[immutable.SortedMap[String,EthAddress]] ) : Parser[(Abi.Function, immutable.Seq[String])] = {
    token( functionParser( abi, restrictToConstants ) ).flatMap( function => inputsParser( function.inputs, mbAliases ).map( seq => ( function, seq ) ) )
  }

  private [sbtethereum] val DbQueryParser : Parser[String] = (any.*).map( _.mkString.trim )

  // delayed parsers
  private def constructorFromAbi( abi : Abi.Definition ) : Abi.Constructor = {
    abi.constructors.length match {
      case 0 => Abi.Constructor.noArg
      case 1 => abi.constructors.head
      case _ => throw new Exception( s"""Constructor overloading not supprted (or legal in solidity). Found multiple constructors: ${abi.constructors.mkString(", ")}""" )
    }
  }
  private def resultFromCompilation( contractName : String, compilation : jsonrpc20.Compilation.Contract ) : Parser[ ( String, Option[ ( immutable.Seq[String], Abi.Definition, jsonrpc20.Compilation.Contract ) ] ) ] = {
    val mbAbi = compilation.info.mbAbiDefinition
    mbAbi match {
      case Some( abiString ) => {
        val abi = Json.parse( abiString ).as[Abi.Definition]
        val ctor = constructorFromAbi( abi )
        inputsParser( ctor.inputs, None ).map( seq => ( contractName, Some( ( seq, abi, compilation ) ) ) )
      }
      case None => failure( s"ABI not available for compilation of contract '$contractName'" )
    }
  }
  private [sbtethereum] def genContractNamesConstructorInputsParser(
    state : State,
    mbContracts : Option[immutable.Map[String,jsonrpc20.Compilation.Contract]]
  ) : Parser[(String, Option[(immutable.Seq[String], Abi.Definition, jsonrpc20.Compilation.Contract)])] = {
    val contracts = mbContracts.getOrElse( immutable.Map.empty )
    val contractNames = immutable.TreeSet( contracts.keys.toSeq : _* )( Ordering.comparatorToOrdering( String.CASE_INSENSITIVE_ORDER ) )
    val exSet = if ( contractNames.isEmpty ) immutable.Set("<contract-name>", ZWSP) else contractNames // non-breaking space to prevent autocompletion to dummy example
    Space.* ~> token( NotSpace examples exSet ).flatMap { name =>
      contracts.get( name ) match {
        case None                => success( Tuple2( name, None ) )
        case Some( compilation ) => resultFromCompilation( name, compilation )
      }
    }
  }

  // this is terrible. the nested option is because SBT's loadForParser function returns an Option, in case the task it loads from somehow fails
  private [sbtethereum] def genAliasParser( state : State, mbmbAliases : Option[Option[immutable.SortedMap[String,EthAddress]]] ) = {
    // XXX: we accept ID when we don't have aliases, bc maybe there was just a problem getting the aliases but they exist. (kind of weak?)
    Space.* ~> mbmbAliases.flatten.fold( ID )( aliases => token( rawAliasParser( aliases ).examples( aliases.keySet, false ) ) )
  }

  private def _genGenericAddressParser( state : State, mbAliases : Option[immutable.SortedMap[String,EthAddress]] ) : Parser[EthAddress] = {
    val sample = mbAliases.fold( "<address-hex>" )( map => if ( map.isEmpty ) "<address-hex>" else "<address-hex or alias>" )
    createAddressParser( sample, mbAliases.getOrElse( immutable.SortedMap.empty[String,EthAddress] ) )
  }

  private [sbtethereum] def genGenericAddressParser( state : State, mbmbAliases : Option[Option[immutable.SortedMap[String,EthAddress]]] ) : Parser[EthAddress] = {
    if ( mbmbAliases == None ) WARNING.log("Failed to load aliases for address parser.")
    _genGenericAddressParser( state, mbmbAliases.flatten )
  }

  private [sbtethereum] def genOptionalGenericAddressParser( state : State, mbmbAliases : Option[Option[immutable.SortedMap[String,EthAddress]]] ) : Parser[Option[EthAddress]] = {
    genGenericAddressParser( state, mbmbAliases ).?
  }

  private [sbtethereum] def recipientAddressParser = createAddressParser("<recipient-address-hex or alias>") // def not val so that ideally they'd pick up new aliases, but doesn't work

  private [sbtethereum] def genContractAddressOrCodeHashParser( state : State, mbmbAliases : Option[Option[immutable.SortedMap[String,EthAddress]]] ) : Parser[Either[EthAddress,EthHash]] = {
    val chp = token(Space.* ~> literal("0x").? ~> Parser.repeat( HexDigit, 64, 64 ), "<contract-code-hash>").map( chars => EthHash.withBytes( chars.mkString.decodeHex ) )
    genGenericAddressParser( state, mbmbAliases ).map( addr => Left[EthAddress,EthHash]( addr ) ) | chp.map( ch => Right[EthAddress,EthHash]( ch ) )
  }

  private [sbtethereum] def genAddressFunctionInputsAbiParser( restrictedToConstants : Boolean )(
    state : State,
    mbIdAndMbAliases : Option[(String,Option[immutable.SortedMap[String,EthAddress]])]
  ) : Parser[(EthAddress, Abi.Function, immutable.Seq[String], Abi.Definition)] = {
    mbIdAndMbAliases match {
      case Some( Tuple2(blockchainId, mbAliases) ) => { 
        _genGenericAddressParser( state, mbAliases ).map( a => ( a, abiForAddressOrEmpty(blockchainId,a) ) ).flatMap { case ( address, abi ) =>
          ( Space.* ~> functionAndInputsParser( abi, restrictedToConstants, mbAliases ) ).map { case ( function, inputs ) => ( address, function, inputs, abi ) }
        }
      }
      case None => {
        WARNING.log("Failed to load blockchain ID and aliases for address, function, inputs, abi parser")
        failure( "Blockchain ID and alias list are unavailable, can't parse address and ABI" )
      }
    }
  }
  private [sbtethereum] def genAddressFunctionInputsAbiMbValueInWeiParser( restrictedToConstants : Boolean  )(
    state : State,
    mbIdAndMbAliases : Option[(String,Option[immutable.SortedMap[String,EthAddress]])]
  ) : Parser[((EthAddress, Abi.Function, immutable.Seq[String], Abi.Definition), Option[BigInt])] = {
    genAddressFunctionInputsAbiParser( restrictedToConstants )( state, mbIdAndMbAliases ).flatMap { afia =>
      if ( afia._2.payable ) {
        valueInWeiParser("[ETH to pay, optional]").?.flatMap( mbv => success(  ( afia, mbv ) ) ) // useless flatmap rather than map
      } else {
        success( ( afia, None ) )
      }
    }
  }
}
