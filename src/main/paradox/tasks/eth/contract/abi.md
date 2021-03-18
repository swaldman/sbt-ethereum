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
@ref:[`ethContractAbiDefaultImport`](#ethContractAbiDefaultImport) or @ref:[`ethContractAbiDefaultSet`](#ethContractAbiDefaultSet).

Default ABI associations are store in the _sbt-ethereum_ "shoebox" and are persistent. Once set, they will remain unless they are
overwritten or explicitly dropped.

**<a name="importing_contract_abis"></a>Importing Contract ABIs**

Prior to _sbt-ethereum_ version 0.5.3, tasks that import contract ABIs (@ref:[`ethContractAbiImport`](#ethcontractabiimport) and @ref:[`ethContractAbiDefaultImport`](#ethcontractabidefaultimport))
did so in only two ways:

@@@ div {.tight}

* Require users to @ref:[setup an _Etherscan_ API key](../../etherscan.md), and then download ABIs from there (for _Ethereum_ mainnet only)
* Require users to directly paste the ABI, formatted with no embedded newlines (i.e. compact, not "pretty-printed")

@@@

Beginning in version 0.5.3, ABI importing is much more permitted. When prompted for `Contract ABI or Source: `, users
can supply any of

@@@ div {.tight}

1. The ABI JSON directly (with no embedded newlines)
2. A JSON object (with no embedded newlines) containing the ABI under the key `abi` or under the path `metadata/abi` (checked in that order)
3. A URL or path-to-a-file whose contents are either (1) or (2)
4. A URL or path-to-a-file whose data contains a unique nonempty, "strict" (meaning no unexpected elements) ABI that can be scraped from its contents (without or with unescaping HTML entities)

@@@

Together this means that usually you can import an ABI just by pasting in a URL, for example the URLs presented for verified contracts by [_Etherscan_](https://etherscan.io) or [_Blockscout_](blockscout.com),
or URLs to standard contract metadata or _Truffle_ complation artifacts.

@@@ note

**Github URLs are treated specially**

"Ordinary" URLs like https://github.com/CirclesUBI/circles-contracts/blob/master/build/contracts/Token.json are automatically converted
to "raw" Github URLs like https://raw.githubusercontent.com/CirclesUBI/circles-contracts/master/build/contracts/Token.json.)

@@@

For chains other than _Ethereum_ mainnet (where _Etherscan_ could help), importing URLs used to be a significant annoyance. Now it should be
much, much easier. Just paste a URL containing the ABI and go.

@@@ warning

**When importing URLs, be careful who you trust!**

It would be possible for a web page to be crafted that _seems_ to contain one ABI, but includes some invisible something that renders
that visible ABI invalid, while invisibly embedding an invald ABI.

_sbt-ethereum_ always asks user to confirm ABIs upon import, but practically speaking, users are not going to verify ABIs by proofreading them and comparing with contract source.
If you are importing an ABI from a URL, think about whether you trust the source (and avoid insecure protocols like unencrypted `http`.).

@@@


**Overriding Contract ABIs**

Sometimes you may temporarily wish to use an ABI that should not be the default ABI for a contract. _ABI overrides_ define
temporary associations of ABIs with contract addresses that only endure within a single _sbt-ethereum_ session. See e.g. @ref:[`ethContractAbiOverride`](#ethcontractabioverride) and @ref[`ethContractAbiOverrideDrop`](#ethcontractabioverridedrop).

**Aliases**<a name="aliases"></a>

When _sbt-ethereum_ commands expect an ABI, you can refer to it via the address of a contract for which that ABI is already
the default. (You can also refer to ABIs by their hashes, which _sbt-ethereum_ computes after normalizing the JSON ABIs by
removing unnecessary spaces, and sometimes exposes, see e.g. [`ethContractAbiAliasList`](#ethContractAbiAliasList).)

However, it may be convenient to name ABIs you interact with frequently and may wish to reuse. So _sbt-ethereum_
permits you to define _ABI aliases_. To refer to ABI aliases where _sbt-ethereum_ expects an ABI, prefix the alias name with `abi:`.

_sbt-ethereum_ also defines aliases for some standard ABIs. These are prefixed with `abi:standard:`. For the moment, the only
supported standard ABI alias is `abi:standard:erc20`.

**Function Call Encoding and Decoding**

A contracts functions and the form its calls will take (arguments, return values) are specified by contract ABIs.
@ref:[`ethContractAbiCallEncode`](#ethContractAbiCallEncode) and @ref:[`ethContractAbiCallDecode`](#ethContractAbiCallDecode)
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

ABIs can be imported manually, by direct copy-and-paste of JSON, or by providing a URL or the path to a file in which the ABI can be found.
JSON ABIs are interpreted directly, or are extracted from within JSON objects under the key "abi", or under the path "metadata" / "abi.
If the URL or file does not contain JSON directly, this task will offer to "scrape" the ABI from the source. If a unique, nonempty, strictly valid ABI can be scraped from the source, _sbt-ethereum_ will offer to import it. (See @ref:[Importing Contract ABIs](#importing_contract_abis)))

For _Ethereum_ mainnet, ABIs can also be imported automatically from _Etherscan_, if @ref:[an _Etherscan_ API key has been set](../../etherscan.md).

When importing from _Etherscan_, _sbt_ethereum_ will now check to see if the contract appears to be an [EIP-1967 transparent proxy](https://eips.ethereum.org/EIPS/eip-1967).
If the contract does appear to be a proxy, _sbt-ethereum_ will offer to download the API of the proxied contract, which is usually what you'll prefer. (Whichever ABI you choose,
you can have access to alternative ABI by using @ref:[ethContractAbiImport](#ethcontractabiimport) _without supplying a contract address_. Paste in the alternative ABI when
queried, then define an @ref:[ABI alias](#aliases). To use the alternative ABI, use @ref:[ethContractAbiOverrideSet](#ethcontractabioverrideset).)

@@@

@@@ warning

**Directly pasted ABIs cannot include newlines!**

If you are directly pasting an ABI into the console (rather than importing from _Etherscan_), it can't be a pretty-printed ABI with newlines. _sbt-ethereum_ expects you to "hit return" only after
you have pasted the ABI text. If you have a pretty-printed ABI, condense it, for example using sites like [this](https://codebeautify.org/jsonminifier).

@@@

@@@ div { .keydesc }

**Example (scraped from URL):**
```
> ethContractAbiDefaultImport 0xe599f637dfb705e56589951a5d777afdaf618b5b
To import an ABI, you may provide the JSON ABI directly, or else a file path or URL from which the ABI can be downloaded.
Contract ABI or Source: https://etherscan.io/address/0xe599f637dfb705e56589951a5d777afdaf618b5b#code
[info] Attempting to interactively import a contract ABI.
[info] Checking to see if you have provided a JSON array or object directly.
[info] The provided text does not appear to be a JSON ABI.
[info] Checking if the provided source exists as a File.
[info] No file found. Checking if the provided source is interpretable as a URL.
[info] Interpreted user-provided source as a URL. Attempting to fetch contents.
We can attempt to SCRAPE a unique ABI from the text of the provided source.
Be sure you trust 'https://etherscan.io/address/0xe599f637dfb705e56589951a5d777afdaf618b5b#code' to contain a reliable representation of the ABI.
Scrape for the ABI? [y/n] y
[info] We had to scrape, but we were able to recover a unique ABI from source 'https://etherscan.io/address/0xe599f637dfb705e56589951a5d777afdaf618b5b#code'!
Ready to import the following ABI:
[ {
  "constant" : true,
  "inputs" : [ ],
  "name" : "whoAmI",
  "outputs" : [ {
    "name" : "me",
    "type" : "address"
  } ],
  "payable" : false,
  "stateMutability" : "view",
  "type" : "function"
} ]
Do you wish to import this ABi? [y/n] y
[info] A default ABI is now known for the contract at address 0xe599f637DFb705E56589951A5d777aFDaf618B5B
Enter an optional alias for the address '0xe599f637DFb705E56589951A5d777aFDaf618B5B', now associated with the newly imported default ABI (or [return] for none): whoami
[info] Alias 'whoami' now points to address '0xe599f637DFb705E56589951A5d777aFDaf618B5B' (for chain with ID 1).
[info] Refreshing caches.
[success] Total time: 24 s, completed Mar 18, 2021, 1:08:18 AM
```

**Example (automatic Etherscan import with transparent proxy detection):**
```
> ethContractAbiDefaultImport AaveLendingPoolV2-Proxy
An Etherscan API key has been set. Would you like to try to import the ABI for this address from Etherscan? [y/n] y
Checking to see if address '0x7d2768dE32b0b80b7a3454c06BdAc94A69DDc7A9' is an EIP-1967 transparent proxy.
'0x7d2768dE32b0b80b7a3454c06BdAc94A69DDc7A9' appears to be an EIP-1967 transparent proxy for '0xC6845a5C768BF8D7681249f8927877Efda425baf'.
Import the ABI of the proxied contract instead of the apparent proxy? [y/n] y
Will attempt to import the ABI associated with proxied contract at '0xC6845a5C768BF8D7681249f8927877Efda425baf'.
Attempting to fetch ABI for address '0xC6845a5C768BF8D7681249f8927877Efda425baf' from Etherscan.
ABI found:
[{"outputs":[{"name":"","type":"uint256","internalType":"uint256"}],"constant":true,"payable":false,"inputs":[],"name":"FLASHLOAN_PREMIUM_TOTAL","stateMutability":"view","type":"function"},{"outputs":[{"name":"","type":"uint256","internalType":"uint256"}],"constant":true,"payable":false,"inputs":[],"name":"LENDINGPOOL_REVISION","stateMutability":"view","type":"function"},{"outputs":[{"name":"","type":"uint256","internalType":"uint256"}],"constant":true,"payable":false,"inputs":[],"name":"MAX_NUMBER_RESERVES","stateMutability":"view","type":"function"},{"outputs":[{"name":"","type":"uint256","internalType":"uint256"}],"constant":true,"payable":false,"inputs":[],"name":"MAX_STABLE_RATE_BORROW_SIZE_PERCENT","stateMutability":"view","type":"function"},{"outputs":[],"constant":false,"payable":false,"inputs":[{"name":"asset","type":"address","internalType":"address"},{"name":"amount","type":"uint256","internalType":"uint256"},{"name":"interestRateMode","type":"uint256","internalType":"uint256"},{"name":"referralCode","type":"uint16","internalType":"uint16"},{"name":"onBehalfOf","type":"address","internalType":"address"}],"name":"borrow","stateMutability":"nonpayable","type":"function"},{"outputs":[],"constant":false,"payable":false,"inputs":[{"name":"asset","type":"address","internalType":"address"},{"name":"amount","type":"uint256","internalType":"uint256"},{"name":"onBehalfOf","type":"address","internalType":"address"},{"name":"referralCode","type":"uint16","internalType":"uint16"}],"name":"deposit","stateMutability":"nonpayable","type":"function"},{"outputs":[],"constant":false,"payable":false,"inputs":[{"name":"asset","type":"address","internalType":"address"},{"name":"from","type":"address","internalType":"address"},{"name":"to","type":"address","internalType":"address"},{"name":"amount","type":"uint256","internalType":"uint256"},{"name":"balanceFromBefore","type":"uint256","internalType":"uint256"},{"name":"balanceToBefore","type":"uint256","internalType":"uint256"}],"name":"finalizeTransfer","stateMutability":"nonpayable","type":"function"},{"outputs":[],"constant":false,"payable":false,"inputs":[{"name":"receiverAddress","type":"address","internalType":"address"},{"name":"assets","type":"address[]","internalType":"address[]"},{"name":"amounts","type":"uint256[]","internalType":"uint256[]"},{"name":"modes","type":"uint256[]","internalType":"uint256[]"},{"name":"onBehalfOf","type":"address","internalType":"address"},{"name":"params","type":"bytes","internalType":"bytes"},{"name":"referralCode","type":"uint16","internalType":"uint16"}],"name":"flashLoan","stateMutability":"nonpayable","type":"function"},{"outputs":[{"name":"","type":"address","internalType":"contract ILendingPoolAddressesProvider"}],"constant":true,"payable":false,"inputs":[],"name":"getAddressesProvider","stateMutability":"view","type":"function"},{"outputs":[{"name":"","type":"tuple","internalType":"struct DataTypes.ReserveConfigurationMap"}],"constant":true,"payable":false,"inputs":[{"name":"asset","type":"address","internalType":"address"}],"name":"getConfiguration","stateMutability":"view","type":"function"},{"outputs":[{"name":"","type":"tuple","internalType":"struct DataTypes.ReserveData"}],"constant":true,"payable":false,"inputs":[{"name":"asset","type":"address","internalType":"address"}],"name":"getReserveData","stateMutability":"view","type":"function"},{"outputs":[{"name":"","type":"uint256","internalType":"uint256"}],"constant":true,"payable":false,"inputs":[{"name":"asset","type":"address","internalType":"address"}],"name":"getReserveNormalizedIncome","stateMutability":"view","type":"function"},{"outputs":[{"name":"","type":"uint256","internalType":"uint256"}],"constant":true,"payable":false,"inputs":[{"name":"asset","type":"address","internalType":"address"}],"name":"getReserveNormalizedVariableDebt","stateMutability":"view","type":"function"},{"outputs":[{"name":"","type":"address[]","internalType":"address[]"}],"constant":true,"payable":false,"inputs":[],"name":"getReservesList","stateMutability":"view","type":"function"},{"outputs":[{"name":"totalCollateralETH","type":"uint256","internalType":"uint256"},{"name":"totalDebtETH","type":"uint256","internalType":"uint256"},{"name":"availableBorrowsETH","type":"uint256","internalType":"uint256"},{"name":"currentLiquidationThreshold","type":"uint256","internalType":"uint256"},{"name":"ltv","type":"uint256","internalType":"uint256"},{"name":"healthFactor","type":"uint256","internalType":"uint256"}],"constant":true,"payable":false,"inputs":[{"name":"user","type":"address","internalType":"address"}],"name":"getUserAccountData","stateMutability":"view","type":"function"},{"outputs":[{"name":"","type":"tuple","internalType":"struct DataTypes.UserConfigurationMap"}],"constant":true,"payable":false,"inputs":[{"name":"user","type":"address","internalType":"address"}],"name":"getUserConfiguration","stateMutability":"view","type":"function"},{"outputs":[],"constant":false,"payable":false,"inputs":[{"name":"asset","type":"address","internalType":"address"},{"name":"aTokenAddress","type":"address","internalType":"address"},{"name":"stableDebtAddress","type":"address","internalType":"address"},{"name":"variableDebtAddress","type":"address","internalType":"address"},{"name":"interestRateStrategyAddress","type":"address","internalType":"address"}],"name":"initReserve","stateMutability":"nonpayable","type":"function"},{"outputs":[],"constant":false,"payable":false,"inputs":[{"name":"provider","type":"address","internalType":"contract ILendingPoolAddressesProvider"}],"name":"initialize","stateMutability":"nonpayable","type":"function"},{"outputs":[],"constant":false,"payable":false,"inputs":[{"name":"collateralAsset","type":"address","internalType":"address"},{"name":"debtAsset","type":"address","internalType":"address"},{"name":"user","type":"address","internalType":"address"},{"name":"debtToCover","type":"uint256","internalType":"uint256"},{"name":"receiveAToken","type":"bool","internalType":"bool"}],"name":"liquidationCall","stateMutability":"nonpayable","type":"function"},{"outputs":[{"name":"","type":"bool","internalType":"bool"}],"constant":true,"payable":false,"inputs":[],"name":"paused","stateMutability":"view","type":"function"},{"outputs":[],"constant":false,"payable":false,"inputs":[{"name":"asset","type":"address","internalType":"address"},{"name":"user","type":"address","internalType":"address"}],"name":"rebalanceStableBorrowRate","stateMutability":"nonpayable","type":"function"},{"outputs":[{"name":"","type":"uint256","internalType":"uint256"}],"constant":false,"payable":false,"inputs":[{"name":"asset","type":"address","internalType":"address"},{"name":"amount","type":"uint256","internalType":"uint256"},{"name":"rateMode","type":"uint256","internalType":"uint256"},{"name":"onBehalfOf","type":"address","internalType":"address"}],"name":"repay","stateMutability":"nonpayable","type":"function"},{"outputs":[],"constant":false,"payable":false,"inputs":[{"name":"asset","type":"address","internalType":"address"},{"name":"configuration","type":"uint256","internalType":"uint256"}],"name":"setConfiguration","stateMutability":"nonpayable","type":"function"},{"outputs":[],"constant":false,"payable":false,"inputs":[{"name":"val","type":"bool","internalType":"bool"}],"name":"setPause","stateMutability":"nonpayable","type":"function"},{"outputs":[],"constant":false,"payable":false,"inputs":[{"name":"asset","type":"address","internalType":"address"},{"name":"rateStrategyAddress","type":"address","internalType":"address"}],"name":"setReserveInterestRateStrategyAddress","stateMutability":"nonpayable","type":"function"},{"outputs":[],"constant":false,"payable":false,"inputs":[{"name":"asset","type":"address","internalType":"address"},{"name":"useAsCollateral","type":"bool","internalType":"bool"}],"name":"setUserUseReserveAsCollateral","stateMutability":"nonpayable","type":"function"},{"outputs":[],"constant":false,"payable":false,"inputs":[{"name":"asset","type":"address","internalType":"address"},{"name":"rateMode","type":"uint256","internalType":"uint256"}],"name":"swapBorrowRateMode","stateMutability":"nonpayable","type":"function"},{"outputs":[{"name":"","type":"uint256","internalType":"uint256"}],"constant":false,"payable":false,"inputs":[{"name":"asset","type":"address","internalType":"address"},{"name":"amount","type":"uint256","internalType":"uint256"},{"name":"to","type":"address","internalType":"address"}],"name":"withdraw","stateMutability":"nonpayable","type":"function"},{"inputs":[{"name":"reserve","type":"address","indexed":true,"internalType":"address"},{"name":"user","type":"address","indexed":false,"internalType":"address"},{"name":"onBehalfOf","type":"address","indexed":true,"internalType":"address"},{"name":"amount","type":"uint256","indexed":false,"internalType":"uint256"},{"name":"borrowRateMode","type":"uint256","indexed":false,"internalType":"uint256"},{"name":"borrowRate","type":"uint256","indexed":false,"internalType":"uint256"},{"name":"referral","type":"uint16","indexed":true,"internalType":"uint16"}],"name":"Borrow","anonymous":false,"type":"event"},{"inputs":[{"name":"reserve","type":"address","indexed":true,"internalType":"address"},{"name":"user","type":"address","indexed":false,"internalType":"address"},{"name":"onBehalfOf","type":"address","indexed":true,"internalType":"address"},{"name":"amount","type":"uint256","indexed":false,"internalType":"uint256"},{"name":"referral","type":"uint16","indexed":true,"internalType":"uint16"}],"name":"Deposit","anonymous":false,"type":"event"},{"inputs":[{"name":"target","type":"address","indexed":true,"internalType":"address"},{"name":"initiator","type":"address","indexed":true,"internalType":"address"},{"name":"asset","type":"address","indexed":true,"internalType":"address"},{"name":"amount","type":"uint256","indexed":false,"internalType":"uint256"},{"name":"premium","type":"uint256","indexed":false,"internalType":"uint256"},{"name":"referralCode","type":"uint16","indexed":false,"internalType":"uint16"}],"name":"FlashLoan","anonymous":false,"type":"event"},{"inputs":[{"name":"collateralAsset","type":"address","indexed":true,"internalType":"address"},{"name":"debtAsset","type":"address","indexed":true,"internalType":"address"},{"name":"user","type":"address","indexed":true,"internalType":"address"},{"name":"debtToCover","type":"uint256","indexed":false,"internalType":"uint256"},{"name":"liquidatedCollateralAmount","type":"uint256","indexed":false,"internalType":"uint256"},{"name":"liquidator","type":"address","indexed":false,"internalType":"address"},{"name":"receiveAToken","type":"bool","indexed":false,"internalType":"bool"}],"name":"LiquidationCall","anonymous":false,"type":"event"},{"inputs":[],"name":"Paused","anonymous":false,"type":"event"},{"inputs":[{"name":"reserve","type":"address","indexed":true,"internalType":"address"},{"name":"user","type":"address","indexed":true,"internalType":"address"}],"name":"RebalanceStableBorrowRate","anonymous":false,"type":"event"},{"inputs":[{"name":"reserve","type":"address","indexed":true,"internalType":"address"},{"name":"user","type":"address","indexed":true,"internalType":"address"},{"name":"repayer","type":"address","indexed":true,"internalType":"address"},{"name":"amount","type":"uint256","indexed":false,"internalType":"uint256"}],"name":"Repay","anonymous":false,"type":"event"},{"inputs":[{"name":"reserve","type":"address","indexed":true,"internalType":"address"},{"name":"liquidityRate","type":"uint256","indexed":false,"internalType":"uint256"},{"name":"stableBorrowRate","type":"uint256","indexed":false,"internalType":"uint256"},{"name":"variableBorrowRate","type":"uint256","indexed":false,"internalType":"uint256"},{"name":"liquidityIndex","type":"uint256","indexed":false,"internalType":"uint256"},{"name":"variableBorrowIndex","type":"uint256","indexed":false,"internalType":"uint256"}],"name":"ReserveDataUpdated","anonymous":false,"type":"event"},{"inputs":[{"name":"reserve","type":"address","indexed":true,"internalType":"address"},{"name":"user","type":"address","indexed":true,"internalType":"address"}],"name":"ReserveUsedAsCollateralDisabled","anonymous":false,"type":"event"},{"inputs":[{"name":"reserve","type":"address","indexed":true,"internalType":"address"},{"name":"user","type":"address","indexed":true,"internalType":"address"}],"name":"ReserveUsedAsCollateralEnabled","anonymous":false,"type":"event"},{"inputs":[{"name":"reserve","type":"address","indexed":true,"internalType":"address"},{"name":"user","type":"address","indexed":true,"internalType":"address"},{"name":"rateMode","type":"uint256","indexed":false,"internalType":"uint256"}],"name":"Swap","anonymous":false,"type":"event"},{"inputs":[],"name":"Unpaused","anonymous":false,"type":"event"},{"inputs":[{"name":"reserve","type":"address","indexed":true,"internalType":"address"},{"name":"user","type":"address","indexed":true,"internalType":"address"},{"name":"to","type":"address","indexed":true,"internalType":"address"},{"name":"amount","type":"uint256","indexed":false,"internalType":"uint256"}],"name":"Withdraw","anonymous":false,"type":"event"}]
Use this ABI? [y/n] y
[info] A default ABI is now known for the contract at address 0x7d2768dE32b0b80b7a3454c06BdAc94A69DDc7A9
[info] Refreshing caches.
[success] Total time: 33 s, completed Feb 7, 2021, 6:45:08 AM
```

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
ABIs can be imported by direct copy-and-paste of JSON, or by providing the URL or the path to a file in which the ABI can be found.
JSON ABIs are interpreted directly, or are extracted from within JSON objects under the key "abi", or under the path "metadata" / "abi.
If the URL or file does not contain JSON directly, this task will offer to "scrape" the ABI from the source. If a unique, nonempty, strictly valid ABI can be scraped from the source, _sbt-ethereum_ will offer to import it. (See @ref:[Importing Contract ABIs](#importing_contract_abis)))

Once you have a named ABI, use tasks like @ref:[`ethContractAbiOverrideSet`](#ethcontractabioverrideset), @ref:[`ethContractAbiDefaultSet`](#ethcontractabidefaultset), and @ref:[`ethContractAbiPrintPretty`](#ethcontractabiprintpretty)

**Example (ABI scraped from a URL):**

```
> ethContractAbiImport
You are importing an ABI unattached to any contract address. You must provide an alias, so you can refer to it later.
To import an ABI, you may provide the JSON ABI directly, or else a file path or URL from which the ABI can be downloaded.
Contract ABI or Source: https://etherscan.io/address/0xe599f637dfb705e56589951a5d777afdaf618b5b#code
[info] Attempting to interactively import a contract ABI.
[info] Checking to see if you have provided a JSON array or object directly.
[info] The provided text does not appear to be a JSON ABI.
[info] Checking if the provided source exists as a File.
[info] No file found. Checking if the provided source is interpretable as a URL.
[info] Interpreted user-provided source as a URL. Attempting to fetch contents.
We can attempt to SCRAPE a unique ABI from the text of the provided source.
Be sure you trust 'https://etherscan.io/address/0xe599f637dfb705e56589951a5d777afdaf618b5b#code' to contain a reliable representation of the ABI.
Scrape for the ABI? [y/n] y
[info] We had to scrape, but we were able to recover a unique ABI from source 'https://etherscan.io/address/0xe599f637dfb705e56589951a5d777afdaf618b5b#code'!
Ready to import the following ABI:
[ {
  "constant" : true,
  "inputs" : [ ],
  "name" : "whoAmI",
  "outputs" : [ {
    "name" : "me",
    "type" : "address"
  } ],
  "payable" : false,
  "stateMutability" : "view",
  "type" : "function"
} ]
Do you wish to import this ABi? [y/n] y
Please enter an alias for this ABI: whoami
[info] The ABI has been successfully imported, with alias 'abi:whoami'.
[info] Refreshing caches.
[success] Total time: 20 s, completed Mar 18, 2021, 1:16:31 AM
```

@@@

@@@ warning

**Directly pasted ABIs cannot include newlines!**

If you are directly pasting an ABI into the console (rather than providing a URL or file path, or importing from _Etherscan_), it can't be a pretty-printed ABI with newlines. _sbt-ethereum_ expects you to "hit return" only after
you have pasted the ABI text. If you have a pretty-printed ABI, condense it, for example using sites like [this](https://codebeautify.org/jsonminifier).

@@@

@@@ div {.keydesc}

**Example (directly pasted JSON ABI):**

```
> ethContractAbiImport
You are importing an ABI unattached to any contract address. You must provide an alias, so you can refer to it later.
To import an ABI, you may provide the JSON ABI directly, or else a file path or URL from which the ABI can be downloaded.
Contract ABI or Source: [{"outputs":[],"constant":false,"payable":false,"inputs":[{"name":"fortune","type":"string","internalType":"string"}],"name":"addFortune","stateMutability":"nonpayable","type":"function"},{"outputs":[{"name":"count","type":"uint256","internalType":"uint256"}],"constant":true,"payable":false,"inputs":[],"name":"countFortunes","stateMutability":"view","type":"function"},{"outputs":[{"name":"fortune","type":"string","internalType":"string"}],"constant":true,"payable":false,"inputs":[],"name":"drawFortune","stateMutability":"view","type":"function"},{"outputs":[{"name":"","type":"string","internalType":"string"}],"constant":true,"payable":false,"inputs":[{"name":"","type":"uint256","internalType":"uint256"}],"name":"fortunes","stateMutability":"view","type":"function"},{"inputs":[{"name":"author","type":"address","indexed":false,"internalType":"address"},{"name":"fortune","type":"string","indexed":false,"internalType":"string"}],"name":"FortuneAdded","anonymous":false,"type":"event"},{"payable":false,"inputs":[{"name":"initialFortune","type":"string","internalType":"string"}],"stateMutability":"nonpayable","type":"constructor"}]
[info] Attempting to interactively import a contract ABI.
[info] Checking to see if you have provided a JSON array or object directly.
[info] Found JSON array. Will attempt to interpret as ABI directly.
Ready to import the following ABI:
[ {
  "outputs" : [ ],
  "constant" : false,
  "payable" : false,
  "inputs" : [ {
    "name" : "fortune",
    "type" : "string",
    "internalType" : "string"
  } ],
  "name" : "addFortune",
  "stateMutability" : "nonpayable",
  "type" : "function"
}, {
  "outputs" : [ {
    "name" : "count",
    "type" : "uint256",
    "internalType" : "uint256"
  } ],
  "constant" : true,
  "payable" : false,
  "inputs" : [ ],
  "name" : "countFortunes",
  "stateMutability" : "view",
  "type" : "function"
}, {
  "outputs" : [ {
    "name" : "fortune",
    "type" : "string",
    "internalType" : "string"
  } ],
  "constant" : true,
  "payable" : false,
  "inputs" : [ ],
  "name" : "drawFortune",
  "stateMutability" : "view",
  "type" : "function"
}, {
  "outputs" : [ {
    "name" : "",
    "type" : "string",
    "internalType" : "string"
  } ],
  "constant" : true,
  "payable" : false,
  "inputs" : [ {
    "name" : "",
    "type" : "uint256",
    "internalType" : "uint256"
  } ],
  "name" : "fortunes",
  "stateMutability" : "view",
  "type" : "function"
}, {
  "inputs" : [ {
    "name" : "author",
    "type" : "address",
    "indexed" : false,
    "internalType" : "address"
  }, {
    "name" : "fortune",
    "type" : "string",
    "indexed" : false,
    "internalType" : "string"
  } ],
  "name" : "FortuneAdded",
  "anonymous" : false,
  "type" : "event"
}, {
  "payable" : false,
  "inputs" : [ {
    "name" : "initialFortune",
    "type" : "string",
    "internalType" : "string"
  } ],
  "stateMutability" : "nonpayable",
  "type" : "constructor"
} ]
Do you wish to import this ABi? [y/n] y
Please enter an alias for this ABI: fortune
[info] The ABI has been successfully imported, with alias 'abi:fortune'.
[info] Refreshing caches.
[success] Total time: 29 s, completed Mar 18, 2021, 1:14:07 AM
```

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

