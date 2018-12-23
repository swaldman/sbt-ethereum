package com.mchange.sc.v1.sbtethereum.lib

import sbt.State
import sbt.complete.Parser

import com.mchange.sc.v1.consuela.ethereum.EthAddress

import com.mchange.sc.v1.sbtethereum.util

object Parsers {
  def parserGeneratorForAddress[T]( addressTabHelp : String )( op : Parser[EthAddress] => Parser[T] ) : ( State, Option[RichParserInfo] ) => Parser[T] = { ( state, mbRpi ) =>
    op( util.Parsers.createAddressParser( addressTabHelp, mbRpi ) )
  }
}

