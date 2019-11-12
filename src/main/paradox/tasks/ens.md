# ens*

### ensAddressLookup

@@@ div { .keydesc}

**Usage:**
```
> ensAddressLookup <ens-name>.eth
```

Looks up the address that an ENS name refers to.

_**Note that this address is distinct from the address of the name's owner!**_

**Example:**
```
> ensAddressLookup thisisadumbname.eth
The name 'thisisadumbname.eth' resolves to address '0xae79b77e31387a3b2409b70c27cebc7220101026' (with aliases ['testing1'] on chain with ID 1).
[success] Total time: 2 s, completed Mar 16, 2019 11:07:24 PM
```

@@@

### ensAddressSet

@@@ div { .keydesc}

**Usage:**
```
> ensAddressSet <ens-name>.eth <address-as-hex-or-ens-or-alias>
```

Sets the address that an ENS name will point to.

_**Note that this address is distinct from the address of the name's owner!**_

**Example:**
```
> ensAddressSet thisisadumbname.eth 0xae79b77e31387a3b2409b70c27cebc7220101026
[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************
[info] V3 wallet(s) found for '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (aliases ['default-sender','testing0'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x1da022710df5002339274aadee8d58218e9d6ab5 (on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  0xd5fa2b00fe09d7b2a951becd6a6ab7e08c4ec2979ea216ccca363d514998e13479937f83000000000000000000000000ae79b77e31387a3b2409b70c27cebc7220101026
==>   Value: 0 Ether
==>
==> The transaction is signed with Chain ID 1 (which correctly matches the current session's 'ethNodeChainId').
==>
==> !!! Any ABI is associated with the destination address is currently unknown, so we cannot decode the message data as a method call !!!
==>
==> The nonce of the transaction would be 379.
==>
==> $$$ The transaction you have requested could use up to 59463 units of gas.
==> $$$ You would pay 2 gwei for each unit of gas, for a maximum cost of 0.000118926 ether.
==> $$$ This is worth 0.016400490030 USD (according to Coinbase at 11:04 PM).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0x9d9bf7abeb5d9f94d6181c220a60c46c58cab030d07acc7d46986574828ad46b' will be submitted. Please wait.
[info] The name 'thisisadumbname.eth' now resolves to '0xae79b77e31387a3b2409b70c27cebc7220101026' (with aliases ['testing1'] on chain with ID 1).
[success] Total time: 64 s, completed Mar 16, 2019 11:05:26 PM
```

@@@










### ensAddressMultichainLookup

@@@ div { .keydesc}

**Usage:**
```
> ensAddressMultichainLookup <BTC|ETH|slip44-index> <ens-name>.eth
```
For a specified coin, which may not be _Ethereum_, looks up the the cryptocurrency address assocaited with an ENS name .
Coins identified by their `Index` in [SLIP-44](https://github.com/satoshilabs/slips/blob/master/slip-0044.md). 

_**Note that this address is distinct from the Ethereum address that owns the name!**_

_sbt-ethereum_ currently offers complete support only for BTC and ETH addresses. It will print those in their conventional formats.
Other coins' addresses may be looked up, but the addresses will be returned as raw hex binary, prepended with the tag `binary-format:`

See [EIP 2304](https://eips.ethereum.org/EIPS/eip-2304) for specification of multichain address associations in ENS, including different coins' binary formats.

**Example (BTC):**
```
> ensAddressMultichainLookup BTC exigent.eth
[info] For coin 'BTC' with SLIP-44 Index 0, the name 'exigent.eth' resolves to address 18cjh41Ljp7CPzFZfrX45sdX9yKtaKXtPd, or binary-format:76a914538b134f052afc31504391632474579f2e62cf9288ac.
[success] Total time: 1 s, completed Nov 9, 2019 4:15:47 PM
```

**Example (arbitrary coin):**
```
> ensAddressMultichainLookup 1140810366 exigent.eth
[info] For coin with SLIP-44 Index 1140810366, the name 'exigent.eth' resolves to address binary-format:0123456789abcdef.
[success] Total time: 1 s, completed Nov 9, 2019 4:45:23 PM
```

@@@

### ensAddressMultichainSet

@@@ div { .keydesc}

**Usage:**
```
> ensAddressMultichainSet <BTC|ETH|slip44-index> <ens-name>.eth <address-as-hex-or-ens-or-alias>
```

For a specified coin, which may not be _Ethereum_, defines the the cryptocurrency address that will be assocaited with an ENS name .
Coins identified by their `Index` in [SLIP-44](https://github.com/satoshilabs/slips/blob/master/slip-0044.md). 

_**Note that this address is distinct from the Ethereum address that owns the name!**_

_sbt-ethereum_ currently offers complete support only for BTC and ETH addresses. It will accept those in their conventional formats.
Other coins' addresses may be defined, but the addresses must be set as raw hex binary, prepended with the tag `binary-format:`

See [EIP 2304](https://eips.ethereum.org/EIPS/eip-2304) for specification of multichain address associations in ENS, including different coins' binary formats.

**Example (BTC):**
```
> ensAddressMultichainSet BTC exigent.eth 18cjh41Ljp7CPzFZfrX45sdX9yKtaKXtPd

==> T R A N S A C T I O N   S I G N A T U R E   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x226159d592e2b063810a10ebf6dcbada94ed68b8 (with aliases ['ens-public-resolver-2019-10-24'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  0x8b95dd71f7af8227451695ae1890a0be783975f51704cbd3f1df6c3687838bd39d309b2700000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000060000000000000000000000000000000000000000000000000000000000000001976a914538b134f052afc31504391632474579f2e62cf9288ac00000000000000
==>   Value: 0 ether
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: setAddr(bytes32,uint256,bytes)
==>     Arg 1 [name=node, type=bytes32]: 0xf7af8227451695ae1890a0be783975f51704cbd3f1df6c3687838bd39d309b27
==>     Arg 2 [name=coinType, type=uint256]: 0
==>     Arg 3 [name=a, type=bytes]: 0x76a914538b134f052afc31504391632474579f2e62cf9288ac
==>
==> The nonce of the transaction would be 518.
==>
==> $$$ The transaction you have requested could use up to 50605 units of gas.
==> $$$ You would pay 1 gwei for each unit of gas, for a maximum cost of 0.000050605 ether.
==> $$$ This is worth 0.01 USD (according to Coinbase at 4:34 PM).

Would you like to sign this transaction? [y/n] y

[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************

[info] A transaction with hash '0x655416cf3e854589cd9f394b4c60bcbd26f8e8b8135d5dfb0d4a7cba0c1dcd80' has been submitted.
[info] Waiting up to 5 minutes for the transaction to be mined.
[info] For coin 'BTC' with SLIP-44 Index 0, the name 'exigent.eth' now resolves to 18cjh41Ljp7CPzFZfrX45sdX9yKtaKXtPd, or binary-format:76a914538b134f052afc31504391632474579f2e62cf9288ac.
[success] Total time: 86 s, completed Nov 9, 2019 4:35:52 PM
```

**Example (arbitrary coin):**
```
> ensAddressMultichainSet 1140810366 exigent.eth binary-format:0123456789abcdef
[warn] Hand-entered binary formats are very dangerous.
Are you sure you want to set the address for coin with SLIP-44 Index 1140810366 to raw binary data 0x0123456789abcdef? [y/n] y

==> T R A N S A C T I O N   S I G N A T U R E   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x226159d592e2b063810a10ebf6dcbada94ed68b8 (with aliases ['ens-public-resolver-2019-10-24'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  0x8b95dd71f7af8227451695ae1890a0be783975f51704cbd3f1df6c3687838bd39d309b270000000000000000000000000000000000000000000000000000000043ff627e000000000000000000000000000000000000000000000000000000000000006000000000000000000000000000000000000000000000000000000000000000080123456789abcdef000000000000000000000000000000000000000000000000
==>   Value: 0 ether
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: setAddr(bytes32,uint256,bytes)
==>     Arg 1 [name=node, type=bytes32]: 0xf7af8227451695ae1890a0be783975f51704cbd3f1df6c3687838bd39d309b27
==>     Arg 2 [name=coinType, type=uint256]: 1140810366
==>     Arg 3 [name=a, type=bytes]: 0x0123456789abcdef
==>
==> The nonce of the transaction would be 519.
==>
==> $$$ The transaction you have requested could use up to 61538 units of gas.
==> $$$ You would pay 1.1 gwei for each unit of gas, for a maximum cost of 0.0000676918 ether.
==> $$$ This is worth 0.01 USD (according to Coinbase at 4:42 PM).

Would you like to sign this transaction? [y/n] y

Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************

[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
[info] A transaction with hash '0x742624af4f4fa58f89bb5d431f8bc799be710a75e2538793251d4438ecf35f03' has been submitted.
[info] Waiting up to 5 minutes for the transaction to be mined.
[info] For coin with SLIP-44 Index 1140810366, the name 'exigent.eth' now resolves to binary-format:0123456789abcdef.
[success] Total time: 130 s, completed Nov 9, 2019 4:44:35 PM
```

@@@




















### ensMigrateRegistrar

@@@ div { .keydesc}

**Usage:**
```
> ensMigrateRegistrar <ens-name>.eth
```

Migrate a name registered from an obsoleted registrar (in practice, the [original Vickery Auction registrar](https://medium.com/the-ethereum-name-service/ens-is-upgrading-heres-what-you-need-to-do-f26423339fcf))
onto the current registrar for its domain (which is usually `eth`).

The @ref:[current session sender](./eth/address/sender.md) must be the owner of the name being migrated.

**Example:**
```
> > ensMigrateRegistrar mchange.eth
Using sender address '0xf0ed4a1ade1f4bbcc875275a9480c387dcdb185c' (on chain with ID 1, aliases ['steve-ens']). OK? [y/n] y

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x6090a6e47849629b7245dfa1ca21d94cd15878ef (with aliases ['ens-original-registrar','ens-registrar'] on chain with ID 1)
==>   From:  0xf0ed4a1ade1f4bbcc875275a9480c387dcdb185c (with aliases ['steve-ens'] on chain with ID 1)
==>   Data:  0x5ddae283df1868dc3e0a593019de98747a6b827efb993b350c8bced78969565947ef962a
==>   Value: 0 Ether
==>
==> The transaction is signed with Chain ID 1 (which correctly matches the current session's 'ethNodeChainId').
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: transferRegistrars(bytes32)
==>     Arg 1 [name=_hash, type=bytes32]: 0xdf1868dc3e0a593019de98747a6b827efb993b350c8bced78969565947ef962a
==>
==> The nonce of the transaction would be 142.
==>
==> $$$ The transaction you have requested could use up to 188376 units of gas.
==> $$$ You would pay 3 gwei for each unit of gas, for a maximum cost of 0.000565128 ether.
==> $$$ This is worth 0.139114734120 USD (according to Coinbase at 6:35 PM).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0x2abbb5eecb8d54a3587adce387f919dc535cabedd7a87e7aee7126d97d1a62f5' will be submitted. Please wait.
[info] The name 'mchange.eth' has successfully migrated.
[success] Total time: 78 s, completed May 23, 2019 6:37:00 PM
```

@@@

### ensNameExtend

@@@ div { .keydesc }

**Usage:**
```
> ensNameExtend <ens-name>.eth
```
Interactively extends the term of registration of an already registered name.

**Example:**
```
> ensNameExtend expletive.eth
For how long would you like to extend the name (ex: "3 years")? 10 days
In order to rent 'expletive.eth' for 10 days, it would cost approximately 0.000431514077176343 ether (431514077176343 wei).
To be sure the renewal succeeds, we'll mark it up a bit to 0.00045308978103516 ether (453089781035160 wei). Any "change" will be returned.
This corresponds to approximately 0.14 USD (at a rate of 317.605 USD per ETH, retrieved at 11:16 PM from Coinbase)
Is that okay? [y/n] y
[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************
[info] V3 wallet(s) found for '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (aliases ['default-sender','testing0'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0xf0ad5cad05e10572efceb849f6ff0c68f9700455 (with aliases ['ens-eth-tld-controller'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  0xacf1a841000000000000000000000000000000000000000000000000000000000000004000000000000000000000000000000000000000000000000000000000000d2f0000000000000000000000000000000000000000000000000000000000000000096578706c65746976650000000000000000000000000000000000000000000000
==>   Value: 0.00045308978103516 Ether
==>
==> The transaction is signed with Chain ID 1 (which correctly matches the current session's 'ethNodeChainId').
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: renew(string,uint256)
==>     Arg 1 [name=name, type=string]: "expletive"
==>     Arg 2 [name=duration, type=uint256]: 864000
==>
==> The nonce of the transaction would be 436.
==>
==> $$$ The transaction you have requested could use up to 70172 units of gas.
==> $$$ You would pay 2 gwei for each unit of gas, for a maximum cost of 0.000140344 ether.
==> $$$ This is worth 0.044489749720 USD (according to Coinbase at 11:19 PM).
==> $$$ You would also send 0.00045308978103516 ether (0.14363172603705089580 USD), for a maximum total cost of 0.00059343378103516 ether (0.18812147575705089580 USD).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0xa3ee6d40fe253713c73023d3cb111f7afb99f113a1d6ee1709357c7bc034a08e' will be submitted. Please wait.
[info] Registration of 'expletive.eth' has been extended for 864000 seconds (10.0 days).
[info] The registration is now valid until 'Mon, 13 Jul 2020 13:58:12 -0700'
[success] Total time: 58 s, completed Jun 29, 2019 11:19:32 PM
```

@@@

### ensNameHashes

@@@ div { .keydesc }

**Usage:**
```
> ensNameHashes <ens-name>.eth
```
Prints the namehash and labelhash of an ENS named.

These are mostly used internally by the ENS library, but may be useful for interacting with ENS-aware smart contracts.

**Example:**
```
> ensNameHashes mchange.eth
[info] The ENS namehash of mchange.eth is '0x17c52912b0502993fe269470ca3c740e563bbead87dd46dbe2b1fa9946ebf054'.
[info] The labelhash of label 'mchange' is '0xdf1868dc3e0a593019de98747a6b827efb993b350c8bced78969565947ef962a'.
[success] Total time: 1 s, completed Jun 29, 2019 9:51:31 PM
```

@@@

### ensNamePrice

@@@ div { .keydesc }

**Usage:**
```
> ensNamePrice <ens-name>.eth
```
Interactively computes the price of registering or renewing a name for a given period of time.

**Example:**
```
> ensNamePrice expletive.eth
For how long would you like to rent the name (ex: "3 years")? 2 months
In order to register or extend 'expletive.eth' for 2 months, it would cost 0.002626788005551341 ether (2626788005551341 wei).
This corresponds to approximately 0.83 USD (at a rate of 317.605 USD per ETH, retrieved at 11:16 PM from Coinbase)
[success] Total time: 5 s, completed Jun 29, 2019 11:17:22 PM
```

@@@

### ensNameRegister

@@@ div { .keydesc }

**Usage:**
```
> ensNameRegister <ens-name>.eth [optional-registrant-address-as-hex-or-ens-or-alias] [optional-secret-from-prior-commitment]
```
Interactively registers an available ENS name for a period of time.

If a registrant address is provided, the name will be registered to that address. Otherwise, it will
be registered to the @ref:[session's current sender](./eth/address/sender.md).

If a name is "half registered", if a commitment transaction has been submitted for the name and is still
valid, you can provide the "secret" you generated for that commitment and this task will register based on
that prior commitment.

**Example:**
```
> ensNameRegister smartelephant.eth
[warn] Using hard-coded, backstop node URL 'https://ethjsonrpc.mchange.com/', which may not be reliable.
[warn] Please use 'ethNodeUrlDefaultSet` to define a node URL (for chain with ID 1) to which you have reliable access.
For how long would you like to rent the name (ex: "3 years")? 1 month

In order to rent 'smartelephant.eth' for 1 months, it would cost approximately 0.002240969031475373 ether (2240969031475373 wei).
To be sure the renewal succeeds, we'll mark it up a bit to 0.002353017483049142 ether (2353017483049142 wei). Any "change" will be returned.
This corresponds to approximately 0.44 USD (at a rate of 185.485 USD per ETH, retrieved at 1:55 AM from Coinbase)
Is that okay? [y/n] y

In order to register 'smartelephant.eth', two transactions will be performed...
     (1) a commitment transaction
     (2) a registration transaction
You will need to approve both transactions.
A pause of about 60 seconds will be required between the two transactions.

Establishing registration commitment.
The random "secret" to which we are committing is '0x0e17ebdaf2948be6f1018484b16f1c92a09a21d6b08b6564065b276eb4370b58'.
If we sadly time out while waiting for the transaction to mine, if it does eventually successfully mine...
  you can continue where you left off with
    'ensNameRegister smartelephant.eth 0x465e79b940bc2157e4259ff6b2d92f454497f1e4 0x0e17ebdaf2948be6f1018484b16f1c92a09a21d6b08b6564065b276eb4370b58'
The registration must be completed after a minimum of 60 seconds, but within a maximum of 86400 seconds (24.0 hours) or the commitment will be lost.
Do you understand? [y/n] y
Preparing commitment transaction...

==> T R A N S A C T I O N   S I G N A T U R E   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0xb22c1c159d12461ea124b0deb4b5b93020e6ad16 (with aliases ['ens-controller-2019-11-10'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  0xf14fcbc88db07713f0e2fcc3c484a600e9e7383248a6c9820ae516371a5e603abffc0669
==>   Value: 0 ether
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: commit(bytes32)
==>     Arg 1 [name=commitment, type=bytes32]: 0x8db07713f0e2fcc3c484a600e9e7383248a6c9820ae516371a5e603abffc0669
==>
==> The nonce of the transaction would be 541.
==>
==> $$$ The transaction you have requested could use up to 53130 units of gas.
==> $$$ You would pay 2 gwei for each unit of gas, for a maximum cost of 0.00010626 ether.
==> $$$ This is worth 0.02 USD (according to Coinbase at 1:56 AM).

Would you like to sign this transaction? [y/n] y

[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************

[info] A transaction with hash '0xe04d52115c53cebb0d6379818dafcced214845f54ded8e6a9cd97f9fd420fa38' has been submitted.
[info] Waiting up to 5 minutes for the transaction to be mined.
[info] Temporary commitment of name 'smartelephant' for registrant '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (with aliases ['default-sender','testing0'] on chain with ID 1) has succeeded!

We must wait about 70 seconds before we can complete the registration.
Please wait.
Our long wait is over! Let's register 'smartelephant.eth'.

IF THIS FAILS FOR ANY REASON, COMPLETE IT MANUALLY BY EXECUTING

   > ensNameRegister smartelephant.eth 0x465e79b940bc2157e4259ff6b2d92f454497f1e4 0x0e17ebdaf2948be6f1018484b16f1c92a09a21d6b08b6564065b276eb4370b58

Now finalizing the registration of name 'smartelephant' for registrant 'EthAddress(ByteSeqExact20(0x465e79b940bc2157e4259ff6b2d92f454497f1e4))'.
If we sadly time out while waiting for the transaction to mine, it still may eventually succeed.
Use 'ensNameStatus smartelephant.eth' to check.

==> T R A N S A C T I O N   S I G N A T U R E   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0xb22c1c159d12461ea124b0deb4b5b93020e6ad16 (with aliases ['ens-controller-2019-11-10'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  0x85f6d1550000000000000000000000000000000000000000000000000000000000000080000000000000000000000000465e79b940bc2157e4259ff6b2d92f454497f1e400000000000000000000000000000000000000000000000000000000002820720e17ebdaf2948be6f1018484b16f1c92a09a21d6b08b6564065b276eb4370b58000000000000000000000000000000000000000000000000000000000000000d736d617274656c657068616e7400000000000000000000000000000000000000
==>   Value: 0.002353017483049142 ether
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: register(string,address,uint256,bytes32)
==>     Arg 1 [name=name, type=string]: "smartelephant"
==>     Arg 2 [name=owner, type=address]: 0x465e79b940bc2157e4259ff6b2d92f454497f1e4
==>     Arg 3 [name=duration, type=uint256]: 2629746
==>     Arg 4 [name=secret, type=bytes32]: 0x0e17ebdaf2948be6f1018484b16f1c92a09a21d6b08b6564065b276eb4370b58
==>
==> The nonce of the transaction would be 542.
==>
==> $$$ The transaction you have requested could use up to 183595 units of gas.
==> $$$ You would pay 1.5 gwei for each unit of gas, for a maximum cost of 0.0002753925 ether.
==> $$$ This is worth 0.05 USD (according to Coinbase at 1:58 AM).
==> $$$ You would also send 0.002353017483049142 ether (0.44 USD), for a maximum total cost of 0.002628409983049142 ether (0.49 USD).

Would you like to sign this transaction? [y/n] y

Using sender address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (with aliases ['default-sender','testing0'] on chain with ID 1), which is already unlocked.
Is that okay? [y/n] y

[info] A transaction with hash '0x1850d91bc4b718e93b7d2a86dece3e9863e68d546466a2c392e649d6b7c1ef93' has been submitted.
[info] Waiting up to 5 minutes for the transaction to be mined.
[info] Name 'smartelephant.eth' has been successfully registered to '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (with aliases ['default-sender','testing0'] on chain with ID 1)!
[info] The registration is valid until 'Thu, 12 Dec 2019 12:27:48 -0800'
[success] Total time: 172 s, completed Nov 12, 2019, 1:59:00 AM
```

@@@

### ensNameStatus

@@@ div { .keydesc}

**Usage:**
```
> ensNameStatus <ens-name>.eth
```
Looks up the status of an ENS name.

**Example:**
```
> ensNameStatus mchange.eth
[info] ENS name 'mchange.eth' is currently owned by '0xf0ed4a1ade1f4bbcc875275a9480c387dcdb185c'.
[info] This registration will expire at 'Sun, 3 May 2020 17:00:00 -0700'.
[success] Total time: 3 s, completed May 23, 2019 6:50:29 PM
```

@@@

### ensOwnerLookup

@@@ div { .keydesc}

**Usage:**
```
> ensOwnerLookup <ens-name>.eth
```

Looks up the owner on an ENS name (who is not necessarily the deed owner).

**Example:**
```
> ensOwnerLookup thisisadumbname.eth
The name 'thisisadumbname.eth' is owned by address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (with aliases ['default-sender','testing0'] on chain with ID 1).
[success] Total time: 1 s, completed Mar 16, 2019 10:14:00 PM
```

@@@

### ensOwnerSet

@@@ div { .keydesc}

**Usage:**
```
> ensOwnerSet <ens-name>.eth <owner-address-as-hex-or-ens-or-alias>
```

Sets the owner of an ENS name.

**Example:**
```
> ensOwnerSet thisisadumbname.eth steve-ens
[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************
[info] V3 wallet(s) found for '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (aliases ['default-sender','testing0'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x314159265dd8dbb310642f98f50c066173c1259b (with aliases ['ens'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  0x5b0fc9c3fe09d7b2a951becd6a6ab7e08c4ec2979ea216ccca363d514998e13479937f83000000000000000000000000f0ed4a1ade1f4bbcc875275a9480c387dcdb185c
==>   Value: 0 Ether
==>
==> The transaction is signed with Chain ID 1 (which correctly matches the current session's 'ethNodeChainId').
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: setOwner(bytes32,address)
==>     Arg 1 [name=node, type=bytes32]: 0xfe09d7b2a951becd6a6ab7e08c4ec2979ea216ccca363d514998e13479937f83
==>     Arg 2 [name=owner, type=address]: 0xf0ed4a1ade1f4bbcc875275a9480c387dcdb185c
==>
==> The nonce of the transaction would be 416.
==>
==> $$$ The transaction you have requested could use up to 38377 units of gas.
==> $$$ You would pay 1 gwei for each unit of gas, for a maximum cost of 0.000038377 ether.
==> $$$ This is worth 0.009403708195 USD (according to Coinbase at 6:58 PM).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0x44b17aa4c6a9b4ad35ba13911061e72f238b0e3d28e8235f5c834fe0eec82f1c' will be submitted. Please wait.
[info] The name 'thisisadumbname.eth' is now owned by '0xf0ed4a1ade1f4bbcc875275a9480c387dcdb185c' (with aliases ['steve-ens'] on chain with ID 1).
[success] Total time: 173 s, completed May 23, 2019 7:00:53 PM
```

@@@

### ensResolverLookup

@@@ div { .keydesc}

**Usage:**
```
> ensResolverLookup <ens-name>.eth
```

Looks up the resolver associated with a name.

**Example:**
```
> ensResolverLookup pejorative.eth
The name 'pejorative.eth' is associated with a resolver at address '0x1da022710df5002339274aadee8d58218e9d6ab5' (on chain with ID 1)'.
[success] Total time: 1 s, completed Mar 16, 2019 10:23:41 PM
```

@@@

### ensResolverSet


@@@ div { .keydesc}

**Usage:**
```
> ensResolverSet <ens-name>.eth [optional-resolver-address-as-hex-or-ens-or-alias]
```

Associates and ENS name with a resolver.

If you omit the resolver address, the current default public resolver is used. Which is usually what you want.

**Example (with resolver specified):**
```
ensResolverSet thisisadumbname.eth 0x1da022710df5002339274aadee8d58218e9d6ab5
[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************
[info] V3 wallet(s) found for '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (aliases ['default-sender','testing0'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x314159265dd8dbb310642f98f50c066173c1259b (with aliases ['ens'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  0x1896f70afe09d7b2a951becd6a6ab7e08c4ec2979ea216ccca363d514998e13479937f830000000000000000000000001da022710df5002339274aadee8d58218e9d6ab5
==>   Value: 0 Ether
==>
==> The transaction is signed with Chain ID 1 (which correctly matches the current session's 'ethNodeChainId').
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: setResolver(bytes32,address)
==>     Arg 1 [name=node, type=bytes32]: 0xfe09d7b2a951becd6a6ab7e08c4ec2979ea216ccca363d514998e13479937f83
==>     Arg 2 [name=resolver, type=address]: 0x1da022710df5002339274aadee8d58218e9d6ab5
==>
==> The nonce of the transaction would be 376.
==>
==> $$$ The transaction you have requested could use up to 56533 units of gas.
==> $$$ You would pay 1 gwei for each unit of gas, for a maximum cost of 0.000056533 ether.
==> $$$ This is worth 0.00774219435 USD (according to Coinbase at 10:28 PM).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0xf0b21ad569e2e7a8244f31c0ca455558a17c6cd86500d38e7ce374d189314210' will be submitted. Please wait.
[info] The name 'thisisadumbname.eth' is now set to be resolved by a contract at '0x1da022710df5002339274aadee8d58218e9d6ab5' (on chain with ID 1).
[success] Total time: 35 s, completed Mar 16, 2019 10:28:47 PM
```

**Example (with default public resolver):**
```
> ensResolverSet shiningmonkey.eth
[warn] No resolver specified. Using default public resolver '0x226159d592e2b063810a10ebf6dcbada94ed68b8'.

==> T R A N S A C T I O N   S I G N A T U R E   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x314159265dd8dbb310642f98f50c066173c1259b (with aliases ['ens'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  0x1896f70a49763e65c2efcc46b84722d1358e19f41fd5932f6db324800e39902828f451d5000000000000000000000000226159d592e2b063810a10ebf6dcbada94ed68b8
==>   Value: 0 ether
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: setResolver(bytes32,address)
==>     Arg 1 [name=node, type=bytes32]: 0x49763e65c2efcc46b84722d1358e19f41fd5932f6db324800e39902828f451d5
==>     Arg 2 [name=resolver, type=address]: 0x226159d592e2b063810a10ebf6dcbada94ed68b8
==>
==> The nonce of the transaction would be 524.
==>
==> $$$ The transaction you have requested could use up to 56610 units of gas.
==> $$$ You would pay 1.0000384 gwei for each unit of gas, for a maximum cost of 0.000056612173824 ether.
==> $$$ This is worth 0.01 USD (according to Coinbase at 4:41 AM).

Would you like to sign this transaction? [y/n] y

[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************

[info] A transaction with hash '0x35b7d30ecc1ca53c002b4476d88657a7d09054fa1a0724ffd917b9c2263fb842' has been submitted.
[info] Waiting up to 5 minutes for the transaction to be mined.
[info] The name 'shiningmonkey.eth' is now set to be resolved by a contract at '0x226159d592e2b063810a10ebf6dcbada94ed68b8' (with aliases ['ens-public-resolver-2019-10-24'] on chain with ID 1).
[success] Total time: 100 s, completed Nov 10, 2019 4:42:41 AM
```

@@@

### ensSubnodeCreate

@@@ div { .keydesc}

**Usage:**
```
> ensSubnodeCreate <full-subnode-ens-name>.eth
```

Creates a "subnode" for an existing ENS name.
The @ref:[current sender](./eth/address/sender.md) should be the owner of the parent name, and will become owner of the subname.

If there already exists a name like `zzz.eth`, you can create `yyy.zzz.eth` as a subnode. If there already exists `yyy.zzz.eth`, you can create `xxx.yyy.zzz.eth`.

**Example:**
```
> ensSubnodeCreate reallydumb.thisisadumbname.eth
[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************
[info] V3 wallet(s) found for '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (aliases ['default-sender','testing0'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x314159265dd8dbb310642f98f50c066173c1259b (with aliases ['ens'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  0x06ab5923fe09d7b2a951becd6a6ab7e08c4ec2979ea216ccca363d514998e13479937f839cf3a69ffb03e16355ebcd7e8c0af4f096daeda44425f4a0ec2f420279ab2780000000000000000000000000465e79b940bc2157e4259ff6b2d92f454497f1e4
==>   Value: 0 Ether
==>
==> The transaction is signed with Chain ID 1 (which correctly matches the current session's 'ethNodeChainId').
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: setSubnodeOwner(bytes32,bytes32,address)
==>     Arg 1 [name=node, type=bytes32]: 0xfe09d7b2a951becd6a6ab7e08c4ec2979ea216ccca363d514998e13479937f83
==>     Arg 2 [name=label, type=bytes32]: 0x9cf3a69ffb03e16355ebcd7e8c0af4f096daeda44425f4a0ec2f420279ab2780
==>     Arg 3 [name=owner, type=address]: 0x465e79b940bc2157e4259ff6b2d92f454497f1e4
==>
==> The nonce of the transaction would be 377.
==>
==> $$$ The transaction you have requested could use up to 59655 units of gas.
==> $$$ You would pay 2 gwei for each unit of gas, for a maximum cost of 0.00011931 ether.
==> $$$ This is worth 0.01633652175 USD (according to Coinbase at 10:33 PM).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0x4d83b26b2e91351a3b500d64d1f1dffcd8d2fe2cdf06337a2fd11a7c12f2c7a7' will be submitted. Please wait.
[info] The name 'reallydumb.thisisadumbname.eth' now exists, with owner '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (with aliases ['default-sender','testing0'] on chain with ID 1).
[success] Total time: 30 s, completed Mar 16, 2019 10:33:25 PM
```

@@@

### ensSubnodeOwnerSet

@@@ div { .keydesc}

**Usage:**
```
> ensSubnodeOwnerSet <full-subnode-ens-name>.eth <subnode-owner-as-hex-or-ens-or-alias>
```

Resets the owner of a "subnode" for an existing ENS name.
The @ref:[current sender](./eth/address/sender.md) should be the owner of the parent name.

If the subnode does not already exist, but the parent name does, the subnode will be automatically created.
(If there already exists a name like `zzz.eth`, you can create `yyy.zzz.eth` as a subnode. If there already exists `yyy.zzz.eth`, you can create `xxx.yyy.zzz.eth`.)

**Example:**
```
> ensSubnodeOwnerSet reallydumb.thisisadumbname.eth 0xae79b77e31387a3b2409b70c27cebc7220101026
[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************
[info] V3 wallet(s) found for '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (aliases ['default-sender','testing0'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x314159265dd8dbb310642f98f50c066173c1259b (with aliases ['ens'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  0x06ab5923fe09d7b2a951becd6a6ab7e08c4ec2979ea216ccca363d514998e13479937f839cf3a69ffb03e16355ebcd7e8c0af4f096daeda44425f4a0ec2f420279ab2780000000000000000000000000ae79b77e31387a3b2409b70c27cebc7220101026
==>   Value: 0 Ether
==>
==> The transaction is signed with Chain ID 1 (which correctly matches the current session's 'ethNodeChainId').
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: setSubnodeOwner(bytes32,bytes32,address)
==>     Arg 1 [name=node, type=bytes32]: 0xfe09d7b2a951becd6a6ab7e08c4ec2979ea216ccca363d514998e13479937f83
==>     Arg 2 [name=label, type=bytes32]: 0x9cf3a69ffb03e16355ebcd7e8c0af4f096daeda44425f4a0ec2f420279ab2780
==>     Arg 3 [name=owner, type=address]: 0xae79b77e31387a3b2409b70c27cebc7220101026
==>
==> The nonce of the transaction would be 378.
==>
==> $$$ The transaction you have requested could use up to 41655 units of gas.
==> $$$ You would pay 2 gwei for each unit of gas, for a maximum cost of 0.00008331 ether.
==> $$$ This is worth 0.01142888235 USD (according to Coinbase at 10:41 PM).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0x5cc524c6d4c065491e77f41dd5ce72ec6164b1accf481351ba0f27cc31c313fa' will be submitted. Please wait.
[info] The name 'reallydumb.thisisadumbname.eth' now exists, with owner '0xae79b77e31387a3b2409b70c27cebc7220101026' (with aliases ['testing1'] on chain with ID 1).
[success] Total time: 41 s, completed Mar 16, 2019 10:41:34 PM
```

@@@


