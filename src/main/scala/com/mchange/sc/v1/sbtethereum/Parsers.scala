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

object Parsers {
  private implicit lazy val logger = mlogger( this )

  private val ZWSP = "\u200B" // we add zero-width space to parser examples lists where we don't want autocomplete to apply to unique examples

  private val RawAddressParser = ( literal("0x").? ~> Parser.repeat( HexDigit, 40, 40 ) ).map( chars => EthAddress.apply( chars.mkString ) )

  private def createSimpleAddressParser( tabHelp : String ) = Space.* ~> token( RawAddressParser, tabHelp )

  private def rawAliasedAddressParser( aliases : SortedMap[String,EthAddress] ) = {
    aliases.keys.foldLeft( failure("not a known alias") : Parser[EthAddress] )( ( nascent, next ) => nascent | literal( next ).map( aliases ) )
  }

  private def rawAliasParser( aliases : SortedMap[String,EthAddress] ) = {
    aliases.keys.foldLeft( failure("not a known alias") : Parser[String] )( ( nascent, next ) => nascent | literal( next ) )
  }

  private def createAddressParser( tabHelp : String ) = {
    val faliases = Repository.Database.findAllAliases
    if ( faliases.isFailed ) {
      WARNING.log("Could not select address aliases from the repository database, so aliases cannot be parsed")
      createSimpleAddressParser( tabHelp )
    } else {
      val aliases = faliases.get
      if ( aliases.isEmpty ) {
        createSimpleAddressParser( tabHelp )
      } else {
        // println("CREATING COMPOUND PARSER")
        Space.* ~> token( RawAddressParser.examples( tabHelp ) | rawAliasedAddressParser( aliases ).examples( aliases.keySet, false ) )
      }
    }
  }

  private [sbtethereum] def aliasParser = { // def not val so that ideally they'd pick up new aliases, but doesn't work
    Space.* ~> Repository.Database.findAllAliases.fold( _ => ID, aliases => rawAliasParser( aliases ) )
  }

  private [sbtethereum] def genericAddressParser = createAddressParser("<address-hex>") // def not val so that ideally they'd pick up new aliases, but doesn't work

  private [sbtethereum] def recipientAddressParser = createAddressParser("<recipient-address-hex>") // def not val so that ideally they'd pick up new aliases, but doesn't work

  private [sbtethereum] val NewAliasParser = token(Space.* ~> ID, "<alias>") ~ createSimpleAddressParser("<hex-address>")

  private [sbtethereum] val AmountParser = token(Space.* ~> (Digit|literal('.')).+, "<amount>").map( chars => BigDecimal( chars.mkString ) )

  private [sbtethereum] val UnitParser = {
    val ( w, s, f, e ) = ( "wei", "szabo", "finney", "ether" );
    //(Space.* ~>(literal(w) | literal(s) | literal(f) | literal(e))).examples(w, s, f, e)
    Space.* ~> token(literal(w) | literal(s) | literal(f) | literal(e))
  }

  private [sbtethereum] val ValueInWeiParser = {
    (AmountParser ~ UnitParser).map { case ( amount, unit ) => rounded(amount * BigDecimal(Denominations.Multiplier.BigInt( unit ))).toBigInt }
  }

  private [sbtethereum] def ethSendEtherParser : Parser[( EthAddress, BigInt )] = { // def not val so that ideally they'd pick up new aliases, but doesn't work
    recipientAddressParser ~ ValueInWeiParser
  }

  private [sbtethereum] def contractNamesParser : (State, immutable.Set[String]) => Parser[String] = {
    (state, contractNames) => {
      val exSet = if ( contractNames.isEmpty ) immutable.Set("<contract-name>", ZWSP) else contractNames // non-breaking space to prevent autocompletion to dummy example
      Space ~> token( NotSpace examples exSet )
    }
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

  private [sbtethereum] def inputParser( input : Abi.Function.Parameter, unique : Boolean ) : Parser[String] = {
    val displayName = if ( input.name.length == 0 ) "mapping key" else input.name
    (StringEscapable.map( str => s""""${str}"""") | NotQuoted).examples( FixedSetExamples( immutable.Set( s"<${displayName}, of type ${input.`type`}>", ZWSP ) ) )
  }

  private [sbtethereum] def inputsParser( inputs : immutable.Seq[Abi.Function.Parameter] ) : Parser[immutable.Seq[String]] = {
    val unique = inputs.size <= 1
    val parserMaker : Abi.Function.Parameter => Parser[String] = param => inputParser( param, unique )
    inputs.map( parserMaker ).foldLeft( success( immutable.Seq.empty[String] ) )( (nascent, next) => nascent.flatMap( partial => Space ~> next.map( str => partial :+ str ) ) )
  }

  private [sbtethereum] def functionAndInputsParser( abi : Abi.Definition, restrictToConstants : Boolean ) : Parser[(Abi.Function, immutable.Seq[String])] = {
    token( functionParser( abi, restrictToConstants ) ).flatMap( function => inputsParser( function.inputs ).map( seq => ( function, seq ) ) )
  }

  private [sbtethereum] def unrestrictedAddressFunctionInputsAbiParser : Parser[(EthAddress, Abi.Function, immutable.Seq[String], Abi.Definition)] = {
    genericAddressParser.map( a => ( a, abiForAddress(a) ) ).flatMap { case ( address, abi ) =>
      ( Space ~> functionAndInputsParser( abi, false ) ).map { case ( function, inputs ) => ( address, function, inputs, abi ) }
    }
  }

  private [sbtethereum] def restrictedAddressFunctionInputsAbiParser : Parser[(EthAddress, Abi.Function, immutable.Seq[String], Abi.Definition)] = {
    genericAddressParser.map( a => ( a, abiForAddress(a) ) ).flatMap { case ( address, abi ) =>
      ( Space ~> functionAndInputsParser( abi, true ) ).map { case ( function, inputs ) => ( address, function, inputs, abi ) }
    }
  }

  private [sbtethereum] def contractAddressOrCodeHashParser : Parser[Either[EthAddress,EthHash]] = {
    val chp = token(Space.* ~> literal("0x").? ~> Parser.repeat( HexDigit, 64, 64 ), "<contract-code-hash>").map( chars => EthHash.withBytes( chars.mkString.decodeHex ) )
    createAddressParser("<address-hex>").map( addr =>Left[EthAddress,EthHash]( addr ) ) | chp.map( ch => Right[EthAddress,EthHash]( ch ) )
  }

  private [sbtethereum] val DbQueryParser : Parser[String] = (any.*).map( _.mkString.trim )


  
}
