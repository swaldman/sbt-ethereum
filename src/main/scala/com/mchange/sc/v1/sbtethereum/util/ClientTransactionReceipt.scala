package com.mchange.sc.v1.sbtethereum.util

import com.mchange.sc.v1.sbtethereum._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{jsonrpc, EthHash, EthLogEntry}
import jsonrpc.Client
import com.mchange.sc.v1.consuela.ethereum.ethabi._
import com.mchange.sc.v1.consuela.ethereum.specification.Types.Unsigned256

import com.mchange.sc.v3.failable._

import scala.collection._
import scala.concurrent.duration.Duration

import Formatting._

private [sbtethereum]
object ClientTransactionReceipt {
  private def decodeStatus( status : Option[Unsigned256] ) : String = status.fold( "Unknown" ){ swrapped =>
    swrapped match {
      case Zero256 => "FAILED"
      case One256  => "SUCCEEDED"
      case _       => s"Unexpected status ${swrapped.widen}"
    }
  }

  def prettyClientTransactionReceipt( mbabi : Option[jsonrpc.Abi], ctr : Client.TransactionReceipt ) : String = {
    val f_events = {
      val seq_f_events = {
        mbabi.fold( immutable.Seq.empty[Failable[SolidityEvent]] ){ abi =>
          val interpretor = SolidityEvent.Interpretor( abi )
          ctr.logs map { interpretor.interpret(_) }
        }
      }
      Failable.sequence( seq_f_events )
    }

    def decoded( d : Decoded ) : String = {
      d match {
        case dv : Decoded.Value => s"${dv.parameter.name} (of type ${dv.parameter.`type`}): ${dv.stringRep}"
        case dh : Decoded.Hash  => s"${dh.parameter.name} (of type ${dh.parameter.`type`}), whose value hashes to ${hexString(dh.hash)}"
      }
    }

    def indentedNamedSolidityEvent( num : Int, named : SolidityEvent.Named, indent : Int ) : String = {
      val prespaces = " " * indent

      val numStr = s"${num} => "

      val fullspaces = prespaces + (" " * numStr.length)

      val sb = new StringBuilder()

      sb.append( s"${prespaces}${numStr}${named.name} [source=${hexString(named.address)}] (${LineSep}" )
      val len = named.inputs.length
      (0 until len).foreach { i =>
        sb.append( s"${fullspaces}  ${decoded( named.inputs(i) )}" )
        if ( i != len - 1 ) sb.append(',')
        sb.append( LineSep )
      }
      sb.append( s"${fullspaces})" )

      sb.toString
    }

    def indentedAnonymousSolidityEvent( num : Int, anon : SolidityEvent.Anonymous, indent : Int ) : String = {
      val prespaces = " " * indent

      s"${prespaces}${num} => Anonymous Event [source=${hexString(anon.address)}]"
    }

    def indentedSolidityEvent( num : Int, evt : SolidityEvent, indent : Int ) : String = {
      evt match {
        case named : SolidityEvent.Named => indentedNamedSolidityEvent( num, named, indent )
        case anon  : SolidityEvent.Anonymous => indentedAnonymousSolidityEvent( num, anon, indent )
      }
    }

    def indentedEvents( events : immutable.Seq[SolidityEvent], indent : Int ) : String = {
      val sb = new StringBuilder()
      val len = events.length
      (0 until len).foreach { i =>
        sb.append( indentedSolidityEvent( i, events(i), indent ) )
        if ( i != len-1 ) {
          sb.append(',')
          sb.append( LineSep )
        }
      }
      sb.toString
    }


    def indentedData( data : immutable.Seq[Byte], indent : Int ) : String = {
      val prespaces = " " * indent

      data.grouped(32).map( rowBytes => s"${prespaces}${rowBytes.hex}" ).mkString(s"${LineSep}")
    }

    def indentedLog( num : Int, log : EthLogEntry, indent : Int ) : String = {
      val prespaces = " " * indent
      val indent1   = prespaces + (" " * 5) 
      val indent2   = prespaces + (" " * 7)
      val indent3   = indent2   + (" " * 2)

      val sb = new StringBuilder()
      sb.append( prespaces + s"${num} => EthLogEntry [source=${hexString(log.address)}] (${LineSep}" )
      sb.append( indent2 + s"topics=[${LineSep}" )

      def appendTopic( topic : EthLogEntry.Topic, last : Boolean ) = sb.append( indent3 +  s"""${hexString(topic)}${if (!last) "," else ""}${LineSep}""" )

      val len = log.topics.length
      (0 until len).foreach { index =>
        appendTopic( log.topics(index), index == len - 1 )
      }

      sb.append( indent2 + s"],${LineSep}" )
      sb.append( indent2 + s"data=${indentedData(log.data, indent+12).trim}${LineSep}" )
      sb.append( indent1 +  ")" )

      sb.toString
    }

    def indentedLogs( indent : Int ) : String = {
      val sb = new StringBuilder()
      val len = ctr.logs.length
      (0 until len).foreach { i =>
        sb.append( indentedLog( i, ctr.logs(i), indent ) )
        if ( i != len-1 ) {
          sb.append(',')
          sb.append( LineSep )
        }
      }
      sb.toString
    }

    val withoutEventsStr = {
      s"""|Transaction Receipt:
          |       Transaction Hash:    0x${ctr.transactionHash.hex}
          |       Transaction Index:   ${ctr.transactionIndex.widen}
          |       Transaction Status:  ${ decodeStatus( ctr.status ) }
          |       Block Hash:          0x${ctr.blockHash.hex}
          |       Block Number:        ${ctr.blockNumber.widen}
          |       From:                ${if (ctr.from.isEmpty) "Unknown" else hexString(ctr.from.get)}
          |       To:                  ${if (ctr.to.isEmpty) "Unknown" else hexString(ctr.to.get)}
          |       Cumulative Gas Used: ${ctr.cumulativeGasUsed.widen}
          |       Gas Used:            ${ctr.gasUsed.widen}
          |       Contract Address:    ${ctr.contractAddress.fold("None")( ea => "0x" + ea.hex )}
          |       Logs:                ${if (ctr.logs.isEmpty) "None" else indentedLogs(28).trim}""".stripMargin
    }

    f_events match {
      case Succeeded( events ) => {
        mbabi match {
          case Some( abi )  => withoutEventsStr + LineSep + s"""       Events:              ${if (events.isEmpty) "None" else indentedEvents(events, 28).trim}"""
          case None         => withoutEventsStr + LineSep + s"""       Events:              ${if (ctr.logs.isEmpty) "None" else "<no abi available to interpret logs as events>"}"""
        }
      }
      case oops : Failed[_] => withoutEventsStr + LineSep + s"""       Events:              Something went wrong interpreting events! ${oops}"""
    }
  }

  def prettyPrintEval( log : sbt.Logger, mbabi : Option[jsonrpc.Abi], ctr : Client.TransactionReceipt ) : Client.TransactionReceipt = {
    log.info( prettyClientTransactionReceipt( mbabi, ctr ) )
    ctr
  }

  def prettyPrintEval( log : sbt.Logger, mbabi : Option[jsonrpc.Abi], txnHash : EthHash, timeout : Duration, mbctr : Option[Client.TransactionReceipt] ) : Option[Client.TransactionReceipt] = {
    mbctr match {
      case Some( ctr ) => log.info( prettyClientTransactionReceipt( mbabi, ctr ) )
      case None        => log.warn( s"Failed to mine transaction with hash '${hexString(txnHash)}' within timeout of ${timeout}!" )
    }
    mbctr
  }

  def prettyPrintEval( log : sbt.Logger, mbabi : Option[jsonrpc.Abi], txnHash : EthHash, timeout : Duration, ctr : Client.TransactionReceipt ) : Client.TransactionReceipt = {
    log.info( prettyClientTransactionReceipt( mbabi, ctr ) )
    ctr
  }
}
