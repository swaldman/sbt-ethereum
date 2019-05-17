package com.mchange.sc.v1.sbtethereum.util

import com.mchange.sc.v1.consuela._

import com.mchange.sc.v1.consuela.ethereum.{EthAddress,EthHash}
import com.mchange.sc.v1.consuela.ethereum.specification.Types.ByteSeqExact32

import java.time.{Instant, ZoneId}
import java.time.format.{FormatStyle, DateTimeFormatter}

private [sbtethereum]
object Formatting {
  private val InstantFormatter = DateTimeFormatter.RFC_1123_DATE_TIME.withZone( ZoneId.systemDefault() )
  private val TimeFormatter    = DateTimeFormatter.ofLocalizedTime( FormatStyle.SHORT ).withZone( ZoneId.systemDefault() )

  def formatInstant( instant : Instant ) : String = InstantFormatter.format( instant )

  def formatInstant( l : Long ) : String = formatInstant( Instant.ofEpochMilli( l ) )

  def formatTime( l : Long ) : String = TimeFormatter.format( Instant.ofEpochMilli( l ) )

  def hexString( bytes : Seq[Byte]    )   = s"0x${bytes.hex}"
  def hexString( bytes : Array[Byte]  )   = s"0x${bytes.hex}"
  def hexString( address : EthAddress )   = s"0x${address.hex}"
  def hexString( hash : EthHash )         = s"0x${hash.hex}"
  def hexString( bytes : ByteSeqExact32 ) = s"0x${bytes.widen.hex}"
}
