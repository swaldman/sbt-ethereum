package com.mchange.sc.v1.sbtethereum.util

import com.mchange.sc.v1.sbtethereum._
import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.EthAddress
import com.mchange.sc.v1.consuela.ethereum.{ethabi,jsonrpc,EthAddress}
import jsonrpc.Invoker
import jsonrpc.Client.BlockNumber

import scala.collection._
import scala.concurrent.Future

object Eip1967 {
  final object Slot {
    val Logic  = "0x360894a13ba1a3210667c828492db98dca3e2076cc3735a920a3ca505d382bbc".decodeHex.toUnsignedBigInt
    val Beacon = "0xa3f0ad74e5423aebfd80d3ef4346578335a9a72aeaee59ff6cb3582b35133d50".decodeHex.toUnsignedBigInt
    val Admin  = "0xb53127684a568b3173ae13b9f8a6016e243e63b6e8ee1178d6a717850b5d6103".decodeHex.toUnsignedBigInt
  }

  val BeaconImplementationFunction = {
    val inputs  = immutable.Seq.empty
    val outputs = immutable.Seq( jsonrpc.Abi.Function.Parameter( "", "address", None ) )
    jsonrpc.Abi.Function("implementation", inputs, outputs, "view" )
  }

  val BeaconImplementationIdentifier = ethabi.identifierForAbiFunction(BeaconImplementationFunction)

  val BeaconImplementationCallData = BeaconImplementationIdentifier

  private [sbtethereum]
  def lookupBeaconImplementation( beaconContractAddress : EthAddress )( implicit ic : Invoker.Context ) : Future[EthAddress] = {
    implicit val ec = ic.econtext
    Invoker.constant.sendMessage( EthAddress.Zero, beaconContractAddress, Zero256, BeaconImplementationCallData ).map { ret =>
      ethabi.decodeReturnValuesForFunction( ret, BeaconImplementationFunction ).assert.head.value.asInstanceOf[EthAddress]
    }
  }

  private [sbtethereum]
  def findProxied( proxyAddress : EthAddress )( implicit ic : jsonrpc.Invoker.Context ) : Future[EthAddress] = jsonrpc.Invoker.withClient { (client, url) =>
    implicit val ec = ic.econtext
    client.eth.getStorageAt( proxyAddress, Slot.Logic, BlockNumber.Latest ).flatMap { logic =>
      if (logic != 0) Future.successful( EthAddress( logic.unsignedBytes(20) ) )
      else {
        client.eth.getStorageAt( proxyAddress, Slot.Beacon, BlockNumber.Latest ).flatMap { beacon =>
          if (beacon != 0) lookupBeaconImplementation( EthAddress( beacon.unsignedBytes(20) ) )
          else Future.failed( new NotEip1967TransparentProxyException( proxyAddress ) )
        }
      }
    }
  }
}
