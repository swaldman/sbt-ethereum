# Gas*

### ethTransactionGasLimitOverride

@@@ div { .keydesc }

**Usage:**
```
> ethTransactionGasLimitOverride <gas-limit-override>
```
This is a shorthand for @ref:[`ethTransactionGasLimitOverrideSet`](#ethtransactiongaslimitoverrideset). Please see that command for more information.

@@@

### ethTransactionGasLimitOverrideDrop

@@@ div { .keydesc }

**Usage:**
```
> ethTransactionGasLimitOverrideDrop
```

Drops (undoes) any transaction gas-limit override set for the current session and [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID.
Transaction gas limits will be automatically computed.

**Example:**
```
> ethTransactionGasLimitOverrideDrop
[info] No gas override is now set for chain with ID 1. Quantities of gas will be automatically computed.
[success] Total time: 0 s, completed Apr 3, 2019 10:07:52 PM
```

@@@

### ethTransactionGasLimitOverridePrint

@@@ div { .keydesc }

**Usage:**
```
> ethTransactionGasLimitOverridePrint
```

Displays on the console any currently set gas limit override,

**Example (override set):**
```
> ethTransactionGasLimitOverridePrint
[info] A gas override is set, with value 21000, for chain with ID 1.
[success] Total time: 0 s, completed Apr 3, 2019 10:14:26 PM
```

**Example (no override set):**
```
> ethTransactionGasLimitOverridePrint
[info] No gas override is currently set for chain with ID 1.
[success] Total time: 0 s, completed Apr 3, 2019 10:14:36 PM
```

@@@

### ethTransactionGasLimitOverrideSet

@@@ div { .keydesc }

**Usage:**
```
> ethTransactionGasLimitOverrideSet <gas-limit-override>
```

Sets the _gas limit_ for transactions in this session, overriding _sbt-ethereum_'s default automatic computation based on @ref:[`ethcfgGasPriceMarkup`](../../../settings/index.md#ethcfggaspricemarkup),
@ref:[`ethcfgGasPriceCap`](../../../settings/index.md#ethcfggaspricecap), and @ref:[`ethcfgGasPriceCap`](../../../settings/index.md#ethcfggaspricefloor).

_**Note: Typically, you will want an override to control one particular transaction, not multiple transactions in a session. Be sure to @ref:[drop](#ethtransactiongaslimitoverridedrop) the override when you are done with it. sbt-ethereum warns you when a gas limit override will be used.**_

**Example:**
```
> ethTransactionGasLimitOverrideSet 21000
[info] Gas override set to 21000 on chain with ID 1.
[success] Total time: 1 s, completed Apr 3, 2019 10:05:07 PM
sbt:ens-scala> ethTransactionEtherSend testing1 0.001 ether
[warn] Gas limit override set: 21000
[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************
[info] V3 wallet(s) found for '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (aliases ['default-sender','testing0'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0xae79b77e31387a3b2409b70c27cebc7220101026 (with aliases ['testing1'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  None
==>   Value: 0.001 Ether
==>
==> The transaction is signed with Chain ID 1 (which correctly matches the current session's 'ethNodeChainId').
==>
==> The nonce of the transaction would be 391.
==>
==> $$$ The transaction you have requested could use up to 21000 units of gas.
==> $$$ You would pay 10 gwei for each unit of gas, for a maximum cost of 0.00021 ether.
==> $$$ This is worth 0.0342405 USD (according to Coinbase at 10:05 PM).
==> $$$ You would also send 0.001 ether (0.16305 USD), for a maximum total cost of 0.00121 ether (0.1972905 USD).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0xc68b15b48c58bb49fa6cb08866d5e83a0540b41dd4f8587f9d1a36d430b2d1e5' will be submitted. Please wait.
[info] Sending 1000000000000000 wei to address '0xae79b77e31387a3b2409b70c27cebc7220101026' in transaction '0xc68b15b48c58bb49fa6cb08866d5e83a0540b41dd4f8587f9d1a36d430b2d1e5'.
[info] Waiting for the transaction to be mined (will wait up to 5 minutes).
[info] Transaction Receipt:
[info]        Transaction Hash:    0xc68b15b48c58bb49fa6cb08866d5e83a0540b41dd4f8587f9d1a36d430b2d1e5
[info]        Transaction Index:   142
[info]        Transaction Status:  SUCCEEDED
[info]        Block Hash:          0x36215d62203fb7641e3d8e87a2e4cb86df4802924f8a78546cf33f5d0097d614
[info]        Block Number:        7499744
[info]        From:                0x465e79b940bc2157e4259ff6b2d92f454497f1e4
[info]        To:                  0xae79b77e31387a3b2409b70c27cebc7220101026
[info]        Cumulative Gas Used: 6417405
[info]        Gas Used:            21000
[info]        Contract Address:    None
[info]        Logs:                None
[info]        Events:              None
[info] Ether sent.
[success] Total time: 53 s, completed Apr 3, 2019 10:06:07 PM
```

@@@

### ethTransactionGasPriceOverride

@@@ div { .keydesc }

**Usage:**
```
> ethTransactionGasPriceOverride <gas-price-override>
```
This is a shorthand for @ref:[`ethTransactionGasPriceOverrideSet`](#ethtransactiongaspriceoverrideset). Please see that command for more information.

@@@

### ethTransactionGasPriceOverrideDrop

@@@ div { .keydesc }

**Usage:**
```
> ethTransactionGasPriceOverrideDrop
```

Drops (undoes) any transaction gas-price override set for the current session and [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID.
Transaction gas prices will be automatically computed.

**Example:**
```
> ethTransactionGasPriceOverrideDrop
[info] No gas price override is now set for chain with ID 1.
[info] Gas price will be automatically marked-up from your ethereum node's current default value.
[success] Total time: 1 s, completed Apr 3, 2019 10:46:05 PM
```

@@@

### ethTransactionGasPriceOverridePrint

@@@ div { .keydesc }

**Usage:**
```
> ethTransactionGasPriceOverridePrint
```

Displays on the console any currently set gas price override,

**Example (override set):**
```
> ethTransactionGasPriceOverridePrint
[info] A gas price override is set, with value 12000000000, for chain with ID 1.
[success] Total time: 0 s, completed Apr 3, 2019 10:40:55 PM
```

**Example (no override set):**
```
> ethTransactionGasPriceOverridePrint
[info] No gas price override is currently set for chain with ID 1.
[success] Total time: 0 s, completed Apr 3, 2019 10:47:09 PM
```

@@@

### ethTransactionGasPriceOverrideSet


@@@ div { .keydesc }

**Usage:**
```
> ethTransactionGasPriceOverrideSet <gas-price-override-amount> <gas-price-override-unit>
```

Sets the _gas price_ for transactions in this session, overriding _sbt-ethereum_'s default automatic computation based on @ref:[`ethcfgGasPriceMarkup`](../../../settings/index.md#ethcfggaspricemarkup),
@ref:[`ethcfgGasPriceCap`](../../../settings/index.md#ethcfggaspricecap), and @ref:[`ethcfgGasPriceCap`](../../../settings/index.md#ethcfggaspricefloor).

_**Note: Typically, you will want an override to control one particular transaction, not multiple transactions in a session. Be sure to @ref:[drop](#ethtransactiongaspriceoverridedrop) the override when you are done with it. sbt-ethereum warns you when a gas price override will be used.**_

**Example:**
```
> ethTransactionGasPriceOverrideSet 12 gwei
[info] Gas price override set to 12000000000 for chain with ID 1.
[success] Total time: 1 s, completed Apr 3, 2019 10:40:48 PM
> ethTransactionEtherSend testing1 0.001 ether
[warn] Gas price override set: 12000000000
[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************
[info] V3 wallet(s) found for '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (aliases ['default-sender','testing0'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0xae79b77e31387a3b2409b70c27cebc7220101026 (with aliases ['testing1'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  None
==>   Value: 0.001 Ether
==>
==> The transaction is signed with Chain ID 1 (which correctly matches the current session's 'ethNodeChainId').
==>
==> The nonce of the transaction would be 392.
==>
==> $$$ The transaction you have requested could use up to 25200 units of gas.
==> $$$ You would pay 12 gwei for each unit of gas, for a maximum cost of 0.0003024 ether.
==> $$$ This is worth 0.0493380720 USD (according to Coinbase at 10:43 PM).
==> $$$ You would also send 0.001 ether (0.163155 USD), for a maximum total cost of 0.0013024 ether (0.2124930720 USD).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0xcd30b091ca5a6b31210aa2242db370518a40e05e04d08f6d360e8621e93e3ebb' will be submitted. Please wait.
[info] Sending 1000000000000000 wei to address '0xae79b77e31387a3b2409b70c27cebc7220101026' in transaction '0xcd30b091ca5a6b31210aa2242db370518a40e05e04d08f6d360e8621e93e3ebb'.
[info] Waiting for the transaction to be mined (will wait up to 5 minutes).
[info] Transaction Receipt:
[info]        Transaction Hash:    0xcd30b091ca5a6b31210aa2242db370518a40e05e04d08f6d360e8621e93e3ebb
[info]        Transaction Index:   58
[info]        Transaction Status:  SUCCEEDED
[info]        Block Hash:          0x19a77d9724dabeccdc8d8f7734e7231e774b540aef50c0eecb9e3a39512beb49
[info]        Block Number:        7499893
[info]        From:                0x465e79b940bc2157e4259ff6b2d92f454497f1e4
[info]        To:                  0xae79b77e31387a3b2409b70c27cebc7220101026
[info]        Cumulative Gas Used: 2649066
[info]        Gas Used:            21000
[info]        Contract Address:    None
[info]        Logs:                None
[info]        Events:              None
[info] Ether sent.
[success] Total time: 37 s, completed Apr 3, 2019 10:43:25 PM
```

@@@
