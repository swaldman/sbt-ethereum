package com.mchange.sc.v1.sbtethereum.repository

import java.io.{BufferedOutputStream, File, FileOutputStream, OutputStreamWriter, PrintWriter}
import java.util.Date
import java.text.SimpleDateFormat
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v2.failable._
import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{EthHash, EthTransaction}
import scala.io.Codec

abstract class RepositoryLog[T]( logName : String ) {
  private val TimestampPattern = "yyyy-MM-dd'T'HH-mm-ssZ"

  lazy val File : Failable[File] = Directory.map(dir => new java.io.File(dir, logName) )

  def toLine( timestamp : String, t : T ) : String

  private def nowTimestamp : String = {
    val df = new SimpleDateFormat(TimestampPattern)
    df.format( new Date() )
  }

  def log( t : T ) : Failable[Unit] = this.synchronized {
    File.flatMap { file =>
      Failable {
        borrow( new PrintWriter( new OutputStreamWriter( new BufferedOutputStream( new FileOutputStream( file, true ) ), Codec.UTF8.charSet ) ) )( _.println( toLine( nowTimestamp, t ) ) )
      }
    }
  }
}

