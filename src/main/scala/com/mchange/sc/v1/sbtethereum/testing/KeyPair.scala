package com.mchange.sc.v1.sbtethereum.testing

import com.mchange.sc.v1.consuela.ethereum.{EthKeyPair,EthPrivateKey}

object KeyPair {

  /**
    * This is just a conventional account to use as an Ether fountain in testing environments
    * 
    * Corresponds to eth address 0xaba220742442621625bb1160961d2cfcb64c7682
    */ 
  val Fountain = EthKeyPair( EthPrivateKey( BigInt( 0x7e57 ) ) )
}
