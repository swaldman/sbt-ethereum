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

For a list of common Ethereum-like network Chain IDs, see [https://chainid.network](https://chainid.network).

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

@@@ div { .keydesc }

**Usage:**
```
> ethNodeChainIdDefaultDrop
```
Removes any default [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID that may have been set.

**Example:**
```
> ethNodeChainIdDefaultDrop
[info] Default chain ID, previously set to 1, has now been dropped. No default node chain ID is set.
[info] The node chain ID will be determined by hardcoded defaults, unless overridden by an on override.
[info] The session is now active on chain with ID 1.
[info] Refreshing caches.
[success] Total time: 0 s, completed Mar 21, 2019 5:04:53 PM
```

@@@

### ethNodeChainIdDefaultSet

@@@ div { .keydesc }

**Usage:**
```
> ethNodeChainIdDefaultSet <chain-id>
```
Sets a default [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID.

**Example:**
```
> ethNodeChainIdDefaultSet 1
[info] The default chain ID has been set to 1.
[info] The session is now active on chain with ID 1.
[info] Refreshing caches.
[success] Total time: 1 s, completed Mar 21, 2019 5:04:35 PM
```

@@@

### ethNodeChainIdDefaultPrint

@@@ div { .keydesc }

**Usage:**
```
> ethNodeChainIdDefaultPrint
```

Displays any default [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID that may have been set.

**Example:**
```
> ethNodeChainIdDefaultPrint
[info] No default chain ID has been explicitly set. A hardcoded default will be used.
[success] Total time: 0 s, completed Mar 21, 2019 5:09:16 PM
```

@@@

### ethNodeChainIdOverrideDrop

@@@ div { .keydesc }

**Usage:**
```
> ethNodeChainIdOverrideDrop
```
Removes any this-session-only override [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID that may have been set.

**Example:**
```
> ethNodeChainIdOverrideDrop
[info] A chain ID override had been set to 2, but has now been dropped.
[info] The effective chain ID will be determined either by a default set with 'ethNodeChainIdDefaultSet', by an 'ethcfgNodeChainId' set in the build or '.sbt. folder, or an sbt-ethereum hardcoded default.
[info] The session is now active on chain with ID 1.
[info] Refreshing caches.
[success] Total time: 0 s, completed Mar 21, 2019 5:13:39 PM
```

@@@

### ethNodeChainIdOverrideSet

@@@ div { .keydesc }

**Usage:**
```
> ethNodeChainIdOverrideSet <chain-id>
```
Sets a this-session-only override [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID.

**Example:**
```
> ethNodeChainIdOverrideSet 2
[info] The chain ID has been overridden to 2.
[info] The session is now active on chain with ID 2.
[info] Refreshing caches.
[success] Total time: 0 s, completed Mar 21, 2019 5:13:34 PM
```

@@@

### ethNodeChainIdOverridePrint

@@@ div { .keydesc }

**Usage:**
```
> ethNodeChainIdOverridePrint
```

Displays any this-session-only [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID that may have been set.

**Example:**
```
> ethNodeChainIdOverridePrint
[info] The chain ID is overridden to 2.
[success] Total time: 0 s, completed Mar 21, 2019 5:17:18 PM
```

@@@

### ethNodeChainIdOverride

@@@ div { .keydesc }

**Usage:**
```
> ethNodeChainIdOverride <chain-id>
```
This is a shorthand for @ref:[`ethNodeChainIdOverrideSet`](#ethnodechainidoverrideset). Please see that command for more information.

@@@

### ethNodeChainIdPrint


@@@ div { .keydesc }

**Usage:**
```
> ethNodeChainIdPrint
```

Displays the currently effective [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID, and explains how it has been set.

**Example:**
```
> ethNodeChainIdPrint
[info] The current effective node chain ID is '2'.
[info]  + This value has been explicitly set as a session override via 'ethNodeChainIdOverrideSet'.
[success] Total time: 0 s, completed Mar 21, 2019 5:20:42 PM
```

@@@

### ethNodeChainId

_**This task is not indended for direct use on the console**_

Yields the currently effective [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID for use by build tasks and
plugins.

**Example:**
```
> ethNodeChainId
[success] Total time: 0 s, completed Mar 21, 2019 5:24:46 PM
> show ethNodeChainId
[info] 2
[success] Total time: 0 s, completed Mar 21, 2019 5:24:55 PM
```
