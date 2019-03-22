# Url*

_sbt-ethereum_ associates (or wants to associate) a _node URL_ with each @ref:[node chain ID](#chain_id.md)
it encounters.

All of these `ethNodeUrl*` tasks manage the association between the current @ref:[effective chain ID](chain_id.md#ethnodechainidprint)
and a URL. Changing the current effective chain ID will also change the node URL:

```
> ethNodeChainIdPrint
[info] The current effective node chain ID is '1'.
[info]  + This is the default chain ID hard-coded into sbt-ethereum. 
[info]  + It has not been overridden with a session override or by an 'ethcfgNodeChainId' setting in the project build or the '.sbt' folder. 
[info]  + There is no default node chain ID defined in the sbt-ethereum shoebox.
[success] Total time: 0 s, completed Mar 21, 2019 10:19:08 PM
> ethNodeUrlPrint
[info] The current effective node json-rpc URL for chain with ID 1 is 'https://mainnet.infura.io/v3/353e8352f0782b827d72757dab9cc946'.
[info]  + This value is the default node json-rpc URL defined in the sbt-ethereum shoebox for chain with ID 1. 
[info]  + It has not been overridden with a session override or by an 'ethcfgNodeUrl' setting in the project build or the '.sbt' folder.
[success] Total time: 0 s, completed Mar 21, 2019 10:19:11 PM
> ethNodeChainIdOverrideSet 99
[info] The chain ID has been overridden to 99.
[info] The session is now active on chain with ID 99.
[info] Refreshing caches.
[success] Total time: 0 s, completed Mar 21, 2019 10:19:22 PM
> ethNodeUrlPrint
[info] The current effective node json-rpc URL for chain with ID 99 is 'https://core.poa.network'.
[info]  + This value is the default node json-rpc URL defined in the sbt-ethereum shoebox for chain with ID 99. 
[info]  + It has not been overridden with a session override or by an 'ethcfgNodeUrl' setting in the project build or the '.sbt' folder.
[success] Total time: 0 s, completed Mar 21, 2019 10:19:24 PM
```

### ethNodeUrlDefaultDrop

@@@ div { .keydesc }

**Usage:**
```
> ethNodeUrlDefaultDrop
```
Removes any previously set default node URL for current effective [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID.

_**Note: For some chain IDs, _sbt-ethereum_ may define a hardcoded backstop URL, which cannot be removed.**_

Use @ref:[`ethNodeUrlPrint`](#ethnodeurlprint) to see the current effective node URL at any time.

**Example:**
```
ethNodeUrlDefaultDrop
[info] The default node json-rpc URL for chain with ID 1 was 'https://mainnet.infura.io/v3/f69eeaa2105441dfa2461b0410fa117a', but it has now been successfully dropped.
[success] Total time: 1 s, completed Mar 21, 2019 7:28:40 PM
```

@@@

### ethNodeUrlDefaultPrint

@@@ div { .keydesc }

**Usage:**
```
> ethNodeUrlDefaultPrint
```
Displays the node URL current set as the default for current effective [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID.

**Example:**
```
> ethNodeUrlDefaultPrint
[info] The default node json-rpc URL for chain with ID 1 is 'https://mainnet.infura.io/v3/353e8352f0782b827d72757dab9cc946'.
[success] Total time: 1 s, completed Mar 21, 2019 7:23:40 PM
```

@@@

### ethNodeUrlDefaultSet

@@@ div { .keydesc }

**Usage:**
```
> ethNodeUrlDefaultSet <node-url>
```
Defines a default association between the current effective [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID and a node URL.

**Example:**
```
> ethNodeUrlDefaultSet https://https://mainnet.infura.io/v3/353e8352f0782b827d72757dab9cc946
[info] Successfully set default node json-rpc URL for chain with ID 1 to https://https://mainnet.infura.io/v3/353e8352f0782b827d72757dab9cc946.
[success] Total time: 0 s, completed Mar 21, 2019 7:22:35 PM
```

@@@


### ethNodeUrlOverrideDrop


@@@ div { .keydesc }

**Usage:**
```
> ethNodeUrlOverrideDrop
```
Removes any previously set this-session-only override of the node URL associated with the current effective [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID.

**Example:**
```
> ethNodeUrlOverrideDrop
[info] Any override has been dropped. The default node json-rpc URL for chain with ID 1, or else an sbt-ethereum hardcoded value, will be used for all tasks.
[success] Total time: 1 s, completed Mar 21, 2019 8:50:53 PM
```

### ethNodeUrlOverrideSet

@@@ div { .keydesc }

**Usage:**
```
> ethNodeUrlOverrideSet <node-url>
```
Defines a this-session-only association between the current effective [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID and a node URL, which overrides other settings.

**Example:**
```
> ethNodeUrlOverrideSet https://mainnet.infura.io/v3/353e8352f0782b827d72757dab9cc946
[info] The default node json-rpc URL for chain with ID 1 has been overridden. The new overridden value 'https://mainnet.infura.io/v3/353e8352f0782b827d72757dab9cc946' will be used for all tasks.
[success] Total time: 0 s, completed Mar 21, 2019 8:36:25 PM
```

@@@

### ethNodeUrlOverridePrint

@@@ div { .keydesc }

**Usage:**
```
> ethNodeUrlOverridePrint
```
Displays any node URL current associated with the current effective [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID as a this-session-only override.

**Example:**
```
> ethNodeUrlOverridePrint
[info] The default node json-rpc URL for chain with ID 1 has been overridden.
[info] The overridden value 'https://mainnet.infura.io/v3/353e8352f0782b827d72757dab9cc946' will be used for all tasks.
[success] Total time: 0 s, completed Mar 21, 2019 8:46:50 PM
```

@@@

### ethNodeUrlOverride

@@@ div { .keydesc }

**Usage:**
```
> ethNodeUrlOverride <chain-id>
```
This is a shorthand for @ref:[`ethNodeUrlOverrideSet`](#ethnodeurloverrideset). Please see that command for more information.

@@@

### ethNodeUrlPrint

@@@ div { .keydesc }

**Usage:**
```
> ethNodeUrlPrint
```

Displays the node URK currently associated with the effective [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID, and explains how it has been defined.

**Example:**
```
> ethNodeUrlPrint
[info] The current effective node json-rpc URL for chain with ID 1 is 'https://mainnet.infura.io/v3/353e8352f0782b827d72757dab9cc946'.
[info]  + This value is the default node json-rpc URL defined in the sbt-ethereum shoebox for chain with ID 1. 
[info]  + It has not been overridden with a session override or by an 'ethcfgNodeUrl' setting in the project build or the '.sbt' folder.
[success] Total time: 0 s, completed Mar 21, 2019 9:14:02 PM
```

@@@

### ethNodeUrl

@@@ div { .keydesc }

_**This task is not indended for direct use on the console**_

Yields the node URL associated with the currently effective [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID, for use by build tasks and
plugins.

**Example:**
```
> ethNodeUrl
[success] Total time: 0 s, completed Mar 21, 2019 9:16:00 PM
sbt:eth-timelock> show ethNodeUrl
[info] https://mainnet.infura.io/v3/353e8352f0782b827d72757dab9cc946
[success] Total time: 0 s, completed Mar 21, 2019 9:16:04 PM
```

@@@
