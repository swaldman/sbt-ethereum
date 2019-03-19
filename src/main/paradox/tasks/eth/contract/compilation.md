# Compilation*

_sbt-ethereum_ carefully maintains very complete information about smart contract compilations that you may have deployed.
This information includes contract ABIs (so you can interact with your own deployments immediately), the
compiled code itself, and full meta-information (which makes it very easy, for example, to verify deployments
on [_Etherscan_](https://etherscan.io).

Note that compiling a smart contract does not on its own trigger storage in _sbt-ethereum_'s "shoebox" database.
All available compilations are stored when the @ref:[`ethTransactionDeploy`](../transaction/index.md#ethtransactiondeploy) task
is attempted (regardless of whether the deployment eventually succeeds, even if the deployment attempt is aborted).

### ethContractCompilationCull

@@@ div { .keydesc}

**Usage:**
```
> ethContractCompilationCull
```

Clears from the _sbt-ethereum_ database stored records of compilations that were never actually deployed.

**Example:**
```
> ethContractCompilationCull
[info] Removed 11 undeployed compilations from the shoebox database.
[success] Total time: 0 s, completed Mar 18, 2019 10:39:41 PM
```

@@@

### ethContractCompilationInspect

@@@ div { .keydesc}

**Usage:**
```
> ethContractCompilationInspect <deployed-to-address-as-hex-or-ens-or-alias>|<code-hash-hex>
```

Prints the (very complete) details of a store and perhaps deployed compilation.

**Example:**
```
> ethContractCompilationInspect 0xe599f637dfb705e56589951a5d777afdaf618b5b

-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=
                       CONTRACT INFO DUMP
-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=

------------------------------------------------------------------------
Contract Address (on blockchain with ID 1):

0xe599f637dfb705e56589951a5d777afdaf618b5b

------------------------------------------------------------------------
Deployer Address:

0x465e79b940bc2157e4259ff6b2d92f454497f1e4

------------------------------------------------------------------------
Transaction Hash:

0xb5443b5fb8260a3d7b0be623611d5e007907dd2f18592029bab726b8ce357a94

------------------------------------------------------------------------
Deployment Timestamp:

Sat Dec 08 19:37:08 PST 2018

------------------------------------------------------------------------
Code Hash:

0x476366a873d99b0acd0624deb5ad7e06295c3396f5410ace2d42048d8de9c2fc

------------------------------------------------------------------------
Code:

0x608060405234801561001057600080fd5b5060ae8061001f6000396000f300608060405260043610603e5763ffffffff7c0100000000000000000000000000000000000000000000000000000000600035041663da91254c81146043575b600080fd5b348015604e57600080fd5b506055607e565b6040805173ffffffffffffffffffffffffffffffffffffffff9092168252519081900360200190f35b33905600a165627a7a7230582077a9cf6805f0a9dff41a2a42693a4fc37ec627fcffd7435a82ecfe836cc7cda50029

------------------------------------------------------------------------
Constructor Inputs Hex:

0x

------------------------------------------------------------------------
Contract Name:

WhoAmI

------------------------------------------------------------------------
Contract Source:

/*
 * DO NOT EDIT! DO NOT EDIT! DO NOT EDIT!
 *
 * This is an automatically generated file. It will be overwritten.
 *
 * For the original source see
 *    '/Users/swaldman/Dropbox/BaseFolders/development-why/gitproj/eth-who-am-i/src/main/solidity/WhoAmI.sol'
 */

pragma solidity ^0.4.24;

contract WhoAmI {
  function whoAmI() public view returns (address me) {
     me = msg.sender;
  }
}

------------------------------------------------------------------------
Contract Language:

Solidity

------------------------------------------------------------------------
Language Version:

0.4.24

------------------------------------------------------------------------
Compiler Version:

0.4.24+commit.e67f0147

------------------------------------------------------------------------
Compiler Options:

{"compilationTarget":{"<stdin>":"WhoAmI"},"evmVersion":"byzantium","libraries":{},"optimizer":{"enabled":true,"runs":200},"remappings":[]}

------------------------------------------------------------------------
ABI Hash:

0x093def1bd67f0c4c3f6d578cd27f5135d1fa62e2f652fab0ee79933b23a37c27

------------------------------------------------------------------------
ABI Definition:

[{"name":"whoAmI","inputs":[],"outputs":[{"name":"me","type":"address"}],"constant":true,"payable":false,"stateMutability":"view","type":"function"}]

------------------------------------------------------------------------
Metadata:

{"compiler":{"version":"0.4.24+commit.e67f0147"},"language":"Solidity","output":{"abi":[{"constant":true,"inputs":[],"name":"whoAmI","outputs":[{"name":"me","type":"address"}],"payable":false,"stateMutability":"view","type":"function"}],"devdoc":{"methods":{}},"userdoc":{"methods":{}}},"settings":{"compilationTarget":{"<stdin>":"WhoAmI"},"evmVersion":"byzantium","libraries":{},"optimizer":{"enabled":true,"runs":200},"remappings":[]},"sources":{"<stdin>":{"keccak256":"0x0a4d58d3ba6144b1a0a1042ec4cba8e488aa0db0b4b14c1c4418c8d4ce6d334c","urls":["bzzr://014c5a5ffc45a90341457101532a1e524d9397a9aea6b8b2e49ef7eabf67cf80"]}},"version":1}

------------------------------------------------------------------------
AST:

{"attributes":{"absolutePath":"<stdin>","exportedSymbols":{"WhoAmI":[13]}},"children":[{"attributes":{"literals":["solidity","^","0.4",".24"]},"id":1,"name":"PragmaDirective","src":"265:24:0"},{"attributes":{"baseContracts":[null],"contractDependencies":[null],"contractKind":"contract","documentation":null,"fullyImplemented":true,"linearizedBaseContracts":[13],"name":"WhoAmI","scope":14},"children":[{"attributes":{"constant":true,"documentation":null,"implemented":true,"isConstructor":false,"modifiers":[null],"name":"whoAmI","payable":false,"scope":13,"stateMutability":"view","superFunction":null,"visibility":"public"},"children":[{"attributes":{"parameters":[null]},"children":[],"id":2,"name":"ParameterList","src":"326:2:0"},{"children":[{"attributes":{"constant":false,"name":"me","scope":12,"stateVariable":false,"storageLocation":"default","type":"address","value":null,"visibility":"internal"},"children":[{"attributes":{"name":"address","type":"address"},"id":3,"name":"ElementaryTypeName","src":"350:7:0"}],"id":4,"name":"VariableDeclaration","src":"350:10:0"}],"id":5,"name":"ParameterList","src":"349:12:0"},{"children":[{"children":[{"attributes":{"argumentTypes":null,"isConstant":false,"isLValue":false,"isPure":false,"lValueRequested":false,"operator":"=","type":"address"},"children":[{"attributes":{"argumentTypes":null,"overloadedDeclarations":[null],"referencedDeclaration":4,"type":"address","value":"me"},"id":6,"name":"Identifier","src":"369:2:0"},{"attributes":{"argumentTypes":null,"isConstant":false,"isLValue":false,"isPure":false,"lValueRequested":false,"member_name":"sender","referencedDeclaration":null,"type":"address"},"children":[{"attributes":{"argumentTypes":null,"overloadedDeclarations":[null],"referencedDeclaration":28,"type":"msg","value":"msg"},"id":7,"name":"Identifier","src":"374:3:0"}],"id":8,"name":"MemberAccess","src":"374:10:0"}],"id":9,"name":"Assignment","src":"369:15:0"}],"id":10,"name":"ExpressionStatement","src":"369:15:0"}],"id":11,"name":"Block","src":"362:27:0"}],"id":12,"name":"FunctionDefinition","src":"311:78:0"}],"id":13,"name":"ContractDefinition","src":"291:100:0"}],"id":14,"name":"SourceUnit","src":"265:127:0"}

------------------------------------------------------------------------
Project Name:

eth-who-am-i
-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=

[success] Total time: 1 s, completed Mar 18, 2019 10:36:00 PM
```

@@@

### ethContractCompilationList   

@@@ div { .keydesc}

**Usage:**
```
> ethContractCompilationList
```

Lists all compilations that have been stored in the _sbt-ethereum_ "shoebox" database.

This includes deployed compilations, but also compilations that have not been deployed (and indeed may not even be deployable!).

You can clear away the undeployed compilations with @ref:[`ethContractCompilationCull`](#ethcontractcompilationcull)

**Example:**
```
> ethContractCompilationList
+----------+--------------------------------------------+--------------------------------+--------------------------------------------------------------------+------------------------------+
| Chain ID | Contract Address                           | Name                           | Code Hash                                                          | Deployment Timestamp         |
+----------+--------------------------------------------+--------------------------------+--------------------------------------------------------------------+------------------------------+
| 1        | 0xefff298833d99e57c7534a79d1b2aa933ad76965 | MintableBurnableERC20          | 0x5fa27022e4ae820172495780365425ee02c2ed6b3f3a5252f5d8f5e4085eff19 | 2019-03-07T20:13:58.752-0800 |
| 3        | 0x197ed2abe00d0c5cf6997bfab3ffc971def17de9 | HelloWorld                     | 0x716a70eb17e6bcaf2f527e7521a9e709be470ca801da6a6b88bd3d2aeddcabd9 | 2019-03-01T23:42:19.181-0800 |
| 1        | 0x2d73567ccf9ff56923f055f4693b67a40593c430 | Fortune                        | 0x1be0924e22c105ef2a2babc367d171cacfb94f2f4c64b643384a7a9f4c2b294e | 2019-02-18T20:50:12.721-0800 |
| 3        | 0xa240691bb281131ebca3caaffec4a504c66b5849 | UnrevokedSigned                | 0x1471f6ac89ae28f3fc25a3f866059a69cbb573ea9048102803fadca28d5e2f0b | 2018-12-25T04:32:58.438-0800 |
| 1        | 0xe599f637dfb705e56589951a5d777afdaf618b5b | WhoAmI                         | 0x476366a873d99b0acd0624deb5ad7e06295c3396f5410ace2d42048d8de9c2fc | 2018-12-08T19:37:08.961-0800 |
| 3        | 0x5989da02d916b38243dea0761141189e948f1452 | MintableBurnableERC20          | 0x219487d02cbbd57620965270f854df75581a3ca16d549b25c5795de2790db15e | 2018-08-21T14:28:18.197-0700 |
| 3        | 0xf64866fea86d45a7b4152e487e9cd37b678010e7 | ProxyableMintableBurnableERC20 | 0xedf1e591a8acb5c5e32d6e96df355adc9cd4431e7dacf103b4bcdf2823fa6885 | 2018-08-20T22:33:24.054-0700 |
| 1        | 0x3f980ea0cb23569db8cc0aa45c3742e86873d0b6 | HelloWorld                     | 0x622987dd2c7bcb6be962f079fab35d85b3aaf532f77c7df04add084125253ba7 | 2018-08-15T05:46:02.485-0700 |
| 1        | 0x353e8352f0782b827d72757dab9cc9464c7e9a3b | PingPong                       | 0xd4a0590e3048f913add35d10bebaea9d792e2774e103f9cfa56499f9ea6b6da1 | 2017-11-15T23:55:00.532-0800 |
|          |                                            | AddressUtils                   | 0x7955dbe9df46675b948fd4c34ef07b1c8a99fcd8dfaf83a03842414313e3a84d |                              |
|          |                                            | SaferSimpleToken               | 0xc655c66559accfd6f741b796dd1c63cab432df5a9defeecb595264787d7f50c8 |                              |
|          |                                            | ERC20                          | 0x6c01521513af1ffe3b8e655d95cd2e3df33c2d89f5b30b623adeb5d62d8e9f38 |                              |
|          |                                            | UnsafeSimpleToken              | 0x25144fe62d1e12997793c419fe8540bc5499c978afaa96dbbb4cbcba0219d4e6 |                              |
|          |                                            | SafeMath                       | 0x136d824c43769e5beb45013948015e6642094e461043092a4123077557be01c9 |                              |
|          |                                            | BurnableToken                  | 0x14a09b76c5492c10f89c9f662fd9b668e4ea1222d829ec51f3397771bbe137e3 |                              |
|          |                                            | Ownable                        | 0xcef17f5c490d5c85aeec8bf517c6119368421355bd0a25a802064b8c6e16631d |                              |
|          |                                            | SafeMath                       | 0x56786e79154a40e227b4e18ccbba34fc1471736d12625cccab2c1cdd46144713 |                              |
|          |                                            | MintableToken                  | 0xd0a4f40e1d90acafa72ef0a8110b3144eeffc36c6e8374aabee33d9cd1e0d3db |                              |
|          |                                            | SafeMath                       | 0xe621abcc40b669a326bf2b7b9ca3f19bfcfdd4c40d028c8bc2c3ff1d54fa2317 |                              |
|          |                                            | StandardToken                  | 0x79f9372658c102cdeb9c7bffbb848cb7176541d241a1b91e767c7cb3c490e894 |                              |
+----------+--------------------------------------------+--------------------------------+--------------------------------------------------------------------+------------------------------+
[success] Total time: 2 s, completed Mar 18, 2019 10:16:27 PM
```

@@@

