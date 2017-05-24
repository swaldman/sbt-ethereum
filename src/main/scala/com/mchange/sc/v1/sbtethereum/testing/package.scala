package com.mchange.sc.v1.sbtethereum

import com.mchange.sc.v1.consuela.ethereum.{EthKeyPair,EthPrivateKey}

import com.mchange.sc.v1.consuela.ethereum.specification.Types.Unsigned256

package object testing {
  val MaxWei   = Unsigned256.MaxValueExclusive - 1 // we want this to be an inclusive max

  final object Default {
    val EthJsonRpcPort = 58545 // conventional default, for testing

    /**
      * This is just a conventional account to use as an Ether fountain in testing environments
      *
      * Corresponds to eth address 0xaba220742442621625bb1160961d2cfcb64c7682
      */
    val Faucet = EthKeyPair( EthPrivateKey( BigInt( 0x7e57 ) ) )

    val TestRpcCommand = s"""testrpc --port ${EthJsonRpcPort} --account="0x${Faucet.pvt.hex},${MaxWei}""""

    val GasMarkup = 0.2
    val GasPriceMarkup = 0
  }
}
