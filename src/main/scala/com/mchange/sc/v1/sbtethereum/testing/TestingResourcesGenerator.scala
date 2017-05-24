package com.mchange.sc.v1.sbtethereum.testing

import java.io.StringWriter

import com.mchange.sc.v2.lang.borrow

import com.mchange.v2.io.IndentedWriter

import com.mchange.sc.v1.consuela.ethereum.EthPrivateKey

object TestingResourcesGenerator {
  def generateTestingResources( objectName : String, testEthJsonRpcUrl : String, faucetKey : EthPrivateKey, fullyQualifiedPackageName : String ) : String = {
    val sw = new StringWriter()

    borrow( new IndentedWriter( sw ) ) { iw =>
      iw.println( s"package ${fullyQualifiedPackageName}" )
      iw.println()

      iw.println( "import com.mchange.sc.v1.consuela.ethereum.{EthKeyPair,EthPrivateKey}" )
      iw.println( "import com.mchange.sc.v1.consuela.ethereum.ethabi.stub" )
      iw.println( "import com.mchange.sc.v1.consuela.ethereum.jsonrpc20.Invoker" )
      iw.println()

      iw println( s"object ${objectName} {" )
      iw.upIndent()

      iw.println( s"""val EthJsonRpcUrl = "${testEthJsonRpcUrl}"""" )
      iw.println( s"""val Faucet = EthKeyPair( EthPrivateKey( "0x${faucetKey.hex}" ) )""" )
      iw.println()
      iw.println( s"""val DefaultSender = stub.Sender.Basic( Faucet )""" )
      iw.println()

      iw.println( "val EntropySource = new java.security.SecureRandom()" )
      iw.println()

      iw.println( "trait Context extends stub.Utilities {" )
      iw.upIndent()

      iw.println( "implicit val econtext = scala.concurrent.ExecutionContext.Implicits.global" )
      iw.println( s"implicit val icontext = Invoker.Context( EthJsonRpcUrl, Invoker.Markup( ${Default.GasMarkup} ), Invoker.Markup( ${Default.GasPriceMarkup} ) )" )

      iw.println( "def createRandomSender() : stub.Sender = stub.Sender.Basic( EthKeyPair( EthPrivateKey( EntropySource ) ) )" )

      iw.downIndent()
      iw.println( "}" )

      iw.println( "trait AutoSender extends Context {" )
      iw.upIndent()

      iw.println( s"implicit val DefaultSender = ${objectName}.DefaultSender" )

      iw.downIndent()
      iw.println( "}" )

      iw.println( "final object Implicits extends AutoSender" )

      iw.downIndent()
      iw.println( "}" )
    }

    sw.toString()
  }
}
