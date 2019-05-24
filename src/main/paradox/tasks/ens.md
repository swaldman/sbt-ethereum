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

### ensMigrateRegistrar

@@@ div { .keydesc}

**Usage:**
```
> ensMigrateRegistrar <ens-name>.eth
```

Migrate a name registered from an obsoleted registrar (in practice, the [original Vickery Auction registrar](https://medium.com/the-ethereum-name-service/ens-is-upgrading-heres-what-you-need-to-do-f26423339fcf))
onto the current registrar for its domain (which is usually `eth`).

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


