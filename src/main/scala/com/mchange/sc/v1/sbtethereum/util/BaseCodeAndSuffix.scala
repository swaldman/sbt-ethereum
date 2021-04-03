package com.mchange.sc.v1.sbtethereum.util

import com.mchange.sc.v1.sbtethereum._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.EthHash

import com.mchange.sc.v1.log.MLevel._

import _root_.io.bullet.borer._

import scala.collection._

/**
  *  solc now generates code that includes a suffix of non-EVM-code metadata (currently a swarm hash of a metadata file)
  * 
  *  This utility parses out the base code from the swarm-hash-containing suffix
  */
final object BaseCodeAndSuffix {

  private implicit lazy val logger = mlogger(this)

  private final val LegacySuffixRegex = """(?i)^(?:0x)?(\p{XDigit}*?)(a165627a7a72305820\p{XDigit}*0029)?$""".r

  private final val Empty = BaseCodeAndSuffix("","")

  def apply( fullHex : String, mbLog : Option[sbt.Logger] = None ) : BaseCodeAndSuffix = {
    try {
      if ( fullHex.length == 0 ) Empty else applyCbor( fullHex )
    }
    catch {
      case t : Throwable => {
        FINE.log("Error parsing putative CBOR suffix of compiled contract", t);
        mbLog.foreach{ _.warn("An error occurred while trying to parse metadata suffix of compilation to CBOR. (See sbt-ethereum.log for more.) Reverting to legacy metadata parser.") }
        applyLegacy( fullHex )
      }
    }
  }

  private def applyCbor( fullHex : String ) : BaseCodeAndSuffix = {
    val cborEnd = fullHex.length - 4
    val cborSizeHex = fullHex.substring( cborEnd )
    val cborSize = Integer.parseInt(cborSizeHex, 16)
    val cborStart = cborEnd - (cborSize * 2)
    val cbor = fullHex.substring( cborStart, cborEnd )
    // val parsedCbor = Cbor.decode(cbor.decodeHex).withPrintLogging().to[immutable.Map[String,Array[Byte]]].value
    val parsedCbor = Cbor.decode(cbor.decodeHex).to[immutable.Map[String,Array[Byte]]].value
    DEBUG.log( "Parsed CBOR: " + parsedCbor.mapValues( _.hex ) )
    BaseCodeAndSuffix( fullHex.substring(0, cborStart), fullHex.substring(cborStart) )
  }

  private def applyLegacy( fullHex : String ) : BaseCodeAndSuffix= {
    fullHex match {
      case LegacySuffixRegex( baseCodeHex, null )      => BaseCodeAndSuffix( baseCodeHex, "" )
      case LegacySuffixRegex( baseCodeHex, suffixHex ) => BaseCodeAndSuffix( baseCodeHex, suffixHex )
      case _                                        => throw new BadCodeFormatException( s"Unexpected code format: ${fullHex}" )
    }
  }
}
final case class BaseCodeAndSuffix( baseCodeHex : String, codeSuffixHex : String ) {
  lazy val baseCodeHash = EthHash.hash( baseCodeHex.decodeHex )
  lazy val fullCodeHash = EthHash.hash( (baseCodeHex + codeSuffixHex).decodeHex )
  lazy val fullCodeHex  = baseCodeHex + codeSuffixHex
}


