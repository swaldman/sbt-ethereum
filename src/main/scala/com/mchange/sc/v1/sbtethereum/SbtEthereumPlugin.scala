package com.mchange.sc.v1.sbtethereum

import sbt._
import sbt.Keys._
import sbt.plugins.{JvmPlugin,InteractionServicePlugin}
import sbt.complete.DefaultParsers._
import sbt.InteractionServiceKeys.interactionService

import java.io.{BufferedInputStream,File,FileInputStream}

import play.api.libs.json.Json

import com.mchange.sc.v2.lang.borrow

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum._

import com.mchange.sc.v1.consuela.ethereum.specification.Types.Unsigned256

// XXX: provisionally, for now... but what sort of ExecutionContext would be best when?
import scala.concurrent.ExecutionContext.Implicits.global

object SbtEthereumPlugin extends AutoPlugin {

  private val BufferSize = 4096

  private val ContractNameParser = (Space ~> ID)

  private val Zero256 = Unsigned256( 0 )

  private val ZeroEthAddress = (0 until 40).map(_ => "0").mkString("")

  object autoImport {

    // settings

    val ethAddress = settingKey[String]("The address from which transactions will be sent")

    val ethGasOverrides = settingKey[Map[String,BigInt]]("Map of contract names to gas limits for contract creation transactions, overriding automatic estimates")

    val ethGasMarkup = settingKey[Double]("Fraction by which automatically estimated gas limits will be marked up (if not overridden) in setting contract creation transaction gas limits")

    val ethGasPrice = settingKey[BigInt]("If nonzero, use this gas price (in wei)) rather than the current blockchain default gas price.")

    val ethGethKeystore = settingKey[File]("geth-style keystore directory from which V3 wallets can be loaded")

    val ethJsonRpcVersion = settingKey[String]("Version of Ethereum's JSON-RPC spec the build should work with")

    val ethJsonRpcUrl = settingKey[String]("URL of the Ethereum JSON-RPC service build should work with")

    val ethTargetDir = settingKey[File]("Location in target directory where ethereum artifacts will be placed")

    val ethSoliditySource = settingKey[File]("Solidity source code directory")

    val ethSolidityDestination = settingKey[File]("Location for compiled solidity code and metadata")

    // tasks

    val ethCompileSolidity = taskKey[Unit]("Compiles solidity files")

    val ethDefaultGasPrice = taskKey[BigInt]("Finds the current default gas price")

    val ethDeployOnly = inputKey[EthHash]("Deploys the specified named contract")

    val ethGethWallet = taskKey[Option[wallet.V3]]("Loads a V3 wallet from a geth keystore")

    val ethGetCredential = taskKey[Option[String]]("Requests masked input of a credential (wallet passphrase or hex private key)")

    val ethLoadCompilations = taskKey[Map[String,jsonrpc20.Compilation.Contract]]("Loads compiled solidity contracts")

    val ethLoadContractHex = inputKey[String]("Loads hex contract initialization code for a specified named contract")

    val ethNextNonce = taskKey[BigInt]("Finds the next nonce for the address defined by setting 'ethAddress'")

    // definitions

    lazy val ethDefaults : Seq[sbt.Def.Setting[_]] = Seq(

      ethJsonRpcVersion := "2.0",
      ethJsonRpcUrl     := "http://localhost:8545",

      ethGasMarkup := 0.2,

      ethGasOverrides := Map.empty[String,BigInt],

      ethGasPrice := 0,

      ethGethKeystore := clients.geth.KeyStore.directory.get,

      ethAddress := ZeroEthAddress,

      ethTargetDir in Compile := (target in Compile).value / "ethereum",

      ethSoliditySource in Compile      := (sourceDirectory in Compile).value / "solidity",

      ethSolidityDestination in Compile := (ethTargetDir in Compile).value / "solidity",

      ethCompileSolidity in Compile := {
        val log            = streams.value.log
        val jsonRpcUrl     = ethJsonRpcUrl.value

        val solSource      = (ethSoliditySource in Compile).value
        val solDestination = (ethSolidityDestination in Compile).value

        doCompileSolidity( log, jsonRpcUrl, solSource, solDestination )
      },

      compile in Compile := {
        val dummy = (ethCompileSolidity in Compile).value;
        (compile in Compile).value
      },

      ethDefaultGasPrice := {
        val log            = streams.value.log
        val jsonRpcUrl     = ethJsonRpcUrl.value
        doGetDefaultGasPrice( log, jsonRpcUrl )
      },

      ethNextNonce := {
        val log            = streams.value.log
        val jsonRpcUrl     = ethJsonRpcUrl.value
        val last = doGetTransactionCount( log, jsonRpcUrl, EthAddress( ethAddress.value ), jsonrpc20.Client.BlockNumber.Pending )
        last + 1
      },

      ethLoadCompilations := {
        val dummy = (ethCompileSolidity in Compile).value // ensure compilation has completed

        val dir = (ethSolidityDestination in Compile).value

        def addContracts( addTo : Map[String,jsonrpc20.Compilation.Contract], name : String ) = {
          val next = borrow( new BufferedInputStream( new FileInputStream( new File( dir, name ) ), BufferSize ) )( Json.parse( _ ).as[jsonrpc20.Result.eth.compileSolidity] )
          addTo ++ next.compilations
        }

        dir.list.foldLeft( Map.empty[String,jsonrpc20.Compilation.Contract] )( addContracts )
      },

      ethGethWallet := {
        clients.geth.KeyStore.walletForAddress( ethGethKeystore.value, EthAddress( ethAddress.value ) ).toOption
      },

      ethGetCredential := {
        interactionService.value.readLine("Enter passphrase or hex private key: ", mask = true)
      },

      ethLoadContractHex := {
        val contractName = ContractNameParser.parsed
        val contractsMap = ethLoadCompilations.value

        contractsMap( contractName ).code
      },

      // TODO...
      ethDeployOnly := {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val contractName = ContractNameParser.parsed
        val hex = ethLoadContractHex.evaluated // let this input task recurse to the input of the evaluated input task
        val nextNonce = ethNextNonce.value
        val gasPrice = {
          val egp = ethGasPrice.value
          if ( egp > 0 ) egp else ethDefaultGasPrice.value
        }
        val gas = ethGasOverrides.value.getOrElse( contractName, doEstimateGas( log, jsonRpcUrl, EthAddress( ethAddress.value ), hex.decodeHex.toImmutableSeq, jsonrpc20.Client.BlockNumber.Pending ) )
        val unsigned = EthTransaction.Unsigned.ContractCreation( Unsigned256( nextNonce ), Unsigned256( gasPrice ), Unsigned256( gas ), Zero256, hex.decodeHex.toImmutableSeq )
        val privateKey = findPrivateKey( log, ethGethWallet.value, ethGetCredential.value.get )
        val signed = unsigned.sign( privateKey )
        doSendSignedTransaction( log, jsonRpcUrl, signed )
      }
    )
  }


  import autoImport._

  // very important to ensure the ordering of settings,
  // so that compile actually gets overridden
  override def requires = JvmPlugin && InteractionServicePlugin

  override def trigger = allRequirements

  override val projectSettings = ethDefaults
}
