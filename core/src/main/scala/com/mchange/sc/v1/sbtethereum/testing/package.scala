package com.mchange.sc.v1.sbtethereum

import com.mchange.sc.v1.consuela.ethereum.{stub, EthPrivateKey}

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

    /**
      * Some accounts to use for testing
      */
    val TestPrivateKey : IndexedSeq[EthPrivateKey] = stub.Test.PrivateKey
    val TestSender     : IndexedSeq[stub.Sender]   = stub.Test.Sender

    /**
      * This is just a conventional account to use as an Ether fountain in testing environments
      *
      * Corresponds to eth address 0xaba220742442621625bb1160961d2cfcb64c7682
      */
    final object Faucet {
      val PrivateKey = TestPrivateKey(0)
      val Sender  = TestSender(0)
      val Address = Sender.address
    }

    final object Ganache {
      val Executable = "ganache-cli"
      val CommandParsed = immutable.Seq( Executable, "--port", EthJsonRpc.Port.toString, s"--account=0x${ Faucet.PrivateKey.hex },$MaxWei" )
      val Command: String = CommandParsed.mkString(" ")
    }
  }
}
