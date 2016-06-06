package com.mchange.sc.v1.sbtethereum

import sbt._
import sbt.Keys._
import plugins.JvmPlugin

object SbtEthereumPlugin extends AutoPlugin {

  object autoImport {
    val ethJsonRpcVersion = settingKey[String]("Version of Ethereum's JSON-RPC spec the build should work with.")
    val ethJsonRpcUrl     = settingKey[String]("URL of the Ethereum JSON-RPC service build should work with")

    val soliditySource      = settingKey[File]("Solidity source code directory")
    val solidityDestination = settingKey[File]("Location for compiled solidity code and metadata")

    val compileSolidity = taskKey[Unit]("Compiles solidity files")

    lazy val ethDefaults : Seq[sbt.Def.Setting[_]] = Seq(
      ethJsonRpcVersion := "2.0",
      ethJsonRpcUrl     := "http://localhost:8545",

      soliditySource in Compile      := (sourceDirectory in Compile).value / "solidity",
      solidityDestination in Compile := target.value / "solidity",

      compileSolidity in Compile := {
        val log            = streams.value.log
        val jsonRpcUrl     = ethJsonRpcUrl.value

        val solSource      = (soliditySource in Compile).value
        val solDestination = (solidityDestination in Compile).value

        // XXX: provisionally, for now... but what sort of ExecutioContext would be best when?
        import scala.concurrent.ExecutionContext.Implicits.global

        doCompileSolidity( log, jsonRpcUrl, solSource, solDestination )
      },

      compile in Compile := {
        val dummy = (compileSolidity in Compile).value;
        (compile in Compile).value
      }
    )
  }


  import autoImport._

  // very important to ensure the ordering of settings,
  // so that compile actually gets overridden
  override def requires = JvmPlugin

  override def trigger = allRequirements

  override val projectSettings = ethDefaults
}
