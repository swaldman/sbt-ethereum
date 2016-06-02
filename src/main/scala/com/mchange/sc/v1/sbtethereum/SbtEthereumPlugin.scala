package com.mchange.sc.v1.sbtethereum

import sbt._

object SbtEthereumPlugin extends AutoPlugin {

  object autoImport {
    val ethJsonRpcVersion = settingKey[String]("Version of Ethereum's JSON-RPC spec the build should work with.")
    val ethJsonRpcUrl     = settingKey[String]("URL of the Ethereum JSON-RPC service build should work with")

    val compileSolidity = taskKey[Unit]("Compiles solidity files")

    lazy val ethDefaults : Seq[sbt.Def.Setting[_]] = Seq(
      ethJsonRpcVersion := "2.0",
      ethJsonRpcUrl     := "http://localhost:8545"
    )
  }

  import autoImport._

  override def trigger = allRequirements

  override val projectSettings = ethDefaults
}
