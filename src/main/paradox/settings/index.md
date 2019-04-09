# Settings

### ethcfgAddressSender

@@@ div { .keydesc }

**Type:** `String`

**Default:** _Unset_

A hex _ethereum_ address that will become the default sender for sessions started within this repository, overriding any default sender in the database.

**This setting hardcodes the default sender address for sessions in the repository that sets it. Defining it results in the default sender address appearing in version control.
It's usually better _not_ to commit to or expose a sender in the repository, and use @ref:[`ethAddressSender*`](../tasks/eth/address/sender.md) tasks to manage the identity of your senders.**

@@@

### ethcfgAutoDeployContracts

@@@ div { .keydesc }

**Type:** `Seq[String]`

**Default:** _Unset_

Contracts compiled from this repository that should be autodeployed if no arguments are provided to @ref:[`ethTransactionDeploy`](../tasks/eth/transaction/index.md#ethtransactiondeploy),
or (in the `Test` configuration), within @ref:[ethDebugGanacheTest](../tasks/eth/debug/index.md#ethdebugganachetest). Contracts will be deployed in the order specified.
The same contract can be deployed multiple times (with or without different constructor arguments and/or payments).

Each element of the `Seq` should be the name of the Contract to deploy, optionally with space separated constructor arguments for that contract. If the constructor is payable,
you may optionally also include an amount following the last constructor argument, in the form of a numeric value and a unit, which should be one of `wei`, `gwei`, `szabo`, `finney`, or `ether`.

**This setting is primarily used for smart-contract testing.** See e.g. [swaldman/quick-and-dirty-token-overview](https://github.com/swaldman/quick-and-dirty-token-overview) for a project that uses it.

**Example**
```
Test / ethcfgAutoDeployContracts := Seq( "MintableBurnableERC20", "ProxyableMintableBurnableERC20", "UpgradeabilityProxyFactory", "PausableMintableBurnableERC20" )
```

@@@

### ethcfgBaseCurrencyCode

@@@ div { .keydesc }

**Type:** `String`

**Default:** `"USD"`

ISO 4217 Currency Code for the currency ETH values should be translated to in transaction approval messages.

_Note: Values other than "USD" have not been tested, and probably won't work yet._

@@@

### ethcfgEntropySource                 

@@@ div { .keydesc }

**Type:** `java.security.SecureRandom`

**Default:** An instance of `java.security.SecureRandom` constructed and seeded via that class' default constructor

The source of entropy used where randomness is required, especially generating new keys. You can customize this
with custom initialization or via subclasses of `java.security.SecureRandom`

@@@

### ethcfgGasLimitCap                   

@@@ div { .keydesc }

**Type:** `BigInt`

**Default:** _Unset_

If set, this defines the _maximum_ gas limit _sbt-ethereum_ will allow in a transaction, regardless of its estimated gas cost.

_Usually this should not be set! To control the gas limit precisely, you can use @ref:[`ethTransactionGasLimitOverrideSet`](../tasks/eth/transaction/gas.md#ethtransactiongaslimitoverrideset) in your session._

@@@

### ethcfgGasLimitFloor                 

@@@ div { .keydesc }

**Type:** `BigInt`

**Default:** _Unset_

If set, this defines the _minimum_ gas limit _sbt-ethereum_ will provide in a transaction, regardless of its estimated gas cost.

_Usually this should not be set! To control the gas limit precisely, you can use @ref:[`ethTransactionGasLimitOverrideSet`](../tasks/eth/transaction/gas.md#ethtransactiongaslimitoverrideset) in your session._

@@@

### ethcfgGasLimitMarkup                

@@@ div { .keydesc }

**Type:** `Double`

**Default:** `0.20`

To set the transaction gas limit, _sbt-ethereum_ estimates the transaction's cost using its node's [`eth_estimateGas`](https://github.com/ethereum/wiki/wiki/JSON-RPC#eth_estimategas) function,
and then adds a markup, 0.2 or 20% by default. Then any @ref:[cap](#ethcfggaslimitcap) or @ref:[floor](#ethcfggaslimitfloor) is applied.

If you'd like a higher markup (to reduce the possibility of running out of gas) or a lower one (to more tightly limit transaction cost), you can define your own value for this setting.

This value can be negative, in which case it reflects a discount rather than a markup. However, it's a bad idea &mdash; you'll almost certainly just exhaust your gas and have to pay for failed transactions.

@@@

### ethcfgGasPriceCap                   

@@@ div { .keydesc }

**Type:** `BigInt`

**Default:** _Unset_

If set, this defines the _maximum_ gas price in wei _sbt-ethereum_ will allow in a transaction, regardless of its estimated prevailing gas price.

_Usually this should not be set! To control the gas price precisely, you can use @ref:[`ethTransactionGasPriceOverrideSet`](../tasks/eth/transaction/gas.md#ethtransactiongaspriceoverrideset) in your session._

@@@

### ethcfgGasPriceFloor                 

@@@ div { .keydesc }

**Type:** `BigInt`

**Default:** _Unset_

If set, this defines the _minimum_ gas price in wei _sbt-ethereum_ will provide in a transaction, regardless of its estimated prevailing gas price.

_Usually this should not be set! To control the gas price precisely, you can use @ref:[`ethTransactionGasPriceOverrideSet`](../tasks/eth/transaction/gas.md#ethtransactiongaspriceoverrideset) in your session._

@@@

### ethcfgGasPriceMarkup                

@@@ div { .keydesc }

**Type:** `Double`

**Default:** `0.00`

To set the transaction gas price, _sbt-ethereum_ estimates the prevailing gas price using its node's [`eth_gasPrice`](https://github.com/ethereum/wiki/wiki/JSON-RPC#eth_gasprice) function,
and then adds a markup, of 0.00 or 0% by default. Then any @ref:[cap](#ethcfggaspricecap) or @ref:[floor](#ethcfggaspricefloor) is applied.

If you'd like a higher markup (to reduce the possibility of running out of gas) or a lower one (to require a discount to the prevailing cost), you can define your own value for this setting.

This value can be negative, in which case it reflects a discount rather than a markup.

@@@

### ethcfgIncludeLocations

@@@ div { .keydesc }

**Type:** `Seq[String]`

**Default:** `Nil` (an empty `Seq`)

A sequence of directories or URLs that should be searched to resolve Solidity import directives, besides the source directory itself.

@@@

### ethcfgKeystoreAutoImportLocationsV3 

@@@ div { .keydesc }

**Type:** `Seq[File]`

**Default:** _a list containing the default `geth` keystore directory for your platform

A sequence of File objects representing directories containing _ethereum_ V3 Wallet JSON files that should automatically be imported into the _sbt-ethereum_ keystore.

@@@

### ethcfgKeystoreAutoRelockSeconds     

@@@ div { .keydesc }

**Type:** `Int`

**Default:** `300`

A number of seconds during which, after a wallet has been unlocked, its private key may remain available without retyping the wallet passcode.

@@@

### ethcfgNetcompileUrl                 

@@@ div { .keydesc }

**Type:** `String`

**Default:** _Unset_

The URL of a (nonstandard) network-hosted Solidity compiler.

_Networked Solidity compilation using the `eth-netcompile` project is unlikely to be supported going forward. Please consider this setting deprecated._

@@@

### ethcfgNodeChainId                   

@@@ div { .keydesc }

**Type:** `Int`

**Default:** _Unset_ (except in configuration `Test`, for which the default value is `-1`)

A "hardcoded" [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID that will override any @ref:[default Chain ID](../tasks/eth/node/chain_id.md) set for the project.

_Often you will rely upon the @ref:[shoebox default](../method_to_the_madness.md#defaults-and-session-overrides)
(set with @ref:[ethNodeChainIdDefaultSet](../tasks/eth/node/chain_id.md#ethnodechainiddefaultset)) or the session override
(set with @ref:[ethNodeChainIdOverrideSet](../tasks/eth/node/chain_id.md#ethnodechainidoverrideset)). However, if you want a project to be explicitly associated with a
particular chain, you can set this. It will override any value set with @ref:[ethNodeChainIdDefaultSet](../tasks/eth/node/chain_id.md#ethnodechainiddefaultset)._

If a negative value is set, the chain will be treated as ephemeral. Deployments won't be saved in the shoebox database, and no
[EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID will be embedded in signatures.

@@@

### ethcfgNodeUrl                       

@@@ div { .keydesc }

**Type:** `String`

**Default:** _Unset_ (except in configuration `Test`, for which the default value is `http://localhost:58545/`)

A "hardcoded" [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) _ethereum_ node URL that will override any @ref:[default Node URL](../tasks/eth/node/chain_id.md) set for the project.

_Often you will rely upon the @ref:[shoebox default](../method_to_the_madness.md#defaults-and-session-overrides) for your session's Chain ID
(set with @ref:[ethNodeUrlDefaultSet](../tasks/eth/node/chain_id.md#ethnodeurldefaultset)) or the session override
(set with @ref:[ethNodeUrlOverrideSet](../tasks/eth/node/chain_id.md#ethnodeurloverrideset)). However, if you want a project to be explicitly associated with a
particular URL, you can set this. It will override any value set with @ref:[ethNodeUrlDefaultSet](../tasks/eth/node/chain_id.md#ethnodechainiddefaultset)._

@@@

### ethcfgScalaStubsPackage             

@@@ div { .keydesc }

**Type:** `String`

**Default:** _Unset_

The dot-separated fully qualified package name into which Scala stubs for compiled smart contracts should be generated, if you'd like scala stubs for compiled smart contracts!
If not set, no Scala stubs will be generated.

@@@

### ethcfgSolidityCompilerOptimize      

@@@ div { .keydesc }

**Type:** `Boolean`

**Default:** `true`

Defines whether compiler optimization should be enabled during compilations of Solidity files.

@@@

### ethcfgSolidityCompilerOptimizerRuns 

@@@ div { .keydesc }

**Type:** `Boolean`

**Default:** `200`

Defines for how many runs (how exhaustively) the Solidity compiler should try to optimize, if Solidity compiler optimizations are enabled. (See @ref:[ethcfgSolidityCompilerOptimize](#ethcfgsoliditycompileroptimize))

@@@

### ethcfgSoliditySource                

@@@ div { .keydesc }

**Type:** `File` (a diectory)

**Default:** `src/main/solidity` (or, for the `Test` configuration, `src/test/solidity`)

Defines where sbt-ethereum looks for Solidity files to compile. Defined in terms of the standard `sourceDirectory` key: Unless overridden, this will be a `solidity` subdirectory of `sourceDirectory.value`.

@@@

### ethcfgSolidityDestination           

@@@ div { .keydesc }

**Type:** `File` (a diectory)

**Default:** `target/ethereum/solidity`

Defines where Solidity compilation artifacts get stored. Defined in terms of the @ref:[`ethcfgTargetDir`](#ethcfgtargetdir) key: Unless overridden, this will be a `solidity` subdirectory of `ethcfgTargetDir.value`.

@@@

### ethcfgTargetDir                     

@@@ div { .keydesc }

**Type:** `File` (a diectory)

**Default:** `target/ethereum`

Defines where _sbt-ethereum_-related artifacts and compilations get stored. Defined in terms of the standard `target` key: Unless overridden, this will be an `ethereum` subdirectory of `target`.

@@@

### ethcfgTransactionReceiptPollPeriod  

@@@ div { .keydesc }

**Type:** `Duration`

**Default:** 3 seconds

After a transaction is submitted, _sbt-ethereum_ will "poll" its node for that transaction until it has been mined or [times out](#ethcfgtransactionreceipttimeout).

This setting determines how frequently it will poll.

@@@

### ethcfgTransactionReceiptTimeout     

@@@ div { .keydesc }

**Type:** `Duration`

**Default:** 5 minutes

After a transaction is submitted, _sbt-ethereum_ will "poll" its node for that transaction until it has been mined or times out.

This setting determines how long _sbt-ethereum_ will wait to see that a transaction has been mined onto a chain before giving up.

@@@

### ethcfgUseReplayAttackProtection     

@@@ div { .keydesc }

**Type:** `Boolean`

**Default:** `true`

If there is a non-negative Chain ID associated with an _sbt-ethereum_ session, _sbt-ethereum_ can incorporate that in transaction
signatures to [prevent "replay" attacks](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md), which occur when a valid
transaction is submitted to an unintended chain.

If replay attack protection is enabled, transactions are only valid for the (hopefully unique) chain that matches the embedded Chain ID.

If replay attack prevention is not enabled, no Chain ID is encoded into the signature and a transaction might be valid for many chains.

@@@

