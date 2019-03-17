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

### ensAuctionBid

@@@ div { .keydesc}

**Usage:**
```
> ensAuctionBid <ens-name>.eth <amount-to-bid> [optional overpayment to obscure amount]
```
This is a shorthand for @ref:[`ensAuctionBidPlace`](#ensauctionbidplace). Please see that command for more information.

@@@

### ensAuctionBidList

@@@ div { .keydesc}

**Usage:**
```
> ensAuctionBidList
```
Lists the bids placed and retained in the _sbt-ethereum_ "shoebox" database, and information about the bids' status.

**Example:**
```
> ensAuctionBidList
+--------------------------------------------------------------------+-------------+--------------------------------------------+------+--------------------------------------------------------------------+---------------------------------+----------+----------+---------+
| Bid Hash                                                           | Simple Name | Bidder Address                             | ETH  | Salt                                                               | Timestamp                       | Accepted | Revealed | Removed |
+--------------------------------------------------------------------+-------------+--------------------------------------------+------+--------------------------------------------------------------------+---------------------------------+----------+----------+---------+
| 0xc07b92e38cea6a00c7d38bd2fbdae25c4da4f299ab799abde93e2652a1cb3772 | octopodes   | 0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2 | 0.01 | 0x30d1107839513e31ba0bfe2001d7a796fdbdd012544e9f198f45daf9ff44ec48 | Sat, 16 Mar 2019 23:21:02 -0700 | true     | false    | false   |
+--------------------------------------------------------------------+-------------+--------------------------------------------+------+--------------------------------------------------------------------+---------------------------------+----------+----------+---------+
[success] Total time: 0 s, completed Mar 16, 2019 11:22:05 PM
```

@@@

### ensAuctionBidPlace

@@@ div { .keydesc}

**Usage:**
```
> ensAuctionBidPlace <ens-name>.eth <amount-to-bid> [optional overpayment to obscure amount]
```
For a name in status [`Auction`], adds a bid for the name. The bid amount any any overpayment will be sent to the smart contract managing ENS auctions.

_**A submitted bid must be @ref:[revealed](#ensauctionbidreveal) during the reveal phase of the auction, or you may lose your funds! You can always find when the reveal phase will be via [`ensNameStatus`](#ensnamestatus).**_

**Example:**
```
> ensAuctionBidPlace stochasticism.eth 0.01 ether
Using sender address '0xf0ed4a1ade1f4bbcc875275a9480c387dcdb185c' (on chain with ID 1, aliases ['steve-ens']). OK? [y/n] y

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x6090a6e47849629b7245dfa1ca21d94cd15878ef (with aliases ['ens-resolver'] on chain with ID 1)
==>   From:  0xf0ed4a1ade1f4bbcc875275a9480c387dcdb185c (with aliases ['steve-ens'] on chain with ID 1)
==>   Data:  0xce92dced183d94848d2bfbe42468e71ee7c994059f5247703e4ff1b0916d4bf7d416276d
==>   Value: 0.01 Ether
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: newBid(bytes32)
==>     Arg 1 [name=sealedBid, type=bytes32]: 0x183d94848d2bfbe42468e71ee7c994059f5247703e4ff1b0916d4bf7d416276d
==>
==> The nonce of the transaction would be 126.
==>
==> $$$ The transaction you have requested could use up to 502123 units of gas.
==> $$$ You would pay 5 gwei for each unit of gas, for a maximum cost of 0.002510615 ether.
==> $$$ This is worth 0.336510281525 USD (according to Coinbase at 4:27 PM).
==> $$$ You would also send 0.01 ether (1.34035 USD), for a maximum total cost of 0.012510615 ether (1.676860281525 USD).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0x63667255455b20ef381bcb6e5d6a3e50c33025c8f7e0a920b22f25a53faa8423' will be submitted. Please wait.
[warn] A bid has been placed on name 'stochasticism.eth' for 10000000000000000 wei.
[warn] YOU MUST REVEAL THIS BID BETWEEN Sat, 2 Mar 2019 16:26:30 -0800 AND Mon, 4 Mar 2019 16:26:30 -0800. IF YOU DO NOT, YOUR FUNDS WILL BE LOST!
[warn] Bid details, which are required to reveal, have been automatically stored in the sbt-ethereum shoebox,
[warn] and will be provided automatically if revealed by this client, configured with chain ID 1.
[warn] However, it never hurts to be neurotic. You may wish to note:
[warn]     Simple Name:      stochasticism
[warn]     Simple Name Hash: 0x490eb1ec7c5fdb9a86c6ff3483eb47e53034d15e0d615fb88ee87f027903ed35
[warn]     Bidder Address:   0xf0ed4a1ade1f4bbcc875275a9480c387dcdb185c
[warn]     Value In Wei:     10000000000000000
[warn]     Salt:             0x95865ce04f9cd079b8b108bbf6b37d2414f564e497860820781cc136ee994043
[warn]     Full Bid Hash:    0x183d94848d2bfbe42468e71ee7c994059f5247703e4ff1b0916d4bf7d416276d
[success] Total time: 59 s, completed Feb 27, 2019 4:28:23 PM
```
@@@

### ensAuctionBidReveal

@@@ div { .keydesc}

**Usage:**
```
> ensAuctionBidReveal <ens-name.eth-OR-bid-hash>
```

Reveals a bid for an ENS name, after the @ref:[`Auction`](#ensnamestatus) phase has ended and the @ref:[`Reveal`](#ensnamestatus) phase has begun.

**Example:**
```
> ensAuctionBidReveal stochasticism.eth
[info] Unlocking address '0xf0ed4a1ade1f4bbcc875275a9480c387dcdb185c' (on chain with ID 1, aliases ['steve-ens'])
Enter passphrase or hex private key for address '0xf0ed4a1ade1f4bbcc875275a9480c387dcdb185c': ************************
[info] V3 wallet(s) found for '0xf0ed4a1ade1f4bbcc875275a9480c387dcdb185c' (aliases ['steve-ens'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x6090a6e47849629b7245dfa1ca21d94cd15878ef (with aliases ['ens-resolver'] on chain with ID 1)
==>   From:  0xf0ed4a1ade1f4bbcc875275a9480c387dcdb185c (with aliases ['steve-ens'] on chain with ID 1)
==>   Data:  0x47872b42490eb1ec7c5fdb9a86c6ff3483eb47e53034d15e0d615fb88ee87f027903ed35000000000000000000000000000000000000000000000000002386f26fc1000095865ce04f9cd079b8b108bbf6b37d2414f564e497860820781cc136ee994043
==>   Value: 0 Ether
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: unsealBid(bytes32,uint256,bytes32)
==>     Arg 1 [name=_hash, type=bytes32]: 0x490eb1ec7c5fdb9a86c6ff3483eb47e53034d15e0d615fb88ee87f027903ed35
==>     Arg 2 [name=_value, type=uint256]: 10000000000000000
==>     Arg 3 [name=_salt, type=bytes32]: 0x95865ce04f9cd079b8b108bbf6b37d2414f564e497860820781cc136ee994043
==>
==> The nonce of the transaction would be 127.
==>
==> $$$ The transaction you have requested could use up to 115339 units of gas.
==> $$$ You would pay 2.5 gwei for each unit of gas, for a maximum cost of 0.0002883475 ether.
==> $$$ This is worth 0.0384410469625 USD (according to Coinbase at 7:26 PM).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0x175a26ec49593f0af853c5eb7bcbde292ca0a7e7fdba21810e3b60a460944cf6' will be submitted. Please wait.
[info] Bid with name 'stochasticism.eth' was successfully revealed.
[success] Total time: 34 s, completed Mar 2, 2019 7:26:58 PM
```

@@@

### ensAuctionFinalize

### ensAuctionStart

@@@ div { .keydesc}

**Usage:**
```
> ensAuctionStart <ens-name>.eth
```

Starts an auction for an @ref:[`Open`](#ensnamestatus) ENS name. (Converts its status to `Auction`.)

**Example:**
```
> ensAuctionStart stochasticism.eth
[info] Unlocking address '0xf0ed4a1ade1f4bbcc875275a9480c387dcdb185c' (on chain with ID 1, aliases ['steve-ens'])
Enter passphrase or hex private key for address '0xf0ed4a1ade1f4bbcc875275a9480c387dcdb185c': ************************
[info] V3 wallet(s) found for '0xf0ed4a1ade1f4bbcc875275a9480c387dcdb185c' (aliases ['steve-ens'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x6090a6e47849629b7245dfa1ca21d94cd15878ef (with aliases ['ens-resolver'] on chain with ID 1)
==>   From:  0xf0ed4a1ade1f4bbcc875275a9480c387dcdb185c (with aliases ['steve-ens'] on chain with ID 1)
==>   Data:  0xe27fe50f00000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000000000001490eb1ec7c5fdb9a86c6ff3483eb47e53034d15e0d615fb88ee87f027903ed35
==>   Value: 0 Ether
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: startAuctions(bytes32[])
==>     Arg 1 [name=_hashes, type=bytes32[]]: [0x490eb1ec7c5fdb9a86c6ff3483eb47e53034d15e0d615fb88ee87f027903ed35]
==>
==> The nonce of the transaction would be 125.
==>
==> $$$ The transaction you have requested could use up to 72660 units of gas.
==> $$$ You would pay 5 gwei for each unit of gas, for a maximum cost of 0.0003633 ether.
==> $$$ This is worth 0.0486912825 USD (according to Coinbase at 4:26 PM).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0x63223c2b7ae0a0d5a56c96b5f0d4d45986476dd521e3b16835536394e3804343' will be submitted. Please wait.
Auction started for name 'stochasticism.eth'.
[success] Total time: 74 s, completed Feb 27, 2019 4:26:46 PM
```

@@@

### ensDeedRelease

**Usage:**
```
> ensDeedRelease <ens-name>.eth
```

Releases the deed that is the ultimate authority of ownership of an auction-allocated ENS name.

The deed can only be released by the current deed owner, and cannot be released until at least a year has passed since the name's auction.

_**This task is tentatively implemented, but has not yet been tried or tested at all**_

### ensDeedTransfer

@@@ div { .keydesc}

**Usage:**
```
> ensDeedTransfer <ens-name>.eth <transferee-address-as-hex-or-ens-or-alias>
```

Transfers ownership of an ENS name's "deed" (and of the ETH deposit it contains) to the transferee address.

This may only be successfully performed by the current owner of the deed.

_**This represents a permanent and irrevocable change of ownership!**_

**Example:**
```
> ensDeedTransfer prognosis.eth steve-ens
[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************
[info] V3 wallet(s) found for '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (aliases ['default-sender','testing0'])
[warn] This will permanently transfer the deed associated with 'prognosis.eth', and any deplosit paid to secure that deed, to '0xf0ed4a1ade1f4bbcc875275a9480c387dcdb185c' (with aliases ['steve-ens'] on chain with ID 1).
Are you sure you want to do this? [y/n] y

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x6090a6e47849629b7245dfa1ca21d94cd15878ef (with aliases ['ens-resolver'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  0x79ce9facd1a772c558431b7036eaf21a1e0a79d8d43d3b59e323305d2e5b06f32c6c8c88000000000000000000000000f0ed4a1ade1f4bbcc875275a9480c387dcdb185c
==>   Value: 0 Ether
==>
==> The transaction is signed with Chain ID 1 (which correctly matches the current session's 'ethNodeChainId').
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: transfer(bytes32,address)
==>     Arg 1 [name=_hash, type=bytes32]: 0xd1a772c558431b7036eaf21a1e0a79d8d43d3b59e323305d2e5b06f32c6c8c88
==>     Arg 2 [name=newOwner, type=address]: 0xf0ed4a1ade1f4bbcc875275a9480c387dcdb185c
==>
==> The nonce of the transaction would be 371.
==>
==> $$$ The transaction you have requested could use up to 67561 units of gas.
==> $$$ You would pay 2 gwei for each unit of gas, for a maximum cost of 0.000135122 ether.
==> $$$ This is worth 0.018146208990 USD (according to Coinbase at 12:06 AM).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0x2e577ed585af32b60c6a0b8ea1ff0a434b0f7e49ee9d1cc30fe9d3e8422dfe65' will be submitted. Please wait.
[info] The deed for 'prognosis.eth' has been permanently transferred to '0xf0ed4a1ade1f4bbcc875275a9480c387dcdb185c' (with aliases ['steve-ens'] on chain with ID 1).
[success] Total time: 87 s, completed Mar 10, 2019 12:08:07 AM
```

@@@

### ensNameStatus

@@@ div { .keydesc}

**Usage:**
```
> ensNameStatus <ens-name>.eth
```
Looks up the status of an ENS name.

Status will be one of...

@@@@ div { .tight }

* `Open` &mdash; name is available and the auction hasn’t started
* `Auction` &mdash; name is available and the auction has been started
* `Owned` &mdash; name is taken and currently owned by someone
* `Forbidden` &mdash; name is forbidden
* `Reveal` &mdash; name is currently in the 'reveal' stage of the auction
* `NotYetAvailable` &mdash; name is not yet available due to the 'soft launch' of names

@@@@

_Status definitions lifted from the [ENS docs](https://docs.ens.domains/en/latest/userguide.html#starting-an-auction)._

**Example:**
```
> ensNameStatus stochasticism.eth
The current status of ENS name 'stochasticism.eth' is 'Auction'.
Bidding ends, and the reveal phase will begin on Sat, 2 Mar 2019 16:26:30 -0800.
The reveal phase will end, and the auction can be finalized on Mon, 4 Mar 2019 16:26:30 -0800.
[success] Total time: 3 s, completed Feb 27, 2019 5:07:12 PM
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
> ensResolverSet <ens-name>.eth <resolver-address-as-hex-or-ens-or-alias>
```

Associates and ENS name with a resolver. (You may usually wish to use the default plublic resolver `0x1da022710df5002339274aadee8d58218e9d6ab5`.)

**Example:**
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

@@@

### ensSubnodeCreate

@@@ div { .keydesc}

**Usage:**
```
> ensSubnodeCreate <full-subnode-ens-name>.eth
```

Creates a "subnode" to (a hierarchical name beneath) an existing ENS name.
The current sender should be the owner of the parent name, and will become owner of the subname.

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

Resets the owner of a "subnode" to (a hierarchical name beneath) an existing ENS name.
The current sender should be the owner of the parent name.

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


