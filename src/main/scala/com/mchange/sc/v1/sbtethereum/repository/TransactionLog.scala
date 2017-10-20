package com.mchange.sc.v1.sbtethereum.repository

import java.io.{BufferedOutputStream, File, FileOutputStream, OutputStreamWriter, PrintWriter}
import java.util.Date
import java.text.SimpleDateFormat
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v2.failable._
import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{EthHash, EthTransaction}
import com.mchange.sc.v2.yinyang.YinYang
import scala.io.Codec

object TransactionLog {
  private val TimestampPattern = "yyyy-MM-dd'T'HH-mm-ssZ"

  lazy val File: YinYang[Fail, File] = Directory.map(dir => new java.io.File(dir, "transaction-log") )

  case class Entry( timestamp : Date, txn : EthTransaction.Signed, transactionHash : EthHash ) {
    override def toString: String = {
      val ( ttype, payloadKey, payload ) = txn match {
        case m  : EthTransaction.Signed.Message          => ("Message", "data", m.data)
        case cc : EthTransaction.Signed.ContractCreation => ("ContractCreation", "init", cc.init)
      }
      val df = new SimpleDateFormat(TimestampPattern)
      val ts = df.format( timestamp )
      val first  = s"$ts:type=$ttype,nonce=${ txn.nonce.widen },gasPrice=${ txn.gasPrice.widen },gasLimit=${ txn.gasLimit.widen },value=${ txn.value.widen },"
      val middle = if ( payload.nonEmpty ) s"$payloadKey=${payload.hex}," else ""
      val last   = s"v=${txn.v.widen},r=${txn.r.widen},s=${txn.s.widen},transactionHash=${transactionHash.bytes.hex}"
      first + middle + last
    }
  }

  def logTransaction( transaction : EthTransaction.Signed, transactionHash : EthHash ) : Unit = {
    File.flatMap { file =>
      Failable {
        val entry = TransactionLog.Entry( new Date(), transaction, transactionHash )
        borrow( new PrintWriter( new OutputStreamWriter( new BufferedOutputStream( new FileOutputStream( file, true ) ), Codec.UTF8.charSet ) ) )( _.println( entry ) )
      }
    }.get // Unit or vomit Exception
  }
}

