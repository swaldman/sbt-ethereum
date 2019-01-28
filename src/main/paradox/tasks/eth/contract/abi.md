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

### ethContractAbiDefaultList

### ethContractAbiDefaultImport

### ethContractAbiDefaultSet

### ethContractAbiOverride

### ethContractAbiOverrideSet

### ethContractAbiOverrideDrop

### ethContractAbiOverrideDropAll

### ethContractAbiOverrideList

### ethContractAbiOverridePrint

### ethContractAbiPrint

### ethContractAbiPrintPretty

### ethContractAbiPrintCompact
