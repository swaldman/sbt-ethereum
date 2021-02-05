# Abi*

_Contract ABIs_ define the interface by which clients typically interact with _Ethereum_ smart contracts. They are defined
as JSON artifacts. Here's an ABI for [a very simple contract](https://etherscan.io/address/0xe599f637dfb705e56589951a5d777afdaf618b5b#code):
```
[{"constant":true,"inputs":[],"name":"whoAmI","outputs":[{"name":"me","type":"address"}],"payable":false,"stateMutability":"view","type":"function"}]
```
In order to work with a smart contract in _sbt-ethereum_, you need to associate it's address with an ABI.

When a smart contract is compiled, an ABI is often generated as a compilation artifact. However, there is not necessarily
a one-to-one mapping between contracts and ABIs. For example, one mode of deploying _Ethereum_ smart contracts is as a
"[storage proxy](https://blog.zeppelinos.org/proxy-patterns/)", whose user-facing interface is determined by the contract
it delegates to rather than anything the compiler might know about the contract. Even if you do not have "the ABI" for a contract,
you may know that it implements some standard interface (like the [ERC20 token standard](https://en.wikipedia.org/wiki/ERC-20), for example),
in which case you can interact with the contract through an ABI that describes that interface.

**Default Contract ABIs**

When you compile and deploy a smart contract with _sbt-ethereum_, the deployment address automatically becomes the
_default contract ABI_ for the contract. When you wish to interact with a smart contract you have not yourself deployed (or if
the compilation ABI is not the appropriate interface), you can set associate a default ABI with an address via
@ref:[ethContractAbiDefaultImport](#ethContractAbiDefaultImport) or @ref:[ethContractAbiDefaultSet](#ethContractAbiDefaultSet).

Default ABI associations are store in the _sbt-ethereum_ "shoebox" and are persistent. Once set, they will remain unless they are
overwritten or explicitly dropped.

**Override Contract ABIs**

Sometimes you may temporarily wish to use an ABI that should not be the default ABI for a contract. _ABI overrides_ define
temporary associations of ABIs with contract addresses that only endure within a single _sbt-ethereum_ session.

**Aliases**<a name="aliases"></a>

When _sbt-ethereum_ commands expect an ABI, you can refer to it via the address of a contract for which that ABI is already
the default. (You can also refer to ABIs by their hashes, which _sbt-ethereum_ computes after normalizing the JSON ABIs by
removing unnecessary spaces, and sometimes exposes, see e.g. [ethContractAbiAliasList](#ethContractAbiAliasList).)

However, it may be convenient to name ABIs you interact with frequently and may wish to reuse. So _sbt-ethereum_
permits you to define _ABI aliases_. To refer to ABI aliases where _sbt-ethereum_ expects an ABI, prefix the alias name with `abi:`.

_sbt-ethereum_ also defines aliases for some standard ABIs. These are prefixed with `abi:standard:`. For the moment, the only
supported standard ABI alias is `abi:standard:erc20`.

**Function Call Encoding and Decoding**

A contracts functions and the form its calls will take (arguments, return values) are specified by contract ABIs.
@ref:[ethContractAbiCallEncode](#ethContractAbiCallEncode) and @ref:[ethContractAbiCallDecode](#ethContractAbiCallDecode)
permit you to generate the hex bytes that a function call translates to, or to decode those bytes back into a function name
and arguments.

### ethContractAbiAliasDrop

@@@ div { .keydesc}

**Usage:**
```
> ethContractAbiAliasDrop <abi-alias>
```
Removes an alias for a contract abi from the  _sbt-ethereum_ shoebox database.

**Example:**
```
> ethContractAbiAliasDrop abi:whoami
[info] Abi alias 'abi:whoami' successfully dropped.
[info] Refreshing caches.
[success] Total time: 0 s, completed Jan 27, 2019 11:16:29 PM
```
@@@

### ethContractAbiAliasList

@@@ div { .keydesc}

**Usage:**
```
> ethContractAbiAliasList
```
Lists all ABI aliases that have been defined in the _sbt-ethereum_ shoebox database.

**Example:**
```
> ethContractAbiAliasList
+--------------------+--------------------------------------------------------------------+
| ABI Alias          | ABI Hash                                                           |
+--------------------+--------------------------------------------------------------------+
| abi:fortune        | 0x1c40488a3a264071e539f1a36abe69e4ade3751b15d839af83e015fc2dc6be12 |
| abi:standard:erc20 | 0xa405c7571bcf24cac5ee2f1280f3dee84133398287f650235c093a1384c9a2dd |
| abi:whoami         | 0x093def1bd67f0c4c3f6d578cd27f5135d1fa62e2f652fab0ee79933b23a37c27 |
+--------------------+--------------------------------------------------------------------+
[success] Total time: 0 s, completed Jan 27, 2019 11:20:33 PM
```
@@@

### ethContractAbiAliasSet

@@@ div { .keydesc }

**Usage:**
```
> ethContractAbiAliasSet <new-abi-alias> <abi-as-hex-address-or-address-alias-or-ens-address-or-abi-hash-or-abi-alias>
```
Defines a new ABI alias, based on the address of a contract associated with the ABI, an ABI hash, or an existing ABI alias.

**Examples:**
```
> ethContractAbiAliasSet whoami 0xe599f637dfb705e56589951a5d777afdaf618b5b
> ethContractAbiAliasSet whoami who-am-i
> ethContractAbiAliasSet whoami who-am-i.eth
> ethContractAbiAliasSet whoami 0x093def1bd67f0c4c3f6d578cd27f5135d1fa62e2f652fab0ee79933b23a37c27
> ethContractAbiAliasSet whoami abi:some-other-alias
```

**Example response:**
```
[info] Abi alias 'abi:whoami' successfully bound to ABI found via ABI associated with contract address '0xe599f637dfb705e56589951a5d777afdaf618b5b' on chain with ID 1.
[info] Refreshing caches.
[success] Total time: 0 s, completed Jan 27, 2019 11:34:46 PM
```

@@@

### ethContractAbiCallDecode

@@@ div { .keydesc}

**Usage:**
```
> ethContractAbiCallDecode <abi-as-hex-address-or-address-alias-or-ens-address-or-abi-hash-or-abi-alias> <hex-encoded-function-call>
```
Decodes a hex-encoded function call (second argument) back into the function name and arguments, based on a specified ABI (first argument).

**Example:**
```
> ethContractAbiCallDecode abi:fortune 0x4cf373e6000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000135468697320697320616e206578616d706c652e00000000000000000000000000
Function called: addFortune(string)
   Arg 1 [name=fortune, type=string]: "This is an example."
[success] Total time: 0 s, completed Jan 27, 2019 11:50:18 PM
```
@@@

### ethContractAbiCallEncode

@@@ div { .keydesc}

**Usage:**
```
> ethContractAbiCallEncode <abi-as-hex-address-or-address-alias-or-ens-address-or-abi-hash-or-abi-alias> <function-name> [<function-args-if-any>*]
```
Encodes a function call with its arguments (if any) into a hex message, based on a specified ABI (first argument).

**Example:**
```
> ethContractAbiCallEncode abi:fortune addFortune "This is an example."
Encoded data:
0x4cf373e6000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000135468697320697320616e206578616d706c652e00000000000000000000000000
[success] Total time: 0 s, completed Jan 27, 2019 11:49:50 PM
```
@@@

### ethContractAbiDefaultDrop

@@@ div { .keydesc }

**Usage:**
```
> ethContractAbiDefaultDrop <address-as-hex-or-ens-or-alias>
```
Removes any default contract ABI that may have been associated with an address for the current [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID.

**Examples:**
```
> ethContractAbiDefaultDrop 0x82ea8ab1e836272322f376a5f71d5a34a71688f1
> ethContractAbiDefaultDrop fortune
> ethContractAbiDefaultDrop some-address.eth
```

**Example response:**
```
[info] Previously imported or set ABI for contract with address '0x82ea8ab1e836272322f376a5f71d5a34a71688f1' (on chain with ID 1) has been dropped.
[success] Total time: 0 s, completed Feb 15, 2019 4:28:47 PM
```

@@@

### ethContractAbiDefaultList

@@@ div { .keydesc }

**Usage:**
```
> ethContractAbiDefaultList [optional-regex-to-filter-rows]
```
Lists all addresses and contract ABIs for which a default association has been defined, either explicitly or automatically upon deployment,
for the current [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID.

**Example:**
```
> ethContractAbiDefaultList
+--------------------------------------------+--------------------------------------------------------------------+----------+
| Address                                    | ABI Hash                                                           | Source   |
+--------------------------------------------+--------------------------------------------------------------------+----------+
| 0x82ea8ab1e836272322f376a5f71d5a34a71688f1 | 0x1c40488a3a264071e539f1a36abe69e4ade3751b15d839af83e015fc2dc6be12 | Imported | <-- address aliases: ['fortune','fortune3']
+--------------------------------------------+--------------------------------------------------------------------+----------+
[success] Total time: 0 s, completed Feb 15, 2019 4:36:10 PM
```

@@@

### ethContractAbiDefaultImport

@@@ div { .keydesc }

**Usage:**
```
> ethContractAbiDefaultImport <address-as-hex-or-ens-or-alias>
```
Imports an ABI into the _sbt-ethereum_ shoebox database, and associates it as the default ABI for the given address for the current [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID.

ABIs can be imported manually by cut-and-paste. They can also be imported automatically from _Etherscan_, if @ref:[an _Etherscan_ API key has been set](../../etherscan.md).

_For examples, please see the tutorial section @ref["Acquiring an ABI for a smart contract"](../../../tutorials/using-a-smart-contract-i.md#acquiring-an-abi-for-a-smart-contract)._

@@@

@@@ warning

**Directly pasted ABIs cannot include newlines!**

If you are directly pasting an ABI into the console (rather than importing from _Etherscan_), it can't be a pretty-printed ABI with newlines. _sbt-ethereum_ expects you to "hit return" only after
you have pasted the ABI text. If you have a pretty-printed ABI, condense it, for example using sites like [this](https://codebeautify.org/jsonminifier).

@@@

### ethContractAbiDefaultSet

@@@ div { .keydesc }

**Usage:**
```
> ethContractAbiDefaultSet <address-as-hex-or-ens-or-alias> <abi-as-hex-address-or-address-alias-or-ens-address-or-abi-hash-or-abi-alias>
```

Creates a default association between an address and an ABI for the current [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID.

Addresses may be specified as _sbt-ethereum_ aliases, ENS addresses, or raw hex. ABIs may be specified by explicit ABI aliases, hex ABI hashes, via any address currently associated with the same ABI.

**Example:**
```
> ethContractAbiDefaultSet 0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2 abi:standard:erc20
[info] The ABI previously associated with ABI alias 'abi:standard:erc20' (on chain with ID 1) ABI has been associated with address 0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2.
Enter an optional alias for the address '0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2', now associated with the newly matched ABI (or [return] for none): WETH
[info] Alias 'WETH' now points to address '0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2' (for chain with ID 1).
[info] Refreshing caches.
[success] Total time: 8 s, completed Feb 15, 2019 5:14:34 PM
```

@@@

### ethContractAbiImport

@@@ div { .keydesc }

**Usage:**
```
> ethContractAbiImport [<address-as-hex-or-ens-or-alias>]
```
If an address is supplied, this is a shorthand for @ref:[`ethContractAbiDefaultImport`](#ethcontractabidefaultimport). Please see that command for more information.
It imports (either via _Etherscan_ or by copy-and-paste) an ABI as the _default ABI_ associated with the given address.

If no address is supplied, it imports the ABI and then prompts your for an @ref:[ABI alias](#aliases), by which you can refer to the ABI.
Once you have a named ABI, use tasks like @ref:[`ethContractAbiOverrideSet`](#ethcontractabioverrideset), @ref:[`ethContractAbiDefaultSet`](#ethcontractabidefaultset), and @ref:[`ethContractAbiPrintPretty`](#ethcontractabiprintpretty) 

**Example:**

```
> ethContractAbiImport
You are importing an ABI unattached to any contract address. You must provide an alias, so you can refer to it later.
Contract ABI: [{"outputs":[],"constant":false,"payable":false,"inputs":[{"name":"fortune","type":"string","internalType":"string"}],"name":"addFortune","stateMutability":"nonpayable","type":"function"},{"outputs":[{"name":"count","type":"uint256","internalType":"uint256"}],"constant":true,"payable":false,"inputs":[],"name":"countFortunes","stateMutability":"view","type":"function"},{"outputs":[{"name":"fortune","type":"string","internalType":"string"}],"constant":true,"payable":false,"inputs":[],"name":"drawFortune","stateMutability":"view","type":"function"},{"outputs":[{"name":"","type":"string","internalType":"string"}],"constant":true,"payable":false,"inputs":[{"name":"","type":"uint256","internalType":"uint256"}],"name":"fortunes","stateMutability":"view","type":"function"},{"inputs":[{"name":"author","type":"address","indexed":false,"internalType":"address"},{"name":"fortune","type":"string","indexed":false,"internalType":"string"}],"name":"FortuneAdded","anonymous":false,"type":"event"},{"payable":false,"inputs":[{"name":"initialFortune","type":"string","internalType":"string"}],"stateMutability":"nonpayable","type":"constructor"}]
Please enter an alias for this ABI: fortune
[info] The ABI has been successfully imported, with alias 'abi:fortune'.
[success] Total time: 22 s, completed Feb 4, 2021, 8:54:09 PM
```

@@@

@@@ warning

**Directly pasted ABIs cannot include newlines!**

If you are directly pasting an ABI into the console (rather than importing from _Etherscan_), it can't be a pretty-printed ABI with newlines. _sbt-ethereum_ expects you to "hit return" only after
you have pasted the ABI text. If you have a pretty-printed ABI, condense it, for example using sites like [this](https://codebeautify.org/jsonminifier).

@@@

### ethContractAbiOverride

@@@ div { .keydesc }

**Usage:**
```
> ethContractAbiOverride <address-as-hex-or-ens-or-alias> <abi-as-hex-address-or-address-alias-or-ens-address-or-abi-hash-or-abi-alias>
```
This is a shorthand for @ref:[`ethContractAbiOverrideSet`](#ethContractAbiOverrideSet). Please see that command for more information.

@@@

### ethContractAbiOverrideDrop

@@@ div { .keydesc }

**Usage:**
```
> ethContractAbiOverrideDrop <address-as-hex-or-ens-or-alias>
```
Drops any session override of the association between an address and a contract ABI that may have been set (for the current [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID).

**Example:**
```
> ethContractAbiOverrideDrop fortune
[info] ABI override successfully dropped.
[info] Refreshing caches.
[success] Total time: 0 s, completed Feb 16, 2019 12:31:00 PM
```

@@@

### ethContractAbiOverrideDropAll

@@@ div { .keydesc }

**Usage:**
```
> ethContractAbiOverrideDropAll
```
Drops _all_ session overrides of associations between an address and a contract ABI that may have been set (for the current [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID).

**Example:**
```
> ethContractAbiOverrideDropAll
[info] ABI overrides on chain with ID 1 successfully dropped.
[info] Refreshing caches.
[success] Total time: 0 s, completed Feb 16, 2019 12:35:46 PM
```

@@@

### ethContractAbiOverrideList

@@@ div { .keydesc }

**Usage:**
```
> ethContractAbiOverrideDropList
```
Lists any session overrides of associations between an address and a contract ABI that may have been set (for the current [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID).

**Example:**
```
> ethContractAbiOverrideList
+--------------------------------------------+--------------------------------------------------------------------+
| ABI Override Addresses                     | ABI Hash                                                           |
+--------------------------------------------+--------------------------------------------------------------------+
| 0x82ea8ab1e836272322f376a5f71d5a34a71688f1 | 0x1c40488a3a264071e539f1a36abe69e4ade3751b15d839af83e015fc2dc6be12 | <-- address aliases: ['fortune','fortune3'], abi alias: 'abi:oracle'
+--------------------------------------------+--------------------------------------------------------------------+
[success] Total time: 0 s, completed Feb 16, 2019 12:36:27 PM
```

@@@

### ethContractAbiOverridePrint <address-as-hex-or-ens-or-alias>

@@@ div { .keydesc }

**Usage:**
```
> ethContractAbiOverridePrint fortune
```
Pretty-prints the JSON of the session override of the contract ABI associated with an ABI (if any).

**Example:**
```
> ethContractAbiOverridePrint fortune
Session override of contract ABI for address '0x82ea8ab1e836272322f376a5f71d5a34a71688f1':
[ {
  "name" : "drawFortune",
  "inputs" : [ ],
  "outputs" : [ {
    "name" : "fortune",
    "type" : "string"
  } ],
  "constant" : true,
  "payable" : false,
  "stateMutability" : "view",
  "type" : "function"
} ]
[success] Total time: 0 s, completed Feb 16, 2019 12:44:19 PM
```

@@@

### ethContractAbiOverrideSet

@@@ div { .keydesc }

**Usage:**
```
> ethContractAbiOverrideSet <address-as-hex-or-ens-or-alias> <abi-as-hex-address-or-address-alias-or-ens-address-or-abi-hash-or-abi-alias>
```
Creates an association between an address and a contract ABI that will override any default association that may have been set, but only for the current session (and [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID).

**Example:**
```
> ethContractAbiOverrideSet 0x82ea8ab1e836272322f376a5f71d5a34a71688f1 abi:oracle
[info] ABI override successfully set.
[info] Refreshing caches.
[success] Total time: 0 s, completed Feb 16, 2019 12:21:23 PM
```

@@@

### ethContractAbiPrint

@@@ div { .keydesc }

**Usage:**
```
> ethContractAbiPrint <address-as-hex-or-ens-or-alias>
```
This is a shorthand for @ref:[`ethContractAbiPrintCompact`](#ethContractAbiPrintCompact). Please see that command for more information.

@@@


### ethContractAbiPrintCompact


@@@ div { .keydesc }

**Usage:**
```
> ethContractAbiPrintCompact <address-as-hex-or-ens-or-alias>
```

Compactly prints the ABI currently effective and associated with an address (for the current [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID).

If there is a session override set (see @ref:[`ethContractAbiOverrideSet`](#ethContractAbiOverrideSet)), it will be the override ABI.

If there is no session override set, it will be the default contract ABI, either defines at deployment time or explicitly imported.

If neither a session override nor default ABI is defined for the address, it will let you know that.

**Example:**
```
> ethContractAbiPrintCompact WETH
Contract ABI for ABI associated with contract address '0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2' on chain with ID 1:
[{"name":"allowance","inputs":[{"name":"tokenOwner","type":"address"},{"name":"spender","type":"address"}],"outputs":[{"name":"remaining","type":"uint256"}],"constant":true,"payable":false,"stateMutability":"view","type":"function"},{"name":"approve","inputs":[{"name":"spender","type":"address"},{"name":"tokens","type":"uint256"}],"outputs":[{"name":"success","type":"bool"}],"constant":false,"payable":false,"stateMutability":"nonpayable","type":"function"},{"name":"balanceOf","inputs":[{"name":"tokenOwner","type":"address"}],"outputs":[{"name":"balance","type":"uint256"}],"constant":true,"payable":false,"stateMutability":"view","type":"function"},{"name":"totalSupply","inputs":[],"outputs":[{"name":"","type":"uint256"}],"constant":true,"payable":false,"stateMutability":"view","type":"function"},{"name":"transfer","inputs":[{"name":"to","type":"address"},{"name":"tokens","type":"uint256"}],"outputs":[{"name":"success","type":"bool"}],"constant":false,"payable":false,"stateMutability":"nonpayable","type":"function"},{"name":"transferFrom","inputs":[{"name":"from","type":"address"},{"name":"to","type":"address"},{"name":"tokens","type":"uint256"}],"outputs":[{"name":"success","type":"bool"}],"constant":false,"payable":false,"stateMutability":"nonpayable","type":"function"},{"name":"Approval","inputs":[{"name":"tokenOwner","type":"address","indexed":true},{"name":"spender","type":"address","indexed":true},{"name":"tokens","type":"uint256","indexed":false}],"anonymous":false,"type":"event"},{"name":"Transfer","inputs":[{"name":"from","type":"address","indexed":true},{"name":"to","type":"address","indexed":true},{"name":"tokens","type":"uint256","indexed":false}],"anonymous":false,"type":"event"}]
[success] Total time: 0 s, completed Feb 16, 2019 1:01:05 PM
```

@@@

### ethContractAbiPrintPretty

@@@ div { .keydesc }

**Usage:**
```
> ethContractAbiPrintPretty <address-as-hex-or-ens-or-alias>
```

Pretty-prints the ABI currently effective and associated with an address (for the current [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID).

If there is a session override set (see @ref:[`ethContractAbiOverrideSet`](#ethContractAbiOverrideSet)), it will be the override ABI.

If there is no session override set, it will be the default contract ABI, either defines at deployment time or explicitly imported.

If neither a session override nor default ABI is defined for the address, it will let you know that.

**Example:**
```
> ethContractAbiPrintPretty WETH
Contract ABI for ABI associated with contract address '0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2' on chain with ID 1:
[ {
  "name" : "allowance",
  "inputs" : [ {
    "name" : "tokenOwner",
    "type" : "address"
  }, {
    "name" : "spender",
    "type" : "address"
  } ],
  "outputs" : [ {
    "name" : "remaining",
    "type" : "uint256"
  } ],
  "constant" : true,
  "payable" : false,
  "stateMutability" : "view",
  "type" : "function"
}, {
  "name" : "approve",
  "inputs" : [ {
    "name" : "spender",
    "type" : "address"
  }, {
    "name" : "tokens",
    "type" : "uint256"
  } ],
  "outputs" : [ {
    "name" : "success",
    "type" : "bool"
  } ],
  "constant" : false,
  "payable" : false,
  "stateMutability" : "nonpayable",
  "type" : "function"
}, {
  "name" : "balanceOf",
  "inputs" : [ {
    "name" : "tokenOwner",
    "type" : "address"
  } ],
  "outputs" : [ {
    "name" : "balance",
    "type" : "uint256"
  } ],
  "constant" : true,
  "payable" : false,
  "stateMutability" : "view",
  "type" : "function"
}, {
  "name" : "totalSupply",
  "inputs" : [ ],
  "outputs" : [ {
    "name" : "",
    "type" : "uint256"
  } ],
  "constant" : true,
  "payable" : false,
  "stateMutability" : "view",
  "type" : "function"
}, {
  "name" : "transfer",
  "inputs" : [ {
    "name" : "to",
    "type" : "address"
  }, {
    "name" : "tokens",
    "type" : "uint256"
  } ],
  "outputs" : [ {
    "name" : "success",
    "type" : "bool"
  } ],
  "constant" : false,
  "payable" : false,
  "stateMutability" : "nonpayable",
  "type" : "function"
}, {
  "name" : "transferFrom",
  "inputs" : [ {
    "name" : "from",
    "type" : "address"
  }, {
    "name" : "to",
    "type" : "address"
  }, {
    "name" : "tokens",
    "type" : "uint256"
  } ],
  "outputs" : [ {
    "name" : "success",
    "type" : "bool"
  } ],
  "constant" : false,
  "payable" : false,
  "stateMutability" : "nonpayable",
  "type" : "function"
}, {
  "name" : "Approval",
  "inputs" : [ {
    "name" : "tokenOwner",
    "type" : "address",
    "indexed" : true
  }, {
    "name" : "spender",
    "type" : "address",
    "indexed" : true
  }, {
    "name" : "tokens",
    "type" : "uint256",
    "indexed" : false
  } ],
  "anonymous" : false,
  "type" : "event"
}, {
  "name" : "Transfer",
  "inputs" : [ {
    "name" : "from",
    "type" : "address",
    "indexed" : true
  }, {
    "name" : "to",
    "type" : "address",
    "indexed" : true
  }, {
    "name" : "tokens",
    "type" : "uint256",
    "indexed" : false
  } ],
  "anonymous" : false,
  "type" : "event"
} ]
[success] Total time: 0 s, completed Feb 16, 2019 12:58:44 PM
```

@@@

