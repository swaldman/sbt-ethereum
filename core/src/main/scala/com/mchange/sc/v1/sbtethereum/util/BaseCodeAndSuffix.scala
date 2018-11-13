package com.mchange.sc.v1.sbtethereum.util

import com.mchange.sc.v1.sbtethereum._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.EthHash

/**
  *  solc now generates code that includes a suffix of non-EVM-code metadata (currently a swarm hash of a metadata file)
  * 
  *  This utility parses out the base code from the swarm-hash-containing suffix
  */
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


