package com.mchange.sc.v1.sbtethereum.shoebox

import java.time.{Instant,ZoneId}
import java.time.format.DateTimeFormatter

object InFilenameTimestamp {
  private val Pattern   = "yyyyMMdd'T'HH'h'mm'm'ss's'SSS'ms'zzz"
  private val Formatter = DateTimeFormatter.ofPattern( Pattern ).withZone( ZoneId.systemDefault() )

  def generate( instant : Instant = Instant.now() ) : String = Formatter.format( instant )

  def parse( s : String ) : Instant = Instant.from( Formatter.parse( s ) ) 
}
