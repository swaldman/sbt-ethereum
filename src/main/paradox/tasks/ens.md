# ens*

### ensAddressLookup

### ensAddressSet

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


### ensAuctionBidList

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

### ensNameStatus

@@@ div { .keydesc}

**Usage:**
```
> ensNameStatus <ens-name>.eth
```
Looks up the status of an ENS name.

Status will be one of...
 * Open &mdash; name is available and the auction hasn’t started
 * Auction &mdash; name is available and the auction has been started
 * Owned &mdash; name is taken and currently owned by someone
 * Forbidden &mdash; name is forbidden
 * Reveal &mdash; name is currently in the ‘reveal’ stage of the auction
 * NotYetAvailable &mdash; name is not yet available due to the ‘soft launch’ of names

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

### ensOwnerSet

### ensResolverLookup

### ensResolverSet

### ensSubnodeCreate

### ensSubnodeOwnerSet
