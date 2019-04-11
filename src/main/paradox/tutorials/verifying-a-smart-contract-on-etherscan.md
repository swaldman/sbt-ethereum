# Verifying a Smart Contract on Etherscan

### TL; DR

Everything you need is retained by _sbt-ethereum_, and is accessible via the @ref:[`ethContractCompilationInspect`](../tasks/eth/contract/compilation.md#ethcompilationinspect) command.
Provide the _address_ of the smart contract you'd compiled and deployed to that command. If you've forgotten the address, use
@ref:[`ethContractCompilationList`](../tasks/eth/contract/compilation.md#ethcompilationlist) to find it. If something goes wrong, check out @ref:[these gotchas](#gotchas).

### Step by step, in absurd detail

Although this is arguably not great (it is dangerously centralized), for now the best way to "publish" a smart contract
is to upload the code and get it verified on [_Etherscan_](https://etherscan.io).

_sbt-ethereum_ takes care to preserve all the information that you need to a verify a contract after you have deployed
it. Let's try verifying the 'Timelock' contract we created and deployed in an @ref:[earlier tutorial](creating-a-smart-contract.md).

We published that contract to address `0x3e24bfe40874a2f366ecf05746d6dcbc0cfd6f03`. If we go to _Etherscan_ and search on that address,
we'll [see](https://etherscan.io/address/0x3e24bfe40874a2f366ecf05746d6dcbc0cfd6f03) something like the following:

@@@ div { .centered }

<img alt="etherscan-withdrawn-timelock" src="../image/etherscan-withdrawn-timelock.png" height="812" />

@@@

@@@ note

**If you are following along, you'll want to perform the steps in this tutorial with _your own contract's address!_**

Verification is already done for the contract at `0x3e24bfe40874a2f366ecf05746d6dcbc0cfd6f03`, performed while writing this tutorial. Verify a contract that you yourself have compiled and deployed!

If you have forgotten your contract's address, just run `ethContractCompilationList`!

@@@

Now click on the **code** tab.

If you are following along with your own contract, you'll see that
the only code available is raw EVM hex (and hex encoding of the contract's constructor arguments).

@@@ div { .centered }

<img alt="etherscan-timelock-code-screen-preverify" src="../image/etherscan-timelock-code-screen-preverify.png" height="917" />

@@@

We'll want to make the
full code and ABI available, so others could interact with the contract.

@@@ note

**It's a bit pointless to verify this contract, which**
is designed to be used once and is already "spent", but the mechanics would be the same for a contracts describing richer
and more perpetual arrangements that many users might wish to interact with.

&nbsp;

@@@

So, let's click the link to "[Verify and Publish](https://etherscan.io/verifyContract2?a=0x3e24bfe40874a2f366ecf05746d6dcbc0cfd6f03) your contract source code today!"

@@@ div { .centered }

<img alt="etherscan-timelock-verify-and-publish" src="../image/etherscan-timelock-verify-and-publish.png" height="918" />

@@@

Usually, to verify, you need the following:

@@@ div { .tight }

  1. Contract Name
  2. Precise compiler version
  3. Solidity source code _as compiled_ (with any imports resolved)
  4. Whether optimization was enabled when you compiled
  5. If optimization was enabled, for how many runs?
  6. Any hex-encoded constructor arguments
  7. Information about any predeployed, linked libraries (not currently supported by _sbt-ethereum_)

@@@

Fortunately, this information is easy to find. We already know our contract's address, but if we had forgotten it,
we can see every contract we've ever deployed using `ethContractCompilationList`.

```
> ethContractCompilationList
+----------+--------------------------------------------+----------+--------------------------------------------------------------------+------------------------------+
| Chain ID | Contract Address                           | Name     | Code Hash                                                          | Deployment Timestamp         |
+----------+--------------------------------------------+----------+--------------------------------------------------------------------+------------------------------+
| 1        | 0x3e24bfe40874a2f366ecf05746d6dcbc0cfd6f03 | Timelock | 0x63af7e585ee23d2c5b729ae29bdca0fd9d1b724b35884f34857691d4322af7d5 | 2019-02-25T01:05:30.862-0800 |
+----------+--------------------------------------------+----------+--------------------------------------------------------------------+------------------------------+
[success] Total time: 0 s, completed Apr 9, 2019 3:00:42 PM
```

(We haven't done too much, so this is conveniently spare. Over time, this list may become much longer.)

Once we know the address of a deployment that we are interested in, we can use `ethContractCompilationInspect` to retrieve basically everything there is to know about it.

@@@ warning

Note that we use the **Contract Address**, not the Code Hash, as our argument to `ethContractCompilationInspect`.

We could also have used the code hash, but that would supply information only about the compilation, not
about the specific deployment to a specific address.

For example, compiler arguments would not be available if we specified the code hash rather
than the deployment address, as the same code could be deployed at many addresses with different
compiler arguments.

When verifying a contract, be sure to specify the deployment address when invoking `ethContractCompilationInspect`
to get complete, deployment-specific information.

@@@

Anyway, let's try `ethContractCompilationInspect`.


```
> ethContractCompilationInspect 0x3e24bfe40874a2f366ecf05746d6dcbc0cfd6f03

-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=
                       CONTRACT INFO DUMP
-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=

------------------------------------------------------------------------
Contract Address (on blockchain with ID 1):

0x3e24bfe40874a2f366ecf05746d6dcbc0cfd6f03

------------------------------------------------------------------------
Deployer Address:

0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2

------------------------------------------------------------------------
Transaction Hash:

0xf58f30d3ea1d077d8054d77b4c03aca333e49e905b47182c2a7d74d4f026e861

------------------------------------------------------------------------
Deployment Timestamp:

Mon Feb 25 01:05:30 PST 2019

------------------------------------------------------------------------
Code Hash:

0x63af7e585ee23d2c5b729ae29bdca0fd9d1b724b35884f34857691d4322af7d5

------------------------------------------------------------------------
Code:

0x6080604081815280610350833981016040528051602090910151600034116100ae57604080517f08c379a000000000000000000000000000000000000000000000000000000000815260206004820152602f60248201527f54686572652773206e6f20706f696e7420696e206372656174696e6720616e2060448201527f656d7074792054696d656c6f636b210000000000000000000000000000000000606482015290519081900360840190fd5b60008054600160a060020a0319163317905542620151809092029190910101600155610271806100df6000396000f3006080604052600436106100565763ffffffff7c01000000000000000000000000000000000000000000000000000000006000350416633ccfd60b811461005b5780638da5cb5b14610072578063b9e3e2db146100b0575b600080fd5b34801561006757600080fd5b506100706100d7565b005b34801561007e57600080fd5b50610087610223565b6040805173ffffffffffffffffffffffffffffffffffffffff9092168252519081900360200190f35b3480156100bc57600080fd5b506100c561023f565b60408051918252519081900360200190f35b60005473ffffffffffffffffffffffffffffffffffffffff16331461015d57604080517f08c379a000000000000000000000000000000000000000000000000000000000815260206004820152601c60248201527f4f6e6c7920746865206f776e65722063616e2077697468647261772100000000604482015290519081900360640190fd5b60015442116101f357604080517f08c379a000000000000000000000000000000000000000000000000000000000815260206004820152602660248201527f43616e6e6f74207769746864726177207072696f7220746f2072656c6561736560448201527f2064617465210000000000000000000000000000000000000000000000000000606482015290519081900360840190fd5b6040513390303180156108fc02916000818181858888f19350505050158015610220573d6000803e3d6000fd5b50565b60005473ffffffffffffffffffffffffffffffffffffffff1681565b600154815600a165627a7a72305820cce6ece2186b4f5d31d2fefcac36b5c0d30fff3f4f0dabce03115f12361b6e090029

------------------------------------------------------------------------
Constructor Inputs Hex:

0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000258
Decoded inputs: 0, 600

------------------------------------------------------------------------
Contract Name:

Timelock

------------------------------------------------------------------------
Contract Source:

/*
 * DO NOT EDIT! DO NOT EDIT! DO NOT EDIT!
 *
 * This is an automatically generated file. It will be overwritten.
 *
 * For the original source see
 *    '/Users/testuser/eth-timelock/src/main/solidity/Timelock.sol'
 */

pragma solidity ^0.4.24;

contract Timelock {
  address public owner;
  uint public releaseDate;

  constructor( uint _days, uint _seconds ) public payable {
    require( msg.value > 0, "There's no point in creating an empty Timelock!" );
    owner = msg.sender;
    releaseDate = now + (_days * 1 days) + (_seconds * 1 seconds);
  }

  function withdraw() public {
    require( msg.sender == owner, "Only the owner can withdraw!" );
    require( now > releaseDate, "Cannot withdraw prior to release date!" );
    msg.sender.transfer( address(this).balance );
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

{"compilationTarget":{"<stdin>":"Timelock"},"evmVersion":"byzantium","libraries":{},"optimizer":{"enabled":true,"runs":200},"remappings":[]}

------------------------------------------------------------------------
ABI Hash:

0xee54f7161577798a7fa6f3103e124afe9989bb7e9f117a3bf92dfbed7fa84b9c

------------------------------------------------------------------------
ABI Definition:

[{"name":"owner","inputs":[],"outputs":[{"name":"","type":"address"}],"constant":true,"payable":false,"stateMutability":"view","type":"function"},{"name":"releaseDate","inputs":[],"outputs":[{"name":"","type":"uint256"}],"constant":true,"payable":false,"stateMutability":"view","type":"function"},{"name":"withdraw","inputs":[],"outputs":[],"constant":false,"payable":false,"stateMutability":"nonpayable","type":"function"},{"inputs":[{"name":"_days","type":"uint256"},{"name":"_seconds","type":"uint256"}],"payable":true,"stateMutability":"payable","type":"constructor"}]

------------------------------------------------------------------------
Metadata:

{"compiler":{"version":"0.4.24+commit.e67f0147"},"language":"Solidity","output":{"abi":[{"constant":false,"inputs":[],"name":"withdraw","outputs":[],"payable":false,"stateMutability":"nonpayable","type":"function"},{"constant":true,"inputs":[],"name":"owner","outputs":[{"name":"","type":"address"}],"payable":false,"stateMutability":"view","type":"function"},{"constant":true,"inputs":[],"name":"releaseDate","outputs":[{"name":"","type":"uint256"}],"payable":false,"stateMutability":"view","type":"function"},{"inputs":[{"name":"_days","type":"uint256"},{"name":"_seconds","type":"uint256"}],"payable":true,"stateMutability":"payable","type":"constructor"}],"devdoc":{"methods":{}},"userdoc":{"methods":{}}},"settings":{"compilationTarget":{"<stdin>":"Timelock"},"evmVersion":"byzantium","libraries":{},"optimizer":{"enabled":true,"runs":200},"remappings":[]},"sources":{"<stdin>":{"keccak256":"0xe23a8858965c9ad498da5aa9b9e375d7ea8820b9447632ba5cec4a4496692da3","urls":["bzzr://02049dd901400d413bed6491b4a0670c2258d0f41ab0ca5f179126e37e7da427"]}},"version":1}

------------------------------------------------------------------------
AST:

{"attributes":{"absolutePath":"<stdin>","exportedSymbols":{"Timelock":[71]}},"children":[{"attributes":{"literals":["solidity","^","0.4",".24"]},"id":1,"name":"PragmaDirective","src":"223:24:0"},{"attributes":{"baseContracts":[null],"contractDependencies":[null],"contractKind":"contract","documentation":null,"fullyImplemented":true,"linearizedBaseContracts":[71],"name":"Timelock","scope":72},"children":[{"attributes":{"constant":false,"name":"owner","scope":71,"stateVariable":true,"storageLocation":"default","type":"address","value":null,"visibility":"public"},"children":[{"attributes":{"name":"address","type":"address"},"id":2,"name":"ElementaryTypeName","src":"271:7:0"}],"id":3,"name":"VariableDeclaration","src":"271:20:0"},{"attributes":{"constant":false,"name":"releaseDate","scope":71,"stateVariable":true,"storageLocation":"default","type":"uint256","value":null,"visibility":"public"},"children":[{"attributes":{"name":"uint","type":"uint256"},"id":4,"name":"ElementaryTypeName","src":"295:4:0"}],"id":5,"name":"VariableDeclaration","src":"295:23:0"},{"attributes":{"constant":false,"documentation":null,"implemented":true,"isConstructor":true,"modifiers":[null],"name":"","payable":true,"scope":71,"stateMutability":"payable","superFunction":null,"visibility":"public"},"children":[{"children":[{"attributes":{"constant":false,"name":"_days","scope":40,"stateVariable":false,"storageLocation":"default","type":"uint256","value":null,"visibility":"internal"},"children":[{"attributes":{"name":"uint","type":"uint256"},"id":6,"name":"ElementaryTypeName","src":"336:4:0"}],"id":7,"name":"VariableDeclaration","src":"336:10:0"},{"attributes":{"constant":false,"name":"_seconds","scope":40,"stateVariable":false,"storageLocation":"default","type":"uint256","value":null,"visibility":"internal"},"children":[{"attributes":{"name":"uint","type":"uint256"},"id":8,"name":"ElementaryTypeName","src":"348:4:0"}],"id":9,"name":"VariableDeclaration","src":"348:13:0"}],"id":10,"name":"ParameterList","src":"334:29:0"},{"attributes":{"parameters":[null]},"children":[],"id":11,"name":"ParameterList","src":"379:0:0"},{"children":[{"children":[{"attributes":{"argumentTypes":null,"isConstant":false,"isLValue":false,"isPure":false,"isStructConstructorCall":false,"lValueRequested":false,"names":[null],"type":"tuple()","type_conversion":false},"children":[{"attributes":{"argumentTypes":[{"typeIdentifier":"t_bool","typeString":"bool"},{"typeIdentifier":"t_stringliteral_cd644da506a5d5e0eff58f2f12c98e5960100675a8354fef65770d4b7a90479f","typeString":"literal_string \"There's no point in creating an empty Timelock!\""}],"overloadedDeclarations":[89,90],"referencedDeclaration":90,"type":"function (bool,string memory) pure","value":"require"},"id":12,"name":"Identifier","src":"385:7:0"},{"attributes":{"argumentTypes":null,"commonType":{"typeIdentifier":"t_uint256","typeString":"uint256"},"isConstant":false,"isLValue":false,"isPure":false,"lValueRequested":false,"operator":">","type":"bool"},"children":[{"attributes":{"argumentTypes":null,"isConstant":false,"isLValue":false,"isPure":false,"lValueRequested":false,"member_name":"value","referencedDeclaration":null,"type":"uint256"},"children":[{"attributes":{"argumentTypes":null,"overloadedDeclarations":[null],"referencedDeclaration":86,"type":"msg","value":"msg"},"id":13,"name":"Identifier","src":"394:3:0"}],"id":14,"name":"MemberAccess","src":"394:9:0"},{"attributes":{"argumentTypes":null,"hexvalue":"30","isConstant":false,"isLValue":false,"isPure":true,"lValueRequested":false,"subdenomination":null,"token":"number","type":"int_const 0","value":"0"},"id":15,"name":"Literal","src":"406:1:0"}],"id":16,"name":"BinaryOperation","src":"394:13:0"},{"attributes":{"argumentTypes":null,"hexvalue":"54686572652773206e6f20706f696e7420696e206372656174696e6720616e20656d7074792054696d656c6f636b21","isConstant":false,"isLValue":false,"isPure":true,"lValueRequested":false,"subdenomination":null,"token":"string","type":"literal_string \"There's no point in creating an empty Timelock!\"","value":"There's no point in creating an empty Timelock!"},"id":17,"name":"Literal","src":"409:49:0"}],"id":18,"name":"FunctionCall","src":"385:75:0"}],"id":19,"name":"ExpressionStatement","src":"385:75:0"},{"children":[{"attributes":{"argumentTypes":null,"isConstant":false,"isLValue":false,"isPure":false,"lValueRequested":false,"operator":"=","type":"address"},"children":[{"attributes":{"argumentTypes":null,"overloadedDeclarations":[null],"referencedDeclaration":3,"type":"address","value":"owner"},"id":20,"name":"Identifier","src":"466:5:0"},{"attributes":{"argumentTypes":null,"isConstant":false,"isLValue":false,"isPure":false,"lValueRequested":false,"member_name":"sender","referencedDeclaration":null,"type":"address"},"children":[{"attributes":{"argumentTypes":null,"overloadedDeclarations":[null],"referencedDeclaration":86,"type":"msg","value":"msg"},"id":21,"name":"Identifier","src":"474:3:0"}],"id":22,"name":"MemberAccess","src":"474:10:0"}],"id":23,"name":"Assignment","src":"466:18:0"}],"id":24,"name":"ExpressionStatement","src":"466:18:0"},{"children":[{"attributes":{"argumentTypes":null,"isConstant":false,"isLValue":false,"isPure":false,"lValueRequested":false,"operator":"=","type":"uint256"},"children":[{"attributes":{"argumentTypes":null,"overloadedDeclarations":[null],"referencedDeclaration":5,"type":"uint256","value":"releaseDate"},"id":25,"name":"Identifier","src":"490:11:0"},{"attributes":{"argumentTypes":null,"commonType":{"typeIdentifier":"t_uint256","typeString":"uint256"},"isConstant":false,"isLValue":false,"isPure":false,"lValueRequested":false,"operator":"+","type":"uint256"},"children":[{"attributes":{"argumentTypes":null,"commonType":{"typeIdentifier":"t_uint256","typeString":"uint256"},"isConstant":false,"isLValue":false,"isPure":false,"lValueRequested":false,"operator":"+","type":"uint256"},"children":[{"attributes":{"argumentTypes":null,"overloadedDeclarations":[null],"referencedDeclaration":88,"type":"uint256","value":"now"},"id":26,"name":"Identifier","src":"504:3:0"},{"attributes":{"argumentTypes":null,"isConstant":false,"isInlineArray":false,"isLValue":false,"isPure":false,"lValueRequested":false,"type":"uint256"},"children":[{"attributes":{"argumentTypes":null,"commonType":{"typeIdentifier":"t_uint256","typeString":"uint256"},"isConstant":false,"isLValue":false,"isPure":false,"lValueRequested":false,"operator":"*","type":"uint256"},"children":[{"attributes":{"argumentTypes":null,"overloadedDeclarations":[null],"referencedDeclaration":7,"type":"uint256","value":"_days"},"id":27,"name":"Identifier","src":"511:5:0"},{"attributes":{"argumentTypes":null,"hexvalue":"31","isConstant":false,"isLValue":false,"isPure":true,"lValueRequested":false,"subdenomination":"days","token":"number","type":"int_const 86400","value":"1"},"id":28,"name":"Literal","src":"519:6:0"}],"id":29,"name":"BinaryOperation","src":"511:14:0"}],"id":30,"name":"TupleExpression","src":"510:16:0"}],"id":31,"name":"BinaryOperation","src":"504:22:0"},{"attributes":{"argumentTypes":null,"isConstant":false,"isInlineArray":false,"isLValue":false,"isPure":false,"lValueRequested":false,"type":"uint256"},"children":[{"attributes":{"argumentTypes":null,"commonType":{"typeIdentifier":"t_uint256","typeString":"uint256"},"isConstant":false,"isLValue":false,"isPure":false,"lValueRequested":false,"operator":"*","type":"uint256"},"children":[{"attributes":{"argumentTypes":null,"overloadedDeclarations":[null],"referencedDeclaration":9,"type":"uint256","value":"_seconds"},"id":32,"name":"Identifier","src":"530:8:0"},{"attributes":{"argumentTypes":null,"hexvalue":"31","isConstant":false,"isLValue":false,"isPure":true,"lValueRequested":false,"subdenomination":"seconds","token":"number","type":"int_const 1","value":"1"},"id":33,"name":"Literal","src":"541:9:0"}],"id":34,"name":"BinaryOperation","src":"530:20:0"}],"id":35,"name":"TupleExpression","src":"529:22:0"}],"id":36,"name":"BinaryOperation","src":"504:47:0"}],"id":37,"name":"Assignment","src":"490:61:0"}],"id":38,"name":"ExpressionStatement","src":"490:61:0"}],"id":39,"name":"Block","src":"379:177:0"}],"id":40,"name":"FunctionDefinition","src":"323:233:0"},{"attributes":{"constant":false,"documentation":null,"implemented":true,"isConstructor":false,"modifiers":[null],"name":"withdraw","payable":false,"scope":71,"stateMutability":"nonpayable","superFunction":null,"visibility":"public"},"children":[{"attributes":{"parameters":[null]},"children":[],"id":41,"name":"ParameterList","src":"577:2:0"},{"attributes":{"parameters":[null]},"children":[],"id":42,"name":"ParameterList","src":"587:0:0"},{"children":[{"children":[{"attributes":{"argumentTypes":null,"isConstant":false,"isLValue":false,"isPure":false,"isStructConstructorCall":false,"lValueRequested":false,"names":[null],"type":"tuple()","type_conversion":false},"children":[{"attributes":{"argumentTypes":[{"typeIdentifier":"t_bool","typeString":"bool"},{"typeIdentifier":"t_stringliteral_1bbc23e0d6ccc8f11093a096b9d74bfc70384e71d4d135c2a1ee2117ef3b9385","typeString":"literal_string \"Only the owner can withdraw!\""}],"overloadedDeclarations":[89,90],"referencedDeclaration":90,"type":"function (bool,string memory) pure","value":"require"},"id":43,"name":"Identifier","src":"593:7:0"},{"attributes":{"argumentTypes":null,"commonType":{"typeIdentifier":"t_address","typeString":"address"},"isConstant":false,"isLValue":false,"isPure":false,"lValueRequested":false,"operator":"==","type":"bool"},"children":[{"attributes":{"argumentTypes":null,"isConstant":false,"isLValue":false,"isPure":false,"lValueRequested":false,"member_name":"sender","referencedDeclaration":null,"type":"address"},"children":[{"attributes":{"argumentTypes":null,"overloadedDeclarations":[null],"referencedDeclaration":86,"type":"msg","value":"msg"},"id":44,"name":"Identifier","src":"602:3:0"}],"id":45,"name":"MemberAccess","src":"602:10:0"},{"attributes":{"argumentTypes":null,"overloadedDeclarations":[null],"referencedDeclaration":3,"type":"address","value":"owner"},"id":46,"name":"Identifier","src":"616:5:0"}],"id":47,"name":"BinaryOperation","src":"602:19:0"},{"attributes":{"argumentTypes":null,"hexvalue":"4f6e6c7920746865206f776e65722063616e20776974686472617721","isConstant":false,"isLValue":false,"isPure":true,"lValueRequested":false,"subdenomination":null,"token":"string","type":"literal_string \"Only the owner can withdraw!\"","value":"Only the owner can withdraw!"},"id":48,"name":"Literal","src":"623:30:0"}],"id":49,"name":"FunctionCall","src":"593:62:0"}],"id":50,"name":"ExpressionStatement","src":"593:62:0"},{"children":[{"attributes":{"argumentTypes":null,"isConstant":false,"isLValue":false,"isPure":false,"isStructConstructorCall":false,"lValueRequested":false,"names":[null],"type":"tuple()","type_conversion":false},"children":[{"attributes":{"argumentTypes":[{"typeIdentifier":"t_bool","typeString":"bool"},{"typeIdentifier":"t_stringliteral_8e56503eca9610c068f596ceae3ad06963a2bce9582a56f1f16c99adf9cc8afa","typeString":"literal_string \"Cannot withdraw prior to release date!\""}],"overloadedDeclarations":[89,90],"referencedDeclaration":90,"type":"function (bool,string memory) pure","value":"require"},"id":51,"name":"Identifier","src":"661:7:0"},{"attributes":{"argumentTypes":null,"commonType":{"typeIdentifier":"t_uint256","typeString":"uint256"},"isConstant":false,"isLValue":false,"isPure":false,"lValueRequested":false,"operator":">","type":"bool"},"children":[{"attributes":{"argumentTypes":null,"overloadedDeclarations":[null],"referencedDeclaration":88,"type":"uint256","value":"now"},"id":52,"name":"Identifier","src":"670:3:0"},{"attributes":{"argumentTypes":null,"overloadedDeclarations":[null],"referencedDeclaration":5,"type":"uint256","value":"releaseDate"},"id":53,"name":"Identifier","src":"676:11:0"}],"id":54,"name":"BinaryOperation","src":"670:17:0"},{"attributes":{"argumentTypes":null,"hexvalue":"43616e6e6f74207769746864726177207072696f7220746f2072656c65617365206461746521","isConstant":false,"isLValue":false,"isPure":true,"lValueRequested":false,"subdenomination":null,"token":"string","type":"literal_string \"Cannot withdraw prior to release date!\"","value":"Cannot withdraw prior to release date!"},"id":55,"name":"Literal","src":"689:40:0"}],"id":56,"name":"FunctionCall","src":"661:70:0"}],"id":57,"name":"ExpressionStatement","src":"661:70:0"},{"children":[{"attributes":{"argumentTypes":null,"isConstant":false,"isLValue":false,"isPure":false,"isStructConstructorCall":false,"lValueRequested":false,"names":[null],"type":"tuple()","type_conversion":false},"children":[{"attributes":{"argumentTypes":[{"typeIdentifier":"t_uint256","typeString":"uint256"}],"isConstant":false,"isLValue":false,"isPure":false,"lValueRequested":false,"member_name":"transfer","referencedDeclaration":null,"type":"function (uint256)"},"children":[{"attributes":{"argumentTypes":null,"isConstant":false,"isLValue":false,"isPure":false,"lValueRequested":false,"member_name":"sender","referencedDeclaration":null,"type":"address"},"children":[{"attributes":{"argumentTypes":null,"overloadedDeclarations":[null],"referencedDeclaration":86,"type":"msg","value":"msg"},"id":58,"name":"Identifier","src":"737:3:0"}],"id":61,"name":"MemberAccess","src":"737:10:0"}],"id":62,"name":"MemberAccess","src":"737:19:0"},{"attributes":{"argumentTypes":null,"isConstant":false,"isLValue":false,"isPure":false,"lValueRequested":false,"member_name":"balance","referencedDeclaration":null,"type":"uint256"},"children":[{"attributes":{"argumentTypes":null,"isConstant":false,"isLValue":false,"isPure":false,"isStructConstructorCall":false,"lValueRequested":false,"names":[null],"type":"address","type_conversion":true},"children":[{"attributes":{"argumentTypes":[{"typeIdentifier":"t_contract$_Timelock_$71","typeString":"contract Timelock"}],"isConstant":false,"isLValue":false,"isPure":true,"lValueRequested":false,"type":"type(address)","value":"address"},"id":63,"name":"ElementaryTypeNameExpression","src":"758:7:0"},{"attributes":{"argumentTypes":null,"overloadedDeclarations":[null],"referencedDeclaration":99,"type":"contract Timelock","value":"this"},"id":64,"name":"Identifier","src":"766:4:0"}],"id":65,"name":"FunctionCall","src":"758:13:0"}],"id":66,"name":"MemberAccess","src":"758:21:0"}],"id":67,"name":"FunctionCall","src":"737:44:0"}],"id":68,"name":"ExpressionStatement","src":"737:44:0"}],"id":69,"name":"Block","src":"587:199:0"}],"id":70,"name":"FunctionDefinition","src":"560:226:0"}],"id":71,"name":"ContractDefinition","src":"249:539:0"}],"id":72,"name":"SourceUnit","src":"223:566:0"}

------------------------------------------------------------------------
Project Name:

eth-timelock
-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=
```

From here, it is mostly just a matter of copy-and-paste. (For the "Compiler" field, it's a matter of matching the dropdown list.)

Let's enter our data into _Etherscan_'s form.

<a name="gotchas"></a>

@@@ warning

**There are some gotchas!**

Contract Name is "Contract Name", not "Project Name", in the output from `ethContractCompilationInspect`.

Information about whether the optimizer was enabled and for how many runs is in "Compiler Options".

**Don't include the '0x' prefix when supplying the hex-encoded constructor arguments!**

**Mac Safari is not well supported.**

If you get mysterious errors about being unable to generate the bytecode, switch to Firefox.
You'll notice that our screenshot have...

@@@

Okay, we've got the gotchas. Let's enter our data into _Etherscan_'s form, copy-paste, copy-paste, including the full source.

@@@ div { .centered }

<img alt="etherscan-timelock-verify-filled" src="../image/etherscan-timelock-verify-filled.png" height="918" />

@@@

Now we just scroll to the bottom, click the "I am not a robot" checkbox (and perform our little Turing Test), and finally
press the "Verify and Publish" button at the bottom.

If all goes well, we should see something like this:

@@@ div { .centered }

<img alt="etherscan-timelock-verify-succeeded" src="../image/etherscan-timelock-verify-succeeded.png" height="918" />

@@@

Now, when we look at the [`code`](https://etherscan.io/address/0x3e24bfe40874a2f366ecf05746d6dcbc0cfd6f03#code) link, we see that our
contract has been verifed. The contract's ABI is now available to the public, by copy and paste or via the _Etherscan_ API.

@@@ div { .centered }

<img alt="etherscan-timelock-verified" src="../image/etherscan-timelock-verified.png" height="918" />

@@@







