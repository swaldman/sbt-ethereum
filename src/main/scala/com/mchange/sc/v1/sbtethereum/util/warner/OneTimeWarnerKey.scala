package com.mchange.sc.v1.sbtethereum.util.warner

object OneTimeWarnerKey {
  final object NodeChainIdInBuild extends OneTimeWarnerKey
  final object NodeUrlInBuild extends OneTimeWarnerKey
  final object AddressSenderInBuild  extends OneTimeWarnerKey
  final object EtherscanApiKeyInBuild  extends OneTimeWarnerKey

  final object EthDefaultNodeSupportedOnlyForMainet extends OneTimeWarnerKey
  final object UsingUnreliableBackstopNodeUrl extends OneTimeWarnerKey
}
sealed trait OneTimeWarnerKey

