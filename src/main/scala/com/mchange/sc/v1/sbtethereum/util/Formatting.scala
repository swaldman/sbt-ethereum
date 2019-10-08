package com.mchange.sc.v1.sbtethereum.util

import Abi.abiLookupForAddress

import com.mchange.sc.v1.sbtethereum.{shoebox, syncOut, EthValue, LineSep, PriceFeed, SbtEthereumException}

import com.mchange.sc.v1.consuela._

import com.mchange.sc.v1.consuela.ethereum.{ethabi, jsonrpc, EthAddress,EthHash, EthSignature, EthTransaction}
import com.mchange.sc.v1.consuela.ethereum.specification.{Denominations,Types}
import Denominations.Denomination

import Types.{ByteSeqExact32,Unsigned256}

import com.mchange.sc.v3.failable._
import com.mchange.sc.v1.log.MLevel._

import java.time.{Duration => JDuration, Instant, ZoneId}
import java.time.format.{FormatStyle, DateTimeFormatter}
import java.time.temporal.ChronoUnit

import scala.collection._

private [sbtethereum]
object Formatting {
  
  private implicit lazy val logger = mlogger( this )

  private val InstantFormatter = DateTimeFormatter.RFC_1123_DATE_TIME.withZone( ZoneId.systemDefault() )
  private val TimeFormatter    = DateTimeFormatter.ofLocalizedTime( FormatStyle.SHORT ).withZone( ZoneId.systemDefault() )

  def bail( message : String ) : Nothing = throw new SbtEthereumException( message )

  def formatInstant( instant : Instant ) : String = InstantFormatter.format( instant )

  def formatInstant( l : Long ) : String = formatInstant( Instant.ofEpochMilli( l ) )

  def formatInstantOrUnknown( mbInstant : Option[Instant] ) = mbInstant.map( formatInstant ).getOrElse( "???" )

  def formatTime( l : Long ) : String = TimeFormatter.format( Instant.ofEpochMilli( l ) )

  def formatDurationInSeconds( seconds : Long, formatUnit : ChronoUnit ) : String = {
    val duration = JDuration.ofSeconds( seconds )
    val amountInUnit = BigDecimal( duration.getSeconds() ) / formatUnit.getDuration().getSeconds()
    s"${amountInUnit} ${formatUnit.toString.toLowerCase}"
  }

  private def formatEthValue( ethValue : EthValue )                      : String = ethValue.formatted
  def formatEthValue( valueInWei : BigInt, denomination : Denomination ) : String = formatEthValue( EthValue( valueInWei, denomination ) )

  def formatInWei   ( valueInWei : BigInt ) : String = formatEthValue( valueInWei, Denominations.Wei    )
  def formatInGWei  ( valueInWei : BigInt ) : String = formatEthValue( valueInWei, Denominations.GWei   )
  def formatInSzabo ( valueInWei : BigInt ) : String = formatEthValue( valueInWei, Denominations.Szabo  )
  def formatInFinney( valueInWei : BigInt ) : String = formatEthValue( valueInWei, Denominations.Finney )
  def formatInEther ( valueInWei : BigInt ) : String = formatEthValue( valueInWei, Denominations.Ether  )

  private val MarkupFormatPattern = "#,##0.00"

  def formatGasPriceTweak( tweak : jsonrpc.Invoker.MarkupOrOverride ) : String = {
    val nf = new java.text.DecimalFormat(MarkupFormatPattern)
    tweak match {
      case jsonrpc.Invoker.Markup( fraction, cap, floor ) => {
        val capFloorPart = {
          ( cap, floor ) match {
            case ( Some(c), Some(fl) ) => s"subject to a cap of ${formatInGWei(c)} and a floor of ${formatInGWei(fl)}"
            case ( Some(c), None     ) => s"subject to a cap of ${formatInGWei(c)}"
            case ( None,    Some(fl) ) => s"subject to a floor of ${formatInGWei(fl)}"
            case ( None,    None     ) =>  "not subject to any cap or floor"
          }
        }
        val markupPart = if (fraction != 0) s" plus a markup of ${nf.format(fraction)} (${nf.format(fraction * 100)}%)" else ""
        s"default gas price${markupPart}, ${capFloorPart}" 
      }
      case jsonrpc.Invoker.Override( valueInWei ) => {
        s"a fixed gas price of ${formatInGWei(valueInWei)}"
      }
    }
  }

  def formatGasLimitTweak( tweak : jsonrpc.Invoker.MarkupOrOverride ) : String = {
    val nf = new java.text.DecimalFormat(MarkupFormatPattern)
    tweak match {
      case jsonrpc.Invoker.Markup( fraction, cap, floor ) => {
        val capFloorPart = {
          ( cap, floor ) match {
            case ( Some(c), Some(fl) ) => s"subject to a cap of ${c} gas and a floor of ${fl} gas"
            case ( Some(c), None     ) => s"subject to a cap of ${c} gas"
            case ( None,    Some(fl) ) => s"subject to a floor of ${fl} gas"
            case ( None,    None     ) =>  "not subject to any cap or floor"
          }
        }
        val markupPart = if (fraction != 0) s" plus a markup of ${nf.format(fraction)} (${nf.format(fraction * 100)}%)" else ""
        s"estimated gas cost${markupPart}, ${capFloorPart}" 
      }
      case jsonrpc.Invoker.Override( gas ) => {
        s"a fixed limit of ${gas} gas"
      }
    }
  }

  def hexString( bytes : Seq[Byte]    )   = s"0x${bytes.hex}"
  def hexString( bytes : Array[Byte]  )   = s"0x${bytes.hex}"
  def hexString( address : EthAddress )   = s"0x${address.hex}"
  def hexString( hash : EthHash )         = s"0x${hash.hex}"
  def hexString( bytes : ByteSeqExact32 ) = s"0x${bytes.widen.hex}"

  // some formatting functions for ascii tables
  def emptyOrHex( str : String ) = if (str == null) "" else s"0x$str"
  def blankNull( str : String ) = if (str == null) "" else str
  def dashspan( len : Int ) = (0 until len).map(_ => "-").mkString

  def mbWithNonceClause( nonceOverride : Option[Unsigned256] ) = nonceOverride.fold("")( n => s"with nonce ${n.widen} " )

  def commaSepAliasesForAddress( chainId : Int, address : EthAddress ) : Failable[Option[String]] = {
    shoebox.AddressAliasManager.findAddressAliasesByAddress( chainId, address ).map( seq => if ( seq.isEmpty ) None else Some( seq.mkString( "['","','", "']" ) ) )
  }
  def leftwardAliasesArrowOrEmpty( chainId : Int, address : EthAddress ) : Failable[String] = {
    commaSepAliasesForAddress( chainId, address ).map( _.fold("")( aliasesStr => s" <-- ${aliasesStr}" ) )
  }
  def jointAliasesPartAddressAbi( chainId : Int, address : EthAddress, abiHash : EthHash ) : Option[String] = {
    val addressAliases = shoebox.AddressAliasManager.findAddressAliasesByAddress( chainId, address ).assert
    val abiAliases = shoebox.AbiAliasHashManager.findAbiAliasesByAbiHash( chainId, abiHash ).assert
    def aliasList( s : Seq[String] ) : String = s.mkString("['","','","']")
    def pAbi( abiAlias : String ) : String = "abi:" + abiAlias
    def abiAliasList( s : Seq[String] ) : String = aliasList( s.map( pAbi ) )
      ( addressAliases, abiAliases ) match {
      case ( Seq(),          Seq() )           => None
      case ( Seq( alias ),   Seq() )           => Some( s"address alias: '${alias}'" )
      case ( addressAliases, Seq() )           => Some( s"address aliases: ${aliasList(addressAliases)}" )
      case ( Seq(),          Seq( abiAlias ) ) => Some( s"abi alias: '${pAbi(abiAlias)}'" )
      case ( Seq( alias ),   Seq( abiAlias ) ) => Some( s"address alias: '${alias}', abi alias: '${pAbi(abiAlias)}'" )
      case ( addressAliases, Seq( abiAlias ) ) => Some( s"address aliases: ${aliasList(addressAliases)}, abi alias: '${pAbi(abiAlias)}'" )
      case ( Seq(),          abiAliases )      => Some( s"abi aliases: ${abiAliasList(abiAliases)}" )
      case ( Seq( alias ),   abiAliases )      => Some( s"address alias: '${alias}', abi aliases: ${abiAliasList(abiAliases)}" )
      case ( addressAliases, abiAliases )      => Some( s"address aliases: ${aliasList(addressAliases)}, abi aliases: ${abiAliasList(abiAliases)}" )
    }
  }

  private def _verboseAddress( chainId : Int, address : EthAddress, xform : String => String ) : String = {
    val simple = xform(s"0x${address.hex}")
    if ( chainId >= 0 ) {
      val aliasesPart = commaSepAliasesForAddress( chainId, address ).fold( _ => "" )( _.fold("")( str => s"with aliases $str " ) )
      s"${simple} (${aliasesPart}on chain with ID $chainId)"
    }
    else {
      simple
    }
  }

  def verboseAddress( chainId : Int, address : EthAddress, ticks : Boolean = true ) : String = {
    if (ticks) _verboseAddress( chainId, address, s => s"'${s}'" ) else _verboseAddress( chainId, address, identity )
  }

  def displayTransactionSignatureRequest(
    log : sbt.Logger,
    chainId : Int,
    chainAbiOverrides : immutable.Map[EthAddress,jsonrpc.Abi],
    priceFeed : PriceFeed,
    currencyCode : String,
    txn : EthTransaction,
    proposedSender : EthAddress,
    signerDescription : Option[String] = None
  ) : Unit = {
    _displayTransactionRequest( "==> T R A N S A C T I O N   S I G N A T U R E   R E Q U E S T" )(log, chainId, chainAbiOverrides, priceFeed, currencyCode, txn, proposedSender, signerDescription )
  }

  def displayTransactionSubmissionRequest(
    log : sbt.Logger,
    chainId : Int,
    chainAbiOverrides : immutable.Map[EthAddress,jsonrpc.Abi],
    priceFeed : PriceFeed,
    currencyCode : String,
    txn : EthTransaction,
    proposedSender : EthAddress,
    signerDescription : Option[String] = None
  ) : Unit = {
    _displayTransactionRequest( "==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T" )(log, chainId, chainAbiOverrides, priceFeed, currencyCode, txn, proposedSender, signerDescription )
  }

  private val PricePattern = "#,##0.00"

  private def _displayTransactionRequest( titleLine : String )(
    log               : sbt.Logger,
    chainId           : Int,
    chainAbiOverrides : immutable.Map[EthAddress,jsonrpc.Abi],
    priceFeed         : PriceFeed,
    currencyCode      : String,
    txn               : EthTransaction,
    proposedSender    : EthAddress,
    signerDescription : Option[String]
  ) : Unit = {

    // val abiOverrides = abiOverridesForChain( chainId )

    val pf = new java.text.DecimalFormat(MarkupFormatPattern)

    val gasPrice   = txn.gasPrice.widen
    val gasLimit   = txn.gasLimit.widen
    val valueInWei = txn.value.widen

    val nonce = txn.nonce.widen

    syncOut {
      println()
      println( titleLine )
      println( "==>" )

      signerDescription.foreach { desc =>
        println(   s"==> Signer: ${desc}" )
        println(    "==>")
      }
    }

    txn match {
      case msg : EthTransaction.Message => {
        syncOut {
          println(  """==> The transaction would be a message with...""" )
          println( s"""==>   To:    ${verboseAddress(chainId, msg.to, ticks=false)}""" )
          println( s"""==>   From:  ${verboseAddress(chainId, proposedSender, ticks=false)}""" )
          println( s"""==>   Data:  ${if (msg.data.length > 0) hexString(msg.data) else "None"}""" )
          println( s"""==>   Value: ${EthValue(msg.value.widen, Denominations.Ether).denominated} ether""" )
        }

        syncOut {
          txn match {
            case signed : EthTransaction.Signed => {
              println(  "==>" )
              signed.signature match {
                case withId : EthSignature.WithChainId => {
                  val sigChainId = withId.chainId.value.widen.toInt // XXX: ugh.. we should probably modify all our ChainId stuff to be in terms of BigInt or UnsignedBigInt, rather than presuming Int...
                  if (chainId == sigChainId ) {
                    println( s"==> The transaction is signed with Chain ID ${chainId} (which correctly matches the current session's 'ethNodeChainId')." )
                  }
                  else {
                    println( s"==> WARNING: The transaction is signed with Chain ID ${sigChainId}, which does not match the current session's 'ethNodeChainId' of ${chainId}.")
                    println( s"==>          If it is submitted within the current session, the transaction may fail." )
                  }
                }
                case withoutId : EthSignature.Basic => {
                  println( s"""==> WARNING: The transaction is signed with no embedded Chain ID. It could be successfully submitted on multiple chains, potentially as a result of "replay attacks".""" )
                }
              }
            }
            case unsigned : EthTransaction.Unsigned => /* skip section */
          }
        }
          
        if (msg.data.nonEmpty) { // don't try to interpret pure ETH trasfers as ABI calls
          try {
            val abiLookup = abiLookupForAddress( chainId, msg.to, chainAbiOverrides )
            abiLookup.resolveAbi(Some(log)) match {
              case Some(abi) => {
                syncOut {
                  val ( fcn, values ) = ethabi.decodeFunctionCall( abi, msg.data ).assert
                  println(  "==>" )
                  println( s"==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call..." )
                  println( s"==>   Function called: ${ethabi.signatureForAbiFunction(fcn)}" )
                  (values.zip( Stream.from(1) )).foreach { case (value, index) =>
                    println( s"==>     Arg ${index} [name=${value.parameter.name}, type=${value.parameter.`type`}]: ${value.stringRep}" )
                  }
                }
              }
              case None => {
                syncOut {
                  println(  "==>" )
                  println( s"==> !!! Any ABI is associated with the destination address is currently unknown, so we cannot decode the message data as a method call !!!" )
                }
              }
            }
          }
          catch {
            case e : Exception => {
              val msg = s"An Exception occurred while trying to interpret this method with an ABI as a function call. Skipping: ${e}"
              log.warn( msg )
              DEBUG.log( msg, e )
            }
          }
        }
      }
      case cc : EthTransaction.ContractCreation => {
        syncOut {
          println(  """==> The transaction would be a contract creation with...""" )
          println( s"""==>   From:  ${verboseAddress(chainId, proposedSender, ticks=false)}""" )
          println( s"""==>   Init:  ${if (cc.init.length > 0) hexString(cc.init) else "None"}""" )
          println( s"""==>   Value: ${EthValue(cc.value.widen, Denominations.Ether).denominated} Ether""" )
        }
      }
    }

    syncOut {
      println("==>")
      println( s"==> The nonce of the transaction would be ${nonce}." )
      println("==>")

      println( s"==> $$$$$$ The transaction you have requested could use up to ${gasLimit} units of gas." )

      val mbEthPrice = priceFeed.ethPriceInCurrency( chainId, currencyCode, forceRefresh = true )

      val gweiPerGas = Denominations.GWei.fromWei(gasPrice)
      val gasCostInWei = gasLimit * gasPrice
      val gasCostInEth = Denominations.Ether.fromWei( gasCostInWei )
      val gasCostMessage = {
        val sb = new StringBuilder
        sb.append( s"==> $$$$$$ You would pay ${ gweiPerGas } gwei for each unit of gas, for a maximum cost of ${ gasCostInEth } ether.${LineSep}" )
        mbEthPrice match {
          case Some( PriceFeed.Datum( ethPrice, timestamp ) ) => {
            sb.append( s"==> $$$$$$ This is worth ${ pf.format(gasCostInEth * ethPrice) } ${currencyCode} (according to ${priceFeed.source} at ${formatTime( timestamp )})." )
          }
          case None => {
            sb.append( s"==> $$$$$$ (No ${currencyCode} value could be determined for ETH on chain with ID ${chainId} from ${priceFeed.source})." )
          }
        }
        sb.toString
      }
      println( gasCostMessage )

      if ( valueInWei != 0 ) {
        val xferInEth = Denominations.Ether.fromWei( valueInWei )
        val maxTotalCostInEth = xferInEth + gasCostInEth
        print( s"==> $$$$$$ You would also send ${xferInEth} ether" )
        mbEthPrice match {
          case Some( PriceFeed.Datum( ethPrice, timestamp ) ) => {
            println( s" (${ pf.format(xferInEth * ethPrice) } ${currencyCode}), for a maximum total cost of ${ maxTotalCostInEth } ether (${ pf.format(maxTotalCostInEth * ethPrice) } ${currencyCode})." )
          }
          case None => {
            println( s"for a maximum total cost of ${ maxTotalCostInEth } ether." )
          }
        }
      }
      signerDescription.foreach { desc =>
        println(  "==>" )
        println( s"==> Signer: ${desc}" )
      }
      println()
    }
  }
}
