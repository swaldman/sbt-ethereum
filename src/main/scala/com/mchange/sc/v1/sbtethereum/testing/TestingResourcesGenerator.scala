package com.mchange.sc.v1.sbtethereum.testing

import java.io.StringWriter

import com.mchange.sc.v2.lang.borrow

import com.mchange.v2.io.IndentedWriter

import com.mchange.sc.v1.consuela.ethereum.EthPrivateKey

object TestingResourcesGenerator {
  private val ethereumPackage = "com.mchange.sc.v1.consuela.ethereum"

  def generateTestingResources( objectName : String, testEthJsonRpcUrl : String, fullyQualifiedPackageName : String ) : String = {
    val sw = new StringWriter()

    borrow( new IndentedWriter( sw, "  " ) ) { iw =>
      iw.println( s"package $fullyQualifiedPackageName" )
      iw.println()

      iw.println( s"import $ethereumPackage.EthPrivateKey" )
      iw.println( s"import $ethereumPackage.{jsonrpc, stub, EthAddress, EthHash}" )
      iw.println( s"import $ethereumPackage.specification.Denominations" )
      iw.println()
      iw.println(  "import stub.sol" )
      iw.println()
      iw.println(  "import scala.concurrent.{Await,Future}" )
      iw.println(  "import scala.concurrent.duration._" )
      iw.println()
      iw.println(  "import scala.collection._" )
      iw.println()

      iw println s"object $objectName {"
      iw.upIndent()

      iw.println( s"""val EthJsonRpcUrl : String                          = "$testEthJsonRpcUrl"""" )
      iw.println(  """val TestSender    : IndexedSeq[stub.Sender.Signing] = stub.Test.Sender""" )
      iw.println( s"""val DefaultSender : stub.Sender.Signing             = TestSender(0)""" )
      iw.println(  """val Faucet        : stub.Sender.Signing             = DefaultSender""" )
      iw.println()

      iw.println( "val EntropySource = new java.security.SecureRandom()" )
      iw.println()

      iw.println( "/** A variety of utilities often useful within tests. */" )
      iw.println( "trait Context extends Denominations {" )
      iw.upIndent()

      iw.println( s"implicit val scontext = stub.Context.fromUrl( EthJsonRpcUrl )" )
      iw.println( s"implicit val icontext = scontext.icontext" )
      iw.println( s"implicit val econtext = icontext.econtext" )
      iw.println()
      iw.println( "def createRandomSender() : stub.Sender.Signing = stub.Sender.Basic( EthPrivateKey( EntropySource ) )" )
      iw.println()
      iw.println( "def asyncBalance( address : EthAddress )  : Future[BigInt] = jsonrpc.Invoker.getBalance( address )" )
      iw.println( "def asyncBalance( sender  : stub.Sender ) : Future[BigInt] = sender.asyncBalance()" )
      iw.println()
      iw.println( "def awaitBalance( address : EthAddress )                                      : BigInt = awaitBalance( address, Duration.Inf )"             )
      iw.println( "def awaitBalance( address : EthAddress,  duration : Duration )                : BigInt = Await.result( asyncBalance( address ), duration )" )
      iw.println( "def awaitBalance( sender  : stub.Sender, duration : Duration = Duration.Inf ) : BigInt = sender.awaitBalance( duration )"                   )
      iw.println()
      iw.println( "def asyncFundAddress( address : EthAddress, amountInWei : BigInt ) : Future[EthHash] = Faucet.sendWei( address, sol.UInt256( amountInWei ) )" )
      iw.println( "def awaitFundAddress( address : EthAddress, amountInWei : BigInt, duration : Duration = Duration.Inf ) : EthHash = Await.result( asyncFundAddress( address, amountInWei ), duration )" )
      iw.println()
      iw.println( "def asyncFundSender( sender : stub.Sender, amountInWei : BigInt )  : Future[EthHash] = asyncFundAddress( sender.address, amountInWei )" )
      iw.println( "def awaitFundSender( sender : stub.Sender, amountInWei : BigInt, duration : Duration = Duration.Inf ) : EthHash = awaitFundAddress( sender.address, amountInWei, duration )" )
      iw.println()
      iw.println( "def asyncFundAddresses( destinations : Seq[Tuple2[EthAddress,BigInt]] ) : Future[immutable.Seq[EthHash]] = {" )
      iw.upIndent()
      iw.println( "destinations.foldLeft( Future.successful( immutable.Seq.empty : immutable.Seq[EthHash] ) ){ case ( accum, Tuple2( addr, amt ) ) => accum.flatMap( seq => asyncFundAddress( addr, amt ).map( seq :+ _ ) ) }" )
      iw.downIndent()
      iw.println( "}" )
      iw.println( "def awaitFundAddresses( destinations : Seq[Tuple2[EthAddress,BigInt]] ) : immutable.Seq[EthHash] = awaitFundAddresses( destinations, Duration.Inf )" )
      iw.println( "def awaitFundAddresses( destinations : Seq[Tuple2[EthAddress,BigInt]], duration : Duration ) : immutable.Seq[EthHash] = Await.result( asyncFundAddresses( destinations ), duration )" )
      iw.println()
      iw.println( "def asyncFundSenders( destinations : Seq[Tuple2[stub.Sender,BigInt]] ) : Future[immutable.Seq[EthHash]] = asyncFundAddresses( destinations.map { case ( sender, amt ) => ( sender.address, amt ) } )" )
      iw.println( "def awaitFundSenders( destinations : Seq[Tuple2[stub.Sender,BigInt]], duration : Duration = Duration.Inf ) : immutable.Seq[EthHash] = Await.result( asyncFundSenders( destinations ), duration )" ) 

      iw.downIndent()
      iw.println( "}" )

      iw.println( "trait AutoSender extends Context {" )
      iw.upIndent()

      iw.println( s"implicit val DefaultSender : stub.Sender.Signing = ${objectName}.DefaultSender" )

      iw.downIndent()
      iw.println( "}" )

      iw.println( "final object Implicits extends AutoSender" )

      iw.downIndent()
      iw.println( "}" )
    }

    sw.toString
  }
}
