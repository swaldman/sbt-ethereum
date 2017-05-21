package com.mchange.sc.v1.sbtethereum

import com.mchange.sc.v1.consuela.ethereum.specification.Types.Unsigned256

package object testing {
  val MaxWei   = Unsigned256.MaxValueExclusive - 1 // we want this to be an inclusive max

  final object Default {
    val EthJsonRpcPort = 58545 // conventional default, for testing

    val TestRpcCommand = s"""testrpc --port ${EthJsonRpcPort} --account="0x${KeyPair.Fountain.pvt.hex},${MaxWei}""""
  }
}

