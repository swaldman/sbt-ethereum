# Nonce*

Tasks for overriding and taking full control of transaction nonces.

### ethTransactionNonceOverride

@@@ div { .keydesc }

**Usage:**
```
> ethTransactionNonceOverride <nonce-override>
```
This is a shorthand for @ref:[`ethTransactionNonceOverrideSet`](#ethtransactionnonceoverrideset). Please see that command for more information.

@@@

### ethTransactionNonceOverrideDrop

@@@ div { .keydesc }

**Usage:**
```
> ethTransactionNonceOverrideDrop
```

Drops (undoes) any transaction nonce override set for the current session and [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID.
Transaction nonces will be automatically computed.

**Example:**
```
> ethTransactionNonceOverrideDrop
[info] Any nonce override for chain with ID 1 has been unset. The nonces for any new transactions will be automatically computed.
[success] Total time: 0 s, completed Apr 3, 2019 11:01:19 PM
```

@@@

### ethTransactionNonceOverridePrint

@@@ div { .keydesc }

**Usage:**
```
> ethTransactionNonceOverridePrint
```

Displays on the console any currently set nonce override,

**Example (override set):**
```
> ethTransactionNonceOverridePrint
[info] A nonce override is set, with value 12, on chain with ID 1.
[info] Future transactions will use this value as a fixed nonce, until this override is explcitly unset with 'ethTransactionNonceOverrideDrop'.
[success] Total time: 1 s, completed Apr 3, 2019 11:01:12 PM
```

**Example (no override set):**
```
> ethTransactionNonceOverridePrint
[info] No nonce override is currently set for chain with ID 1. The nonces for any new transactions will be automatically computed.
[success] Total time: 0 s, completed Apr 3, 2019 11:01:26 PM
```

@@@

### ethTransactionNonceOverrideSet

@@@ div { .keydesc }

**Usage:**
```
> ethTransactionNonceOverrideSet <nonce-override>
```

Sets the _nonce_ for transactions in this session, overriding _sbt-ethereum_'s default automatic computation of the next nonce.

_**Note: Typically, you will want an override to control one particular transaction, not multiple transactions in a session. Be sure to @ref:[drop](#ethtransactionnonceoverridedrop) the override when you are done with it. sbt-ethereum warns you when a nonce override will be used.**_

**Example (with failing transaction, because the override is not next-nonce):**
```
> ethTransactionNonceOverrideSet 12
[info] Nonce override set to 12 for chain with ID 1.
[info] Future transactions will use this value as a fixed nonce, until this override is explcitly unset with 'ethTransactionNonceOverrideDrop'.
[success] Total time: 0 s, completed Apr 3, 2019 10:58:35 PM
> ethTransactionEtherSend testing1 0.001 ether
[info] Nonce override set: 12
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
==> The nonce of the transaction would be 12.
==>
==> $$$ The transaction you have requested could use up to 25200 units of gas.
==> $$$ You would pay 10 gwei for each unit of gas, for a maximum cost of 0.000252 ether.
==> $$$ This is worth 0.041049540 USD (according to Coinbase at 10:59 PM).
==> $$$ You would also send 0.001 ether (0.162895 USD), for a maximum total cost of 0.001252 ether (0.203944540 USD).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0x6343bab3d8fd466af5d5bf43e547cdd4057835d6f4a8071a73bfad5ce84a84b8' will be submitted. Please wait.
[error] com.mchange.sc.v2.jsonrpc.package$JsonrpcException: nonce too low [code=-32000]: No further information
[error] 	at com.mchange.sc.v2.jsonrpc.Response$Error.vomit(Response.scala:12)
[error] 	at com.mchange.sc.v1.consuela.ethereum.jsonrpc.Client$Implementation$Exchanger.$anonfun$responseHandler$1(Client.scala:282)
[error] 	at scala.util.Success.$anonfun$map$1(Try.scala:251)
[error] 	at scala.util.Success.map(Try.scala:209)
[error] 	at scala.concurrent.Future.$anonfun$map$1(Future.scala:288)
[error] 	at scala.concurrent.impl.Promise.liftedTree1$1(Promise.scala:29)
[error] 	at scala.concurrent.impl.Promise.$anonfun$transform$1(Promise.scala:29)
[error] 	at scala.concurrent.impl.CallbackRunnable.run(Promise.scala:60)
[error] 	at java.util.concurrent.ForkJoinTask$RunnableExecuteAction.exec(ForkJoinTask.java:1402)
[error] 	at java.util.concurrent.ForkJoinTask.doExec(ForkJoinTask.java:289)
[error] 	at java.util.concurrent.ForkJoinPool$WorkQueue.runTask(ForkJoinPool.java:1056)
[error] 	at java.util.concurrent.ForkJoinPool.runWorker(ForkJoinPool.java:1692)
[error] 	at java.util.concurrent.ForkJoinWorkerThread.run(ForkJoinWorkerThread.java:157)
[error] (Compile / ethTransactionEtherSend) com.mchange.sc.v2.jsonrpc.package$JsonrpcException: nonce too low [code=-32000]: No further information
[error] Total time: 13 s, completed Apr 3, 2019 10:59:10 PM
```

@@@
