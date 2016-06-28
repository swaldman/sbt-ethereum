package com.mchange.sc.v1.sbtethereum

import sbt._
import sbt.Keys._
import sbt.plugins.{JvmPlugin,InteractionServicePlugin}
import sbt.complete.DefaultParsers._
import sbt.InteractionServiceKeys.interactionService

import java.io.{BufferedInputStream,File,FileInputStream}

import play.api.libs.json.Json

import com.mchange.sc.v2.lang.borrow

import com.mchange.sc.v1.consuela.ethereum._

// XXX: provisionally, for now... but what sort of ExecutionContext would be best when?
import scala.concurrent.ExecutionContext.Implicits.global

object SbtEthereumPlugin extends AutoPlugin {

  private val BufferSize = 4096

  val ZeroEthAddress = (0 until 40).map(_ => "0").mkString("")

  object autoImport {
    val ethJsonRpcVersion = settingKey[String]("Version of Ethereum's JSON-RPC spec the build should work with")
    val ethJsonRpcUrl     = settingKey[String]("URL of the Ethereum JSON-RPC service build should work with")

    val ethAddress = settingKey[String]("The address from which transactions will be sent")

    val ethGethKeystore = settingKey[File]("geth-style keystore directory from which V3 wallets can be loaded")

    val ethTargetDir = settingKey[File]("Location in target directory where ethereum artifacts will be placed")

    val soliditySource      = settingKey[File]("Solidity source code directory")
    val solidityDestination = settingKey[File]("Location for compiled solidity code and metadata")

    val compileSolidity = taskKey[Unit]("Compiles solidity files")

    val ethGethWallet = taskKey[Option[wallet.V3]]("Loads a V3 wallet from a geth keystore")

    val ethGetCredential = taskKey[Option[String]]("Requests masked input of a credential (wallet passphrase or hex private key)")

    val ethDefaultGasPrice = taskKey[Long]("Finds the current default gas price")

    val ethNextNonce = taskKey[Long]("Finds the next nonce for the address defined by setting 'ethAddress'")

    val ethLoadCompilations = taskKey[Map[String,jsonrpc20.Compilation.Contract]]("Loads compiled solidity contracts")

    val ethLoadContractHex = inputKey[String]("Loads hex contract initialization code for a specified named contract")

    val ethDeployContract= inputKey[String]("Deploys the specified named contract")

    lazy val ethDefaults : Seq[sbt.Def.Setting[_]] = Seq(

      ethJsonRpcVersion := "2.0",
      ethJsonRpcUrl     := "http://localhost:8545",

      ethGethKeystore := clients.geth.KeyStore.directory.get,

      ethAddress := ZeroEthAddress,

      ethTargetDir in Compile := (target in Compile).value / "ethereum",

      soliditySource in Compile      := (sourceDirectory in Compile).value / "solidity",
      solidityDestination in Compile := (ethTargetDir in Compile).value / "solidity",

      compileSolidity in Compile := {
        val log            = streams.value.log
        val jsonRpcUrl     = ethJsonRpcUrl.value

        val solSource      = (soliditySource in Compile).value
        val solDestination = (solidityDestination in Compile).value

        doCompileSolidity( log, jsonRpcUrl, solSource, solDestination )
      },

      compile in Compile := {
        val dummy = (compileSolidity in Compile).value;
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
        val dummy = (compileSolidity in Compile).value // ensure compilation has completed

        val dir = (solidityDestination in Compile).value

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
        val contractName = (Space ~> ID).parsed
        val contractsMap = ethLoadCompilations.value

        contractsMap( contractName ).code
      },

      // TODO...
      ethDeployContract := {
        val hex = ethLoadContractHex.evaluated
        hex
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
