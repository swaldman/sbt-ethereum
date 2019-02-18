# Settings

### ethcfgAddressSender

@@@ div { .keydesc }

**Type:** `String`

**Default:** _Unset_

A hex _Ethereum_ address that will become the default sender for sessions started within this repository, overriding any default sender in the database.

**This setting hardcodes the default sender address for sessions in the repository that sets it. Defining it results in the default sender address appearing in version control.
It's usually better _not_ to commit to or expose a sender in the repository, and use @ref:[`ethAddressSender*`](../tasks/eth/address/sender.md) tasks to manage the identity of your senders.**

@@@

### ethcfgAutoDeployContracts

@@@ div { .keydesc }

**Type:** `Seq[String]`

**Default:** _Unset_

Contracts compiled from this repository that should be autodeployed if no arguments are provided to @ref:[`ethTransactionDeploy`](../tasks/eth/transaction/index.md#ethtransactiondeploy).
Each element of the `Seq` should be the name of the Contract to deploy, optionally with space separated constructor argument for that contract. If the constructor is payable,
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
and then adds a markup, 0.2 or 20% by default. Then any [cap](#ethcfggaslimitcap) or [floor](#ethcfggaslimitcap) is applied.

If you'd like a higher markup (to reduce the possibility of running out of gas) or a lower one (to more tightly limit transaction cost), you can define your own value for this setting.

@@@

### ethcfgGasPriceCap                   

### ethcfgGasPriceFloor                 

### ethcfgGasPriceMarkup                

### ethcfgIncludeLocations              

### ethcfgKeystoreAutoImportLocationsV3 

### ethcfgKeystoreAutoRelockSeconds     

### ethcfgNetcompileUrl                 

### ethcfgNodeChainId                   

### ethcfgNodeUrl                       

### ethcfgScalaStubsPackage             

### ethcfgSolidityCompilerOptimize      

### ethcfgSolidityCompilerOptimizerRuns 

### ethcfgSoliditySource                

### ethcfgSolidityDestination           

### ethcfgTargetDir                     

### ethcfgTransactionReceiptPollPeriod  

### ethcfgTransactionReceiptTimeout     

### ethcfgUseReplayAttackProtection     
