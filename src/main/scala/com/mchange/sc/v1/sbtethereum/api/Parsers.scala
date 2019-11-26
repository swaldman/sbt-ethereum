package com.mchange.sc.v1.sbtethereum.api

import com.mchange.sc.v1.sbtethereum.util.{Parsers => UP}
import com.mchange.sc.v1.consuela.ethereum.{EthAddress, EthHash}

import sbt.State
import sbt.complete.Parser

object Parsers {
  val  RichParserInfo = com.mchange.sc.v1.sbtethereum.RichParserInfo
  type RichParserInfo = com.mchange.sc.v1.sbtethereum.RichParserInfo

  implicit val RichParserInfoFormat = com.mchange.sc.v1.sbtethereum.util.SJsonNewFormats.RichParserInfoFormat

  def genAddressParser( tabHelp : String )( state : State, mbRpi : Option[RichParserInfo] ) : Parser[EthAddress] = UP.genAddressParser( tabHelp )( state, mbRpi )
  // def genAliasWithAddressParser( state : sbt.State, mbRpi : Option[RichParserInfo] ) : Parser[(String,EthAddress)] = UP.genAliasWithAddressParser( state, mbRpi )

  def ethHashParser( exampleStr : String ) : Parser[EthHash] = UP.ethHashParser( exampleStr )
}
