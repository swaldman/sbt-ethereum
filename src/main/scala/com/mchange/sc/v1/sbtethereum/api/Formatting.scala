package com.mchange.sc.v1.sbtethereum.api

import com.mchange.sc.v1.sbtethereum.util.{Formatting => UF}

import com.mchange.sc.v1.consuela.ethereum.{EthAddress,EthHash}

import java.time.Instant

object Formatting {
  def defaultFormatInstant( instant : Instant ) : String = UF.formatInstant( instant )
  def defaultFormatInstant( millis  : Long    ) : String = UF.formatInstant( millis )
  def defaultFormatTime   ( millis  : Long    ) : String = UF.formatTime( millis )

  def formatHex( bytes : Seq[Byte]    ) = UF.hexString( bytes   )
  def formatHex( bytes : Array[Byte]  ) = UF.hexString( bytes   )
  def formatHex( address : EthAddress ) = UF.hexString( address )
  def formatHex( hash : EthHash       ) = UF.hexString( hash    )

  def verboseAddress( chainId : Int, address : EthAddress, ticks : Boolean = true ) : String = UF.verboseAddress( chainId, address, ticks )
}
