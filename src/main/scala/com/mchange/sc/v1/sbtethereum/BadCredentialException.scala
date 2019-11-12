package com.mchange.sc.v1.sbtethereum

import com.mchange.sc.v1.consuela.ethereum.EthAddress

import util.Formatting.hexString

object BadCredentialException {
  private def clause( mbAddress : Option[EthAddress] ) = mbAddress.fold("")( addr => s" for address ${hexString(addr)}" )
}

import BadCredentialException._

class BadCredentialException( mbAddress : Option[EthAddress], cause : Throwable )
    extends SbtEthereumException( s"The credential provided failed as a passcode and could not be interpreted as a hex private key${clause(mbAddress)}.", cause ) {
  // this.setStackTrace( Array.empty[StackTraceElement] )

  def this( address : EthAddress, cause : Throwable = null ) = this( Some( address ), cause )

  def this() = this( None, null )
}
