package com.mchange.sc.v1.sbtethereum.lib

import sbt.State
import sbt.complete.Parser

import com.mchange.sc.v1.consuela.ethereum.EthAddress

import com.mchange.sc.v1.sbtethereum.util

object Parsers {
  def addressParser( addressTabHelp : String, mbRpi : Option[RichParserInfo] ) : Parser[EthAddress] = {
    util.Parsers.createAddressParser( addressTabHelp, mbRpi )
  }
  def parserGenerator[T]( op : Option[RichParserInfo] => Parser[T] ) : ( State, Option[RichParserInfo] ) => Parser[T] = { ( state, mbRpi ) =>
    op( mbRpi )
  }
  def parserGeneratorForAddress[T]( addressTabHelp : String )( op : Parser[EthAddress] => Parser[T] ) : ( State, Option[RichParserInfo] ) => Parser[T] = { ( state, mbRpi ) =>
    op( addressParser( addressTabHelp, mbRpi ) )
  }
}

