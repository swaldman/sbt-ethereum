@@@ index

* [Gas*](gas.md)
* [Nonce*](nonce.md)
* [Unsigned*](unsigned.md)

@@@

@@@ div { .no-display #ethTransactionToc }

@@toc { depth=4 }

@@@

# ethTransaction*

Tasks related to creating, submitting, simulating, and configuring _ethereum_ transactions.

### ethTransactionDeploy


@@@ div { .keydesc}

**Usage:**
```
> ensTransactionDeploy <contract-name-or-deployment-alias> [<constructor-arg-if-applicable> <constructor-arg-if-applicable>...]
```

Deploys a compiled smart contract, based on the smart contract's name.

If the smart contract's constructor accepts arguments, those should be provided after the smart contract's name.

_**Although `sbt` will execute the compile task if necessary when this task is run, it's best to run `compile` first so that tab completion both contract name and constructor arguments can function.**_

```
> compile
[info] Compiling 1 Solidity source to /Users/swaldman/Dropbox/BaseFolders/development-why/gitproj/eth-who-am-i/target/ethereum/solidity...
[info] Compiling 'WhoAmI.sol'. (Debug source: '/Users/swaldman/Dropbox/BaseFolders/development-why/gitproj/eth-who-am-i/target/ethereum/solidity/WhoAmI.sol')
[info] No Scala stubs will be generated as the setting 'ethcfgScalaStubsPackage' has not ben set.
[info] If you'd like Scala stubs to be generated, please define 'ethcfgScalaStubsPackage'.
[info] Updating ...
[info] Done updating.
[success] Total time: 1 s, completed Mar 24, 2019 12:23:55 PM
> ethTransactionDeploy <tab>
WhoAmI   
sbt:eth-who-am-i> ethTransactionDeploy WhoAmI
[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************
[info] V3 wallet(s) found for '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (aliases ['default-sender','testing0'])
[info] Waiting for the transaction to be mined (will wait up to 5 minutes).

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a contract creation with...
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Init:  0x608060405234801561001057600080fd5b5060ae8061001f6000396000f300608060405260043610603e5763ffffffff7c0100000000000000000000000000000000000000000000000000000000600035041663da91254c81146043575b600080fd5b348015604e57600080fd5b506055607e565b6040805173ffffffffffffffffffffffffffffffffffffffff9092168252519081900360200190f35b33905600a165627a7a7230582077a9cf6805f0a9dff41a2a42693a4fc37ec627fcffd7435a82ecfe836cc7cda50029
==>   Value: 0 Ether
==>
==> The nonce of the transaction would be 380.
==>
==> $$$ The transaction you have requested could use up to 119197 units of gas.
==> $$$ You would pay 1.5 gwei for each unit of gas, for a maximum cost of 0.0001787955 ether.
==> $$$ This is worth 0.0240971635125 USD (according to Coinbase at 12:24 PM).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0x38403eaf8b08b19a37ccf70f6d1b1208e8a08bcfe7ff7c98437eab22fc4c567b' will be submitted. Please wait.
[info] Transaction Receipt:
[info]        Transaction Hash:    0x38403eaf8b08b19a37ccf70f6d1b1208e8a08bcfe7ff7c98437eab22fc4c567b
[info]        Transaction Index:   92
[info]        Transaction Status:  SUCCEEDED
[info]        Block Hash:          0xa4a7ee6cb3bccbf805227a41386c9db56cf737fa4e9b5ce430d91cc40e89a9a5
[info]        Block Number:        7433255
[info]        From:                0x465e79b940bc2157e4259ff6b2d92f454497f1e4
[info]        To:                  Unknown
[info]        Cumulative Gas Used: 6262704
[info]        Gas Used:            99331
[info]        Contract Address:    0xea198d96919be797c5aaac713b2698784ff9a0f4
[info]        Logs:                None
[info]        Events:              None
[info] Contract 'WhoAmI' deployed in transaction '0x38403eaf8b08b19a37ccf70f6d1b1208e8a08bcfe7ff7c98437eab22fc4c567b'.
[info] Contract 'WhoAmI' has been assigned address '0xea198d96919be797c5aaac713b2698784ff9a0f4'.
Enter an optional alias for the newly deployed 'WhoAmI' contract at '0xea198d96919be797c5aaac713b2698784ff9a0f4' (or [return] for none): who-who
[info] Alias 'who-who' now points to address '0xea198d96919be797c5aaac713b2698784ff9a0f4' (for chain with ID 1).
[info] Refreshing caches.
[success] Total time: 491 s, completed Mar 24, 2019 12:32:56 PM
```

@@@

### ethTransactionEtherSend


@@@ div { .keydesc}

**Usage:**
```
> ethTransactionEtherSend <destination-address-as-hex-or-ens-or-alias> <amount-quantity> <amount-unit = wei|gwei|szabo|finney|ether>
```

Creates and submits a transaction that sends Ether to the destination address.

**Example:**
```
> ethTransactionEtherSend 0xae79b77e31387a3b2409b70c27cebc7220101026 0.01 ether
[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************
[info] V3 wallet(s) found for '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (aliases ['default-sender','testing0'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0xae79b77e31387a3b2409b70c27cebc7220101026 (with aliases ['testing1'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  None
==>   Value: 0.01 Ether
==>
==> The transaction is signed with Chain ID 1 (which correctly matches the current session's 'ethNodeChainId').
==>
==> The nonce of the transaction would be 381.
==>
==> $$$ The transaction you have requested could use up to 25200 units of gas.
==> $$$ You would pay 2.4 gwei for each unit of gas, for a maximum cost of 0.00006048 ether.
==> $$$ This is worth 0.00814272480 USD (according to Coinbase at 12:44 PM).
==> $$$ You would also send 0.01 ether (1.34635 USD), for a maximum total cost of 0.01006048 ether (1.35449272480 USD).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0xb55148aec97c08bc70a923f351a2154f66097783aa0f058b2007834b63bad138' will be submitted. Please wait.
[info] Sending 10000000000000000 wei to address '0xae79b77e31387a3b2409b70c27cebc7220101026' in transaction '0xb55148aec97c08bc70a923f351a2154f66097783aa0f058b2007834b63bad138'.
[info] Waiting for the transaction to be mined (will wait up to 5 minutes).
[info] Transaction Receipt:
[info]        Transaction Hash:    0xb55148aec97c08bc70a923f351a2154f66097783aa0f058b2007834b63bad138
[info]        Transaction Index:   26
[info]        Transaction Status:  SUCCEEDED
[info]        Block Hash:          0x2c9cf887cbed5ae64512a9310c50947af98f9c1392865570bd5b661241cd88d0
[info]        Block Number:        7433327
[info]        From:                0x465e79b940bc2157e4259ff6b2d92f454497f1e4
[info]        To:                  0xae79b77e31387a3b2409b70c27cebc7220101026
[info]        Cumulative Gas Used: 1335634
[info]        Gas Used:            21000
[info]        Contract Address:    None
[info]        Logs:                None
[info]        Events:              None
[info] Ether sent.
[success] Total time: 29 s, completed Mar 24, 2019 12:44:52 PM
```

@@@

### ethTransactionForward

@@@ div {.keydesc}

**Usage:**
```
> ethTransactionForward [optional-signed-transaction-as-hex]
```

Forwards a signed _ethereum_ transaction to the currently configured @ref:[node](../node/index.md).

The signed transaction can be provided as a hex string on the command line, or will be solicited
interactively by the command.

**Example:**
```
> ethTransactionForward
Enter the path to a file containing a binary signed transaction, or just [return] to paste or type hex data manually: /tmp/new-fortune-signed.bin

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x82ea8ab1e836272322f376a5f71d5a34a71688f1 (with aliases ['fortune','fortune3'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  0x4cf373e6000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000315468652064617920616674657220746f6d6d6f726f772077696c6c20626520612070726574747920676f6f64206461792e000000000000000000000000000000
==>   Value: 0 Ether
==>
==> The transaction is signed with Chain ID 1 (which correctly matches the current session's 'ethNodeChainId').
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: addFortune(string)
==>     Arg 1 [name=fortune, type=string]: "The day after tommorow will be a pretty good day."
==>
==> The nonce of the transaction would be 382.
==>
==> $$$ The transaction you have requested could use up to 113234 units of gas.
==> $$$ You would pay 3.2 gwei for each unit of gas, for a maximum cost of 0.0003623488 ether.
==> $$$ This is worth 0.0488573004480 USD (according to Coinbase at 12:58 PM).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0xe1b27dc0963e92ef84b128d9eef0518379a9f20ec2c152d21e24a8e85d4d8bfb' will be submitted. Please wait.
[info] Sending signed transaction with transaction hash '0xe1b27dc0963e92ef84b128d9eef0518379a9f20ec2c152d21e24a8e85d4d8bfb'.
[info] Transaction Receipt:
[info]        Transaction Hash:    0xe1b27dc0963e92ef84b128d9eef0518379a9f20ec2c152d21e24a8e85d4d8bfb
[info]        Transaction Index:   7
[info]        Transaction Status:  SUCCEEDED
[info]        Block Hash:          0xf7a2e8f1686298043a3a390f9f7f75afe696b554f33c5f947417c725b03737ad
[info]        Block Number:        7433396
[info]        From:                0x465e79b940bc2157e4259ff6b2d92f454497f1e4
[info]        To:                  0x82ea8ab1e836272322f376a5f71d5a34a71688f1
[info]        Cumulative Gas Used: 438922
[info]        Gas Used:            94362
[info]        Contract Address:    None
[info]        Logs:                0 => EthLogEntry [source=0x82ea8ab1e836272322f376a5f71d5a34a71688f1] (
[info]                                    topics=[
[info]                                      0xaf1abf70f2d9f0d04e56242efc047451c912ad8f53a3b6d4391246d92ce889ff
[info]                                    ],
[info]                                    data=000000000000000000000000465e79b940bc2157e4259ff6b2d92f454497f1e4
[info]                                         0000000000000000000000000000000000000000000000000000000000000040
[info]                                         0000000000000000000000000000000000000000000000000000000000000031
[info]                                         5468652064617920616674657220746f6d6d6f726f772077696c6c2062652061
[info]                                         2070726574747920676f6f64206461792e000000000000000000000000000000
[info]                                  )
[info]        Events:              <no abi available to interpret logs as events>
[info] Transaction mined.
[success] Total time: 26 s, completed Mar 24, 2019 12:59:15 PM
```

@@@

### ethTransactionGas*

See @ref:[gas commands page](gas.md), or choose a command below:

@@@ div { #gasList .embedded-toc-list }

&nbsp;

@@@

### ethTransactionInvoke

@@@ div {.keydesc}

**Usage:**
```
> ethTransactionInvoke <contract-address-as-hex-or-ens-or-alias> <name-of-function-to-call> [<function-argument> <function-argument>...]
```

Creates and submits a transaction representing a function call to a smart contract for which a @ref:[contract abi](../contract/abi.md) is available.

Restricted to mutators, functions that might modify the blockchain.

For read-only operations (functions marked `pure` or `view`), use @ref:[`ethTransactionView`](#ethtransactionview).

**Example:**
```
> ethTransactionInvoke pingpong <tab>
ping    pong    
> ethTransactionInvoke pingpong ping <tab>
<payload, of type string>   ​                            
> ethTransactionInvoke pingpong ping "Whoops!"
[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************
[info] V3 wallet(s) found for '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (aliases ['default-sender','testing0'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x353e8352f0782b827d72757dab9cc9464c7e9a3b (with aliases ['pingpong'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  0x3adb191b0000000000000000000000000000000000000000000000000000000000000020000000000000000000000000000000000000000000000000000000000000000757686f6f70732100000000000000000000000000000000000000000000000000
==>   Value: 0 Ether
==>
==> The transaction is signed with Chain ID 1 (which correctly matches the current session's 'ethNodeChainId').
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: ping(string)
==>     Arg 1 [name=payload, type=string]: "Whoops!"
==>
==> The nonce of the transaction would be 383.
==>
==> $$$ The transaction you have requested could use up to 42126 units of gas.
==> $$$ You would pay 2 gwei for each unit of gas, for a maximum cost of 0.000084252 ether.
==> $$$ This is worth 0.011355063300 USD (according to Coinbase at 1:13 PM).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0xc31664e9cb83a29cfb6049c161a98253c6b57201c97dbef6637713c81c3ffd5f' will be submitted. Please wait.
[info] Called function 'ping', with args '"Whoops!"', sending 0 wei to address '0x353e8352f0782b827d72757dab9cc9464c7e9a3b' in transaction '0xc31664e9cb83a29cfb6049c161a98253c6b57201c97dbef6637713c81c3ffd5f'.
[info] Waiting for the transaction to be mined (will wait up to 5 minutes).
[info] Transaction Receipt:
[info]        Transaction Hash:    0xc31664e9cb83a29cfb6049c161a98253c6b57201c97dbef6637713c81c3ffd5f
[info]        Transaction Index:   18
[info]        Transaction Status:  SUCCEEDED
[info]        Block Hash:          0x90e6709ed2d7a945148a52909de552deb6780a36714aa05b96a6e7a8de5a04fb
[info]        Block Number:        7433471
[info]        From:                0x465e79b940bc2157e4259ff6b2d92f454497f1e4
[info]        To:                  0x353e8352f0782b827d72757dab9cc9464c7e9a3b
[info]        Cumulative Gas Used: 7921524
[info]        Gas Used:            35105
[info]        Contract Address:    None
[info]        Logs:                0 => EthLogEntry [source=0x353e8352f0782b827d72757dab9cc9464c7e9a3b] (
[info]                                    topics=[
[info]                                      0xadfa1f0ce4eb1d83af9464a1ab1144799ce4ec3f71e9a0478e437b4b63bafd55
[info]                                    ],
[info]                                    data=0000000000000000000000000000000000000000000000000000000000000020
[info]                                         0000000000000000000000000000000000000000000000000000000000000007
[info]                                         57686f6f70732100000000000000000000000000000000000000000000000000
[info]                                  )
[info]        Events:              0 => Pinged [source=0x353e8352f0782b827d72757dab9cc9464c7e9a3b] (
[info]                                    payload (of type string): "Whoops!"
[info]                                  )
[success] Total time: 63 s, completed Mar 24, 2019 1:14:36 PM
```
@@@

### ethTransactionInvokeAny

@@@ div {.keydesc}

**Usage:**
```
> ethTransactionInvokeAny <contract-address-as-hex-or-ens-or-alias> <name-of-function-to-call> [<function-argument> <function-argument>...]
```

Creates and submits a transaction representing a function call to a smart contract for which a @ref:[contract abi](../contract/abi.md) is available.

Same as @ref:[`ethTransactionInvoke`](#ethtransactioninvoke), except _**not**_ restricted to mutators (functions that might modify the blockchain).
You can, if you wish, submit an Ethereum transaction embedding a call to a read-only (`pure` or `view`) method with this task.
It's not clear why you'd ever want to do that, though. The task is defined for a kind of completeness.

@@@

### ethTransactionLookup <transaction-hash>

@@@ div {.keydesc}

**Usage:**
```
> ethTransactionLookup 0x3919a71d8d00aafe750a2d58293e5aa7aff73b02b40554d7069fcde52d441feb
```

Looks up and displays information about any past transaction incorporated into the blockchain.

**Example:**
```
> ethTransactionLookup 0x3919a71d8d00aafe750a2d58293e5aa7aff73b02b40554d7069fcde52d441feb
[info] Looking up transaction '0x3919a71d8d00aafe750a2d58293e5aa7aff73b02b40554d7069fcde52d441feb' (will wait up to 5 minutes).
[info] Transaction Receipt:
[info]        Transaction Hash:    0x3919a71d8d00aafe750a2d58293e5aa7aff73b02b40554d7069fcde52d441feb
[info]        Transaction Index:   72
[info]        Transaction Status:  SUCCEEDED
[info]        Block Hash:          0x8c0596e7397bd2f34715b090ca5c34aaaad95bf495b9354df0292ce49a81e026
[info]        Block Number:        7433550
[info]        From:                0xb2930b35844a230f00e51431acae96fe543a0347
[info]        To:                  0x6aa9517185430d5de0fdd57105b422f4a114ef20
[info]        Cumulative Gas Used: 7768871
[info]        Gas Used:            21000
[info]        Contract Address:    None
[info]        Logs:                None
[info]        Events:              None
[success] Total time: 4 s, completed Mar 24, 2019 1:39:59 PM
```

@@@

### ethTransactionMock

@@@ div {.keydesc}

**Usage:**
```
> ethTransactionMock <contract-address-as-hex-or-ens-or-alias> <name-of-function-to-call> [<function-argument> <function-argument>...]
```

_Simulates_ a transaction representing a function call to a smart contract for which a @ref:[contract abi](../contract/abi.md) is available,
and prints what the return value _would have been_, without actually making any permanent alteration of the blockchain.

**Example**, mock-incrementing a counter twice; incrementing once with a real, unsimulated, transaction; mock-incrementing twice again:
```
> ethTransactionMock counter increment
[warn] Simulating the result of calling nonconstant function 'increment'.
[warn] An actual transaction would occur sometime in the future, with potentially different results!
[warn] No changes that would have been made to the blockchain by a call of this function will be preserved.
[info] The function 'increment' yields 1 result.
[info]  + Result 1 of type 'uint256', named 'new_count', is 2
[success] Total time: 1 s, completed Mar 24, 2019 7:21:39 PM
> ethTransactionMock counter increment
[warn] Simulating the result of calling nonconstant function 'increment'.
[warn] An actual transaction would occur sometime in the future, with potentially different results!
[warn] No changes that would have been made to the blockchain by a call of this function will be preserved.
[info] The function 'increment' yields 1 result.
[info]  + Result 1 of type 'uint256', named 'new_count', is 2
[success] Total time: 0 s, completed Mar 24, 2019 7:21:44 PM
> ethTransactionInvoke counter increment
[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************
[info] V3 wallet(s) found for '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (aliases ['default-sender','testing0'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x20a4aae4677855681294158ef38344aead83a423 (with aliases ['counter','counterpoint'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  0xd09de08a
==>   Value: 0 Ether
==>
==> The transaction is signed with Chain ID 1 (which correctly matches the current session's 'ethNodeChainId').
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: increment()
==>
==> The nonce of the transaction would be 387.
==>
==> $$$ The transaction you have requested could use up to 33645 units of gas.
==> $$$ You would pay 3 gwei for each unit of gas, for a maximum cost of 0.000100935 ether.
==> $$$ This is worth 0.01370293560 USD (according to Coinbase at 7:22 PM).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0x4a8b0e887812d741c721624aba439d774bae72da86e83f159abf006a30620833' will be submitted. Please wait.
[info] Called function 'increment', with args '', sending 0 wei to address '0x20a4aae4677855681294158ef38344aead83a423' in transaction '0x4a8b0e887812d741c721624aba439d774bae72da86e83f159abf006a30620833'.
[info] Waiting for the transaction to be mined (will wait up to 5 minutes).
[info] Transaction Receipt:
[info]        Transaction Hash:    0x4a8b0e887812d741c721624aba439d774bae72da86e83f159abf006a30620833
[info]        Transaction Index:   55
[info]        Transaction Status:  SUCCEEDED
[info]        Block Hash:          0xa7e39eaf790c8376a64f796376366496212bd4c2ea17a5681e52a42f1673b98c
[info]        Block Number:        7435139
[info]        From:                0x465e79b940bc2157e4259ff6b2d92f454497f1e4
[info]        To:                  0x20a4aae4677855681294158ef38344aead83a423
[info]        Cumulative Gas Used: 5696022
[info]        Gas Used:            28038
[info]        Contract Address:    None
[info]        Logs:                0 => EthLogEntry [source=0x20a4aae4677855681294158ef38344aead83a423] (
[info]                                    topics=[
[info]                                      0x230c08f549f5f9e591e87490c6c26b3715ba3bdbe74477c4ec927b160763f767
[info]                                    ],
[info]                                    data=0000000000000000000000000000000000000000000000000000000000000001
[info]                                         0000000000000000000000000000000000000000000000000000000000000002
[info]                                  )
[info]        Events:              0 => Incremented [source=0x20a4aae4677855681294158ef38344aead83a423] (
[info]                                    old_count (of type uint256): 1,
[info]                                    new_count (of type uint256): 2
[info]                                  )
[success] Total time: 29 s, completed Mar 24, 2019 7:22:22 PM
> ethTransactionMock counter increment
[warn] Simulating the result of calling nonconstant function 'increment'.
[warn] An actual transaction would occur sometime in the future, with potentially different results!
[warn] No changes that would have been made to the blockchain by a call of this function will be preserved.
[info] The function 'increment' yields 1 result.
[info]  + Result 1 of type 'uint256', named 'new_count', is 3
[success] Total time: 0 s, completed Mar 24, 2019 7:22:32 PM
> ethTransactionMock counter increment
[warn] Simulating the result of calling nonconstant function 'increment'.
[warn] An actual transaction would occur sometime in the future, with potentially different results!
[warn] No changes that would have been made to the blockchain by a call of this function will be preserved.
[info] The function 'increment' yields 1 result.
[info]  + Result 1 of type 'uint256', named 'new_count', is 3
[success] Total time: 0 s, completed Mar 24, 2019 7:22:39 PM
```

@@@

### ethTransactionNonce*

See @ref:[nonce commands page](nonce.md), or choose a command below:

@@@ div { #nonceList .embedded-toc-list }

&nbsp;

@@@

### ethTransactionPing

@@@ div {.keydesc}

**Usage:**
```
> ethTransactionPing [optional-destination-address-as-hex-or-ens-or-alias]
```

Sends an "empty" message transaction &mdash; with no data and no Ether &mdash; to the supplied address. If no address is supplied,
the currently configured sender address sends an empty message to itself.

(This is just a cheap form of test transaction.)

**Example:**
```
> ethTransactionPing
[info] No recipient address supplied, sender address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' will ping itself.
[warn] Using hard-coded, backstop node URL 'https://ethjsonrpc.mchange.com/', which may not be reliable.
[warn] Please use 'ethNodeUrlDefaultSet` to define a node URL (for chain with ID 1) to which you have reliable access.
[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************
[info] V3 wallet(s) found for '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (aliases ['default-sender','testing0'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  None
==>   Value: 0 Ether
==>
==> The transaction is signed with Chain ID 1 (which correctly matches the current session's 'ethNodeChainId').
==>
==> The nonce of the transaction would be 388.
==>
==> $$$ The transaction you have requested could use up to 25200 units of gas.
==> $$$ You would pay 1 gwei for each unit of gas, for a maximum cost of 0.0000252 ether.
==> $$$ This is worth 0.0034220340 USD (according to Coinbase at 7:47 PM).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0xc334b218679a7f27ad495ee0550853f142ab1c83700a0e532d9e194e1e5ae3c1' will be submitted. Please wait.
[info] Sending 0 wei to address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' in transaction '0xc334b218679a7f27ad495ee0550853f142ab1c83700a0e532d9e194e1e5ae3c1'.
[info] Waiting for the transaction to be mined (will wait up to 5 minutes).
[info] Transaction Receipt:
[info]        Transaction Hash:    0xc334b218679a7f27ad495ee0550853f142ab1c83700a0e532d9e194e1e5ae3c1
[info]        Transaction Index:   142
[info]        Transaction Status:  SUCCEEDED
[info]        Block Hash:          0x0b67f2fa305c388da9d275c4881bc7f9a919819b97036df1ec0fbe37d7fbf79b
[info]        Block Number:        7435270
[info]        From:                0x465e79b940bc2157e4259ff6b2d92f454497f1e4
[info]        To:                  0x465e79b940bc2157e4259ff6b2d92f454497f1e4
[info]        Cumulative Gas Used: 6982711
[info]        Gas Used:            21000
[info]        Contract Address:    None
[info]        Logs:                None
[info]        Events:              None
[info] Ether sent.
[info] Ping succeeded!
[info] Sent 0 ether from '465e79b940bc2157e4259ff6b2d92f454497f1e4' to itself in transaction '0xc334b218679a7f27ad495ee0550853f142ab1c83700a0e532d9e194e1e5ae3c1'
[success] Total time: 49 s, completed Mar 24, 2019 7:47:43 PM
```

@@@

### ethTransactionRaw

@@@ div {.keydesc}

**Usage:**
```
> ethTransactionRaw <destination-address-as-hex-or-ens-or-alias> <raw-data-as-hex> <amount-quantity> <amount-unit = wei|gwei|szabo|finney|ether>
```

Creates and submits a "raw" transaction, for which the caller provides the message data directly as a hex string.
(For complete control of all fields of the transactions, specify @ref:[gas price and gas limit overrides](gas.md) and a [nonce override](nonce.md).)

**Example:**
```
> ethTransactionRaw counter 0xd09de08a 0 ether
[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************
[info] V3 wallet(s) found for '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (aliases ['default-sender','testing0'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x20a4aae4677855681294158ef38344aead83a423 (with aliases ['counter','counterpoint'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  0xd09de08a
==>   Value: 0 Ether
==>
==> The transaction is signed with Chain ID 1 (which correctly matches the current session's 'ethNodeChainId').
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: increment()
==>
==> The nonce of the transaction would be 390.
==>
==> $$$ The transaction you have requested could use up to 33645 units of gas.
==> $$$ You would pay 5 gwei for each unit of gas, for a maximum cost of 0.000168225 ether.
==> $$$ This is worth 0.02279953425 USD (according to Coinbase at 8:13 PM).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0x3c46890e52ca3dd6f798227eecd0aa1e138b3bce52f6752793a25bcec79e0cda' will be submitted. Please wait.
[info] Sending data '0xd09de08a' with 0 wei to address '0x20a4aae4677855681294158ef38344aead83a423' in transaction '0x3c46890e52ca3dd6f798227eecd0aa1e138b3bce52f6752793a25bcec79e0cda'.
[info] Transaction Receipt:
[info]        Transaction Hash:    0x3c46890e52ca3dd6f798227eecd0aa1e138b3bce52f6752793a25bcec79e0cda
[info]        Transaction Index:   90
[info]        Transaction Status:  SUCCEEDED
[info]        Block Hash:          0x13fdf9406762a2691dbe6dde0e2117548a913075c1f4617d9e2c6c71773218df
[info]        Block Number:        7435376
[info]        From:                0x465e79b940bc2157e4259ff6b2d92f454497f1e4
[info]        To:                  0x20a4aae4677855681294158ef38344aead83a423
[info]        Cumulative Gas Used: 2191898
[info]        Gas Used:            28038
[info]        Contract Address:    None
[info]        Logs:                0 => EthLogEntry [source=0x20a4aae4677855681294158ef38344aead83a423] (
[info]                                    topics=[
[info]                                      0x230c08f549f5f9e591e87490c6c26b3715ba3bdbe74477c4ec927b160763f767
[info]                                    ],
[info]                                    data=0000000000000000000000000000000000000000000000000000000000000003
[info]                                         0000000000000000000000000000000000000000000000000000000000000004
[info]                                  )
[info]        Events:              0 => Incremented [source=0x20a4aae4677855681294158ef38344aead83a423] (
[info]                                    old_count (of type uint256): 3,
[info]                                    new_count (of type uint256): 4
[info]                                  )
[info] Transaction mined.
[success] Total time: 79 s, completed Mar 24, 2019 8:14:59 PM
```

@@@

### ethTransactionSign

@@@ div {.keydesc}

**Usage:**
```
> ethTransactionSign [optional-signed-transaction-as-hex]
```

Signs an unsigned transaction, provided either as a hex string on the command line, or via an interactively provided binary file.

Prints the signed transaction as hex, and optionally saves it in binary format as a file.

**Example:**
```
> ethTransactionSign
Enter the path to a file containing a binary unsigned transaction, or just [return] to enter transaction data manually: /tmp/new-fortune.bin
Do you wish to sign for the sender associated with the current session, '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (with aliases ['default-sender','testing0'] on chain with ID 1)? [y/n] y
The Chain ID associated with your current session is 1. Would you like to sign with this Chain ID? [y/n] y
[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************
[info] V3 wallet(s) found for '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (aliases ['default-sender','testing0'])

==> T R A N S A C T I O N   S I G N A T U R E   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x82ea8ab1e836272322f376a5f71d5a34a71688f1 (with aliases ['fortune','fortune3'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  0x4cf373e6000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000315468652064617920616674657220746f6d6d6f726f772077696c6c20626520612070726574747920676f6f64206461792e000000000000000000000000000000
==>   Value: 0 Ether
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: addFortune(string)
==>     Arg 1 [name=fortune, type=string]: "The day after tommorow will be a pretty good day."
==>
==> The nonce of the transaction would be 382.
==>
==> $$$ The transaction you have requested could use up to 113234 units of gas.
==> $$$ You would pay 3.2 gwei for each unit of gas, for a maximum cost of 0.0003623488 ether.
==> $$$ This is worth 0.0488645474240 USD (according to Coinbase at 12:57 PM).

Would you like to sign this transaction? [y/n] y

Full signed transaction:
0xf8eb82017e84bebc20008301ba529482ea8ab1e836272322f376a5f71d5a34a71688f180b8844cf373e6000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000315468652064617920616674657220746f6d6d6f726f772077696c6c20626520612070726574747920676f6f64206461792e00000000000000000000000000000025a0899a29a21e25044e10a4f2a5bb1e69c461635804a4225f4b587de9bf152b7aa9a004c09a66750c39aee0ebb9ac273f34e49076db17dac8742200869e0ea511d0d7

Enter the path to a (not-yet-existing) file in which to write the binary signed transaction, or [return] to skip: /tmp/new-fortune-signed.bin
[info] Signed transaction saved as '/tmp/new-fortune-signed.bin'.
[success] Total time: 76 s, completed Mar 24, 2019 12:58:42 PM
```

@@@

### ethTransactionUnsigned*

See @ref:[unsigned transaction generation page](unsigned.md), or choose a command below:

@@@ div { #unsignedList .embedded-toc-list }

&nbsp;

@@@

### ethTransactionView

@@@ div {.keydesc}

**Usage:**
```
> ethTransactionView <contract-address-as-hex-or-ens-or-alias> <name-of-read-only-function-to-call> [<function-argument> <function-argument>...]
```

_Simulates_ a transaction representing a _read-only_ function call (calls marked `pure`, `view`, or `constant` in Solidity)
to a smart contract for which a @ref:[contract abi](../contract/abi.md) is available.
Prints what the return value of the call would have been.

_**This is the most common way of checking the state of smart contracts!**_

**Example**:
```
> ethTransactionView counter count
[info] The function 'count' yields 1 result.
[info]  + Result 1 of type 'uint256' is 4
[success] Total time: 0 s, completed Mar 24, 2019 9:28:26 PM
```

@@@


