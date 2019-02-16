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

**Aliases**

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

### ethContractAbiOverride

### ethContractAbiOverrideSet

### ethContractAbiOverrideDrop

### ethContractAbiOverrideDropAll

### ethContractAbiOverrideList

### ethContractAbiOverridePrint

### ethContractAbiPrint

### ethContractAbiPrintPretty

### ethContractAbiPrintCompact
