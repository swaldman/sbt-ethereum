# ChainId*

Each _sbt-ethereum_ session must be associated with an [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) @ref:[Chain ID](chain_id.md),
which specifies which _Ethereum_ compatible chain the session should interact with.

Nearly everything _sbt-ethereum_ does is contingent upon this Chain ID. [Node URLs](url.md) are assigned and modified on a per-Chain-ID basis (and indeed,
users should define default URLs for every chain they typically interact with via @ref:[`ethNodeUrlDefaultSet`](url.md#ethnodeurldefaultset)).

Users may define a default Chain ID (via @ref:[`ethNodeChainIdDefaultSet`](#ethnodechainidoverrideset), which will be the chain new _sbt-ethereum_ session
interact with, unless a repository hardcodes an alternative via the setting @ref:[`ethcfgNodeChainId`](../../../settings/index.md#ethcfgnodechainid). If no default
is defined (and no hardcoded value or session override occludes it), _sbt-ethereum_ will default to interacting with Chain ID 1, _Ethereum_ mainnet.

However, at any time, users can switch Chain IDs via [`ethNodeChainIdOverrideSet`](#ethnodechainidoverrideset). The [Node URL](url.md) and the set of available
@ref:[address aliases](../address/alias.md) will automatically update with the Chain ID. _sbt-ethereum_ distinguishes deployments by Chain ID, so deployments on
different chains can have different @ref:[ABIs](../contract/abi.md), and _sbt-ethereum_ will automatically make available (in, for example, @ref:[`ethTransactionInvoke`](../transaction/index.md#ethtransactioninvoke))
the methods associated with the currently defined Chain ID.

**Chain IDs and Testing**

In _sbt-ethereum_'s `Test` configuration, the Chain ID defaults to the value `-1`, signalling an ephemeral chain, for which no Chain ID should be embedded in signatures.

Although it is possible to change this behavior for the `Test` configuration (using `Test / ethNodeChainIdDefaultSet` or `Test / ethNodeChainIdOverrideSet`),
it is rare that you would want to.

@@@ note

**Running tests on an ephemeral chain** (like [Ganache](https://truffleframework.com/ganache)) is **quite different from deploying to a "test chain"** (like `ropsten` or `rinkleby`).

**Test chains have unique positive Chain IDs and their deployments are permanent.** _sbt-ethereum_ maintains permanent records of test-chain deployments as it would for any
other permanet deployment.

@@@

### ethNodeChainIdDefaultDrop

### ethNodeChainIdDefaultSet

### ethNodeChainIdDefaultPrint

### ethNodeChainIdOverride

### ethNodeChainIdOverrideDrop

### ethNodeChainIdOverrideSet

### ethNodeChainIdOverridePrint

### ethNodeChainIdPrint

### ethNodeChainId



