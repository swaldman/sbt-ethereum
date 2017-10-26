package com.mchange.sc.v1.sbtethereum

// thanks to Mike Slinn for suggesting EthInfuraToken / EthDefaultNode

object ExternalValue {
  private val SystemPropKeys = Map(
    'EthSender      -> "eth.sender",
    'EthInfuraToken -> "eth.infura.token",
    'EthDefaultNode -> "eth.default.node"
  )
  private val EnvironmentVarKeys = Map(
    'EthSender      -> "ETH_SENDER",
    'EthInfuraToken -> "ETH_INFURA_TOKEN",
    'EthDefaultNode -> "ETH_DEFAULT_NODE"
  )

  private def find( keySym : Symbol ) : Option[String] = {
    def mbSysProp = Option( System.getProperty( SystemPropKeys( keySym ) ) )
    def mbEnvVar  = Option( System.getenv( EnvironmentVarKeys( keySym ) ) )
    mbSysProp orElse mbEnvVar
  }

  lazy val EthSender      = find( 'EthSender )
  lazy val EthInfuraToken = find( 'EthInfuraToken )
  lazy val EthDefaultNode = find( 'EthDefaultNode )
}

