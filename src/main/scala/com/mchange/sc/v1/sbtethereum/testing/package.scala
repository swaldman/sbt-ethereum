package com.mchange.sc.v1.sbtethereum

import com.mchange.sc.v1.consuela.ethereum.{EthKeyPair,EthPrivateKey}

import com.mchange.sc.v1.consuela.ethereum.specification.Types.Unsigned256

import scala.collection._

package object testing {
  val MaxWei: BigInt = Unsigned256.MaxValueExclusive - 1 // we want this to be an inclusive max


  final object Default {
    final object EthJsonRpc {
      val Host = "localhost"

      val Port = 58545 // conventional default, for testing

      val Url = s"http://$Host:$Port"
    }

    final object Ganache {
      val Executable = "ganache-cli"
      val CommandParsed = immutable.Seq( Executable, "--port", EthJsonRpc.Port.toString, s"--account=0x${ Faucet.pvt.hex },$MaxWei" )
      val Command: String = CommandParsed.mkString(" ")
    }

    /**
      * This is just a conventional account to use as an Ether fountain in testing environments
      *
      * Corresponds to eth address 0xaba220742442621625bb1160961d2cfcb64c7682
      */
    val Faucet = EthKeyPair( EthPrivateKey( BigInt( 0x7e57 ) ) )
  }
}
