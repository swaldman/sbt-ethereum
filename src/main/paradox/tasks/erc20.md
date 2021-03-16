# erc20*

ERC-20 tokens are perhaps (and perhaps unfortunately) the most prominent current application on _ethereum_.
They are defined by straightforward smart contracts, which you can interact with from _sbt-ethereum_ in the usual way,
using @ref:[`ethTransactionView`](./eth/transaction/index.md#ethtransactionview) and @ref:[`ethTransactionInvoke`](./eth/transaction/index.md#ethtransactioninvoke).
They include the following standard functions:

```
function totalSupply() public view returns (uint);
function balanceOf(address tokenOwner) public view returns (uint balance);
function allowance(address tokenOwner, address spender) public view returns (uint remaining);
function transfer(address to, uint atoms) public returns (bool success);
function approve(address spender, uint atoms) public returns (bool success);
function transferFrom(address from, address to, uint atoms) public returns (bool success);
```

**Understanding atoms vs tokens**

Note that in three of those functions, `transfer`, `approve`, and `transferFrom` is an argument called `atoms`. In most actual ERC-20 smart contracts
it is not called that. It is called, "value", or sometimes even "tokens". But it's best to understand the units ERC-20 smart contracts work with as
_atoms_, because the word _token_ is usually reserved for something else.

In particular, most ERC-20 token contracts also include the following function:
```
function decimals() public returns (uint8);
```

If this function exists, it should always return the same constant value, it defined how many "atoms" (the things actually managed by an ERC-20 contract)
should be understood by humans to constitute a single "token". For example, if `decimals()` returns `18` (its most common value), then one "token" should
be understood by humans as `1 000 000 000 000 000 000` (1 followed by 18 zeroes) of the atoms the smart contract actually manages. You can think of "atoms"
as being something like very small token-cents and "tokens" as token-dollars. One "token", if `decimals` returns `18`, is 10<sup>18</sup> atoms.

Because ERC-20 smart contracts work in atoms but humans mostly think in tokens, it's useful to have utilities for humans that work in tokens, that automatically
convert to and from atoms as necessary. That is what _sbt-ethereum_'s erc20* tasks do.

**Understanding "allowances"**

ERC-20 contracts have a notion of an "allowance", where the owner of some tokens grants some address the right to transact up to a specified limit
on its account. This is important if you wish to fund or pay token-using smart contracts. Rather than directly send ERC-20 tokens to smart contracts,
it's best to first set an allowance and let the smart contracts take what you wish to make available to them.

(If you send tokens directly to a smart
contract that doesn't expect or no how to use them, you will lose those tokens forever. Simple ERC-20 token contracts cannot refuse unexpected token
payments, or refund them. They are not notified of payments in tokens, so they cannot respond to such payments in any way. So many smart contracts
are designed to fund themselves with the tokens they expect to interact with, after users set an allowance.)

You can set or reset or revoke (reset to 0) the allowances for the tokens your address controls at any time.

**Other ERC-20 conventions**

ERC-20 token contracts often support the following functions, in addition to the ones noted above:
```
function name() public returns (string);
function symbol() public returns (string);
```
These are intended to describe the contract, but **be careful**! Smart-contract authors can make these function return anything they'd like.
Be sure you know what token you are interacting with. Don't rely on the self-reported symbol and name.

You can access all of a token's standard self-reported fields using the @ref:[`erc20Summary`](#erc20summary) command.

### erc20AllowancePrint

@@@ div { .keydesc }

**Usage:**
```
> erc20AllowancePrint <token-contract-address-as-hex-or-ens-or-alias> <token-owner-address-as-hex-or-ens-or-alias> <approved-spender-address-as-hex-or-ens-or-alias>
```

Checks the current allowance, on an ERC-20 token contract, from a given token owner, for a given spender.

**Example:**
```
> erc20AllowancePrint TEST 0x465e79b940bc2157e4259ff6b2d92f454497f1e4 0xae79b77e31387a3b2409b70c27cebc7220101026
[info] For ERC20 Token Contract '0xc9da38847c36c8c01b4ad2f4febf3945a63c739d' (with aliases ['TEST'] on chain with ID 1), with 18 decimals...
[info]   Of tokens owned by '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (with aliases ['default-sender','testing0'] on chain with ID 1))...
[info]     For use by '0xae79b77e31387a3b2409b70c27cebc7220101026' (with aliases ['testing1'] on chain with ID 1)...
[info]       An allowance of 1000 tokens (which corresponds to 1000000000000000000000 atoms) has been approved.
[success] Total time: 1 s, completed Apr 5, 2019 9:16:57 PM
```

@@@

### erc20AllowanceSet

@@@ div { .keydesc }

**Usage:**
```
> erc20AllowanceSet <token-contract-address-as-hex-or-ens-or-alias> <approved-spender-address-as-hex-or-ens-or-alias> <amount-in-human-friendly-tokens>
```

Sets an allowance, on an ERC-20 token contract, from the current sender, to the given approved sender, of an amount given as HUMAN-FRIENDLY TOKENS (not atoms).

**Example:**
```
> erc20AllowanceSet TEST 0xae79b77e31387a3b2409b70c27cebc7220101026 1000
[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************
[info] V3 wallet(s) found for '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (aliases ['default-sender','testing0'])
[warn] For the ERC20 token with contract address '0xc9da38847c36c8c01b4ad2f4febf3945a63c739d' (with aliases ['TEST'] on chain with ID 1)...
[warn]   you would approve use of...
[warn]     Amount:     1000 tokens, which (with 18 decimals) translates to 1000000000000000000000 atoms.
[warn]     Owned By:   '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (with aliases ['default-sender','testing0'] on chain with ID 1)
[warn]     For Use By: '0xae79b77e31387a3b2409b70c27cebc7220101026' (with aliases ['testing1'] on chain with ID 1)
[warn] You are calling the 'approve' function on the contract at '0xc9da38847c36c8c01b4ad2f4febf3945a63c739d' (with aliases ['TEST'] on chain with ID 1).
[warn] THIS FUNCTION COULD DO ANYTHING. 
[warn] Make sure that you trust that the token contract does only what you intend, and carefully verify the transaction cost before approving the ultimate transaction.
Continue? [y/n] y

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0xc9da38847c36c8c01b4ad2f4febf3945a63c739d (with aliases ['TEST'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  0x095ea7b3000000000000000000000000ae79b77e31387a3b2409b70c27cebc722010102600000000000000000000000000000000000000000000003635c9adc5dea00000
==>   Value: 0 Ether
==>
==> The transaction is signed with Chain ID 1 (which correctly matches the current session's 'ethNodeChainId').
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: approve(address,uint256)
==>     Arg 1 [name=_spender, type=address]: 0xae79b77e31387a3b2409b70c27cebc7220101026
==>     Arg 2 [name=_value, type=uint256]: 1000000000000000000000
==>
==> The nonce of the transaction would be 396.
==>
==> $$$ The transaction you have requested could use up to 54758 units of gas.
==> $$$ You would pay 3 gwei for each unit of gas, for a maximum cost of 0.000164274 ether.
==> $$$ This is worth 0.02693436504 USD (according to Coinbase at 9:10 PM).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0xb8e538633af604c35dce46d47cd97249e113e5dea56c09cc7087b2068c5fe8d8' will be submitted. Please wait.
[info] ERC20 Allowance Approval, Token Contract '0xc9da38847c36c8c01b4ad2f4febf3945a63c739d' (with aliases ['TEST'] on chain with ID 1):
[info]   --> Approved 1000 tokens (1000000000000000000000 atoms)
[info]   -->   owned by '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (with aliases ['default-sender','testing0'] on chain with ID 1)
[info]   -->   for use by '0xae79b77e31387a3b2409b70c27cebc7220101026' (with aliases ['testing1'] on chain with ID 1)
[info] Waiting for the transaction to be mined (will wait up to 5 minutes).
[info] Transaction Receipt:
[info]        Transaction Hash:    0xb8e538633af604c35dce46d47cd97249e113e5dea56c09cc7087b2068c5fe8d8
[info]        Transaction Index:   141
[info]        Transaction Status:  SUCCEEDED
[info]        Block Hash:          0x6a24c3fdb93898f243f251258f15338db84546e214d2c046aea0fdf1bad2cd75
[info]        Block Number:        7512324
[info]        From:                0x465e79b940bc2157e4259ff6b2d92f454497f1e4
[info]        To:                  0xc9da38847c36c8c01b4ad2f4febf3945a63c739d
[info]        Cumulative Gas Used: 7139980
[info]        Gas Used:            45632
[info]        Contract Address:    None
[info]        Logs:                0 => EthLogEntry [source=0xc9da38847c36c8c01b4ad2f4febf3945a63c739d] (
[info]                                    topics=[
[info]                                      0x8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925,
[info]                                      0x000000000000000000000000465e79b940bc2157e4259ff6b2d92f454497f1e4,
[info]                                      0x000000000000000000000000ae79b77e31387a3b2409b70c27cebc7220101026
[info]                                    ],
[info]                                    data=00000000000000000000000000000000000000000000003635c9adc5dea00000
[info]                                  )
[info]        Events:              0 => Approval [source=0xc9da38847c36c8c01b4ad2f4febf3945a63c739d] (
[info]                                    tokenOwner (of type address): 0x465e79b940bc2157e4259ff6b2d92f454497f1e4,
[info]                                    spender (of type address): 0xae79b77e31387a3b2409b70c27cebc7220101026,
[info]                                    tokens (of type uint256): 1000000000000000000000
[info]                                  )
[success] Total time: 103 s, completed Apr 5, 2019 9:11:29 PM
```

@@@

### erc20Balance

@@@ div { .keydesc }

**Usage:**
```
> erc20Balance <token-contract-address-as-hex-or-ens-or-alias> [optional-token-owner-address-as-hex-or-ens-or-alias]
```

Checks the balance, of a token represented by a given ERC-20 token contract, for an owner if given (or the session's current sender, if none is given).

**Example:**
```
> erc20Balance TEST 0xc33071ead8753b04e0ee108cc168f2b22f93525d
[info] For ERC20 Token Contract '0xc9da38847c36c8c01b4ad2f4febf3945a63c739d' (with aliases ['TEST'] on chain with ID 1), with 18 decimals...
[info]   For Address '0xc33071ead8753b04e0ee108cc168f2b22f93525d' (with aliases ['testing3'] on chain with ID 1))...
[info]     Balance: 50000 tokens (which corresponds to 50000000000000000000000 atoms)
[success] Total time: 0 s, completed Apr 5, 2019 9:34:42 PM
```

@@@

### erc20ConvertAtomsToTokens

@@@ div { .keydesc }

**Usage:**
```
> erc20ConvertAtomsToTokens <token-contract-address-as-hex-or-ens-or-alias> <amount-in-atoms>
```

For a given ERC-20 token contract, using _and trusting_ its self-reported `decimals()`, convert a given number of atoms to a number of human-friendly tokens.

**Example:**
```
> erc20ConvertAtomsToTokens TEST 12345678900000000000000
[info] For ERC20 Token Contract '0xc9da38847c36c8c01b4ad2f4febf3945a63c739d' (with aliases ['TEST'] on chain with ID 1), with 18 decimals, 12345678900000000000000 atoms translates to...
[info] 12345.6789 tokens.
[success] Total time: 0 s, completed Apr 5, 2019 9:40:14 PM
```

@@@

### erc20ConvertTokensToAtoms

@@@ div { .keydesc }

**Usage:**
```
> erc20ConvertTokensToAtoms <token-contract-address-as-hex-or-ens-or-alias> <amount-in-human-friendly-tokens>
```

For a given ERC-20 token contract, using _and trusting_ its self-reported `decimals()`, convert a given number of human-friendly tokens into machine-expected atoms.

**Example:**
```
> erc20ConvertTokensToAtoms TEST 12345.6789
[info] For ERC20 Token Contract '0xc9da38847c36c8c01b4ad2f4febf3945a63c739d' (with aliases ['TEST'] on chain with ID 1), with 18 decimals, 12345.6789 tokens translates to...
[info] 12345678900000000000000 atoms.
[success] Total time: 1 s, completed Apr 5, 2019 9:44:52 PM
```

@@@

### erc20Summary

@@@ div { .keydesc }

**Usage:**
```
> erc20Summary <token-contract-address-as-hex-or-ens-or-alias>
```
For a given ERC-20 token contract, print standard self-reported metainformation.

**Example:**
```
> erc20Summary TEST
[info] ERC20 Summary, token contract at '0xc9da38847c36c8c01b4ad2f4febf3945a63c739d' (with aliases ['TEST'] on chain with ID 1):
[info]   Self-Reported Name:   Test Token
[info]   Self-Reported Symbol: TEST
[info]   Decimals:             18
[info]   Total Supply:         1000000 tokens (1000000000000000000000000 atoms)
[success] Total time: 1 s, completed Apr 5, 2019 9:47:12 PM
```

@@@

### erc20Transfer

@@@ div { .keydesc }

**Usage:**
```
> erc20Transfer <token-contract-address-as-hex-or-ens-or-alias> <recipient-address-as-hex-or-ens-or-alias> <amount-in-human-friendly-tokens>
```

For an ERC-20 token contract, transfer from the current sender, to the given recipient, of an amount given as HUMAN-FRIENDLY TOKENS (not atoms).

**Example:**
```
> erc20Transfer TEST 0xc33071ead8753b04e0ee108cc168f2b22f93525d 50000
[info] Unlocking address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (on chain with ID 1, aliases ['default-sender','testing0'])
Enter passphrase or hex private key for address '0x465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************
[info] V3 wallet(s) found for '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (aliases ['default-sender','testing0'])
[warn] For the ERC20 token with contract address '0xc9da38847c36c8c01b4ad2f4febf3945a63c739d' (with aliases ['TEST'] on chain with ID 1)...
[warn]   you would transfer 50000 tokens, which (with 18 decimals) translates to 50000000000000000000000 atoms.
[warn] The transfer would be 
[warn]   From: '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (with aliases ['default-sender','testing0'] on chain with ID 1)
[warn]   To:   '0xc33071ead8753b04e0ee108cc168f2b22f93525d' (with aliases ['testing3'] on chain with ID 1)
[warn] You are calling the 'transfer' function on the contract at '0xc9da38847c36c8c01b4ad2f4febf3945a63c739d' (with aliases ['TEST'] on chain with ID 1).
[warn] THIS FUNCTION COULD DO ANYTHING. 
[warn] Make sure that you trust that the token contract does only what you intend, and carefully verify the transaction cost before approving the ultimate transaction.
Continue? [y/n] y

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0xc9da38847c36c8c01b4ad2f4febf3945a63c739d (with aliases ['TEST'] on chain with ID 1)
==>   From:  0x465e79b940bc2157e4259ff6b2d92f454497f1e4 (with aliases ['default-sender','testing0'] on chain with ID 1)
==>   Data:  0xa9059cbb000000000000000000000000c33071ead8753b04e0ee108cc168f2b22f93525d000000000000000000000000000000000000000000000a968163f0a57b400000
==>   Value: 0 Ether
==>
==> The transaction is signed with Chain ID 1 (which correctly matches the current session's 'ethNodeChainId').
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: transfer(address,uint256)
==>     Arg 1 [name=_to, type=address]: 0xc33071ead8753b04e0ee108cc168f2b22f93525d
==>     Arg 2 [name=_value, type=uint256]: 50000000000000000000000
==>
==> The nonce of the transaction would be 397.
==>
==> $$$ The transaction you have requested could use up to 62636 units of gas.
==> $$$ You would pay 3 gwei for each unit of gas, for a maximum cost of 0.000187908 ether.
==> $$$ This is worth 0.030842279580 USD (according to Coinbase at 9:33 PM).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0xa7eeff0f0e9a27a66a661509a8a8203050327cc09ec07e9bb77bff98776e60f6' will be submitted. Please wait.
[info] ERC20 Transfer, Token Contract '0xc9da38847c36c8c01b4ad2f4febf3945a63c739d' (with aliases ['TEST'] on chain with ID 1):
[info]   --> Sent 50000 tokens (50000000000000000000000 atoms)
[info]   -->   from '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (with aliases ['default-sender','testing0'] on chain with ID 1)
[info]   -->   to '0xc33071ead8753b04e0ee108cc168f2b22f93525d' (with aliases ['testing3'] on chain with ID 1)
[info] Waiting for the transaction to be mined (will wait up to 5 minutes).
[info] Transaction Receipt:
[info]        Transaction Hash:    0xa7eeff0f0e9a27a66a661509a8a8203050327cc09ec07e9bb77bff98776e60f6
[info]        Transaction Index:   123
[info]        Transaction Status:  SUCCEEDED
[info]        Block Hash:          0xf5ac60b712624e01c98223235afd6a8610095eb631bd0268fa3a31b6adb1e7bc
[info]        Block Number:        7512441
[info]        From:                0x465e79b940bc2157e4259ff6b2d92f454497f1e4
[info]        To:                  0xc9da38847c36c8c01b4ad2f4febf3945a63c739d
[info]        Cumulative Gas Used: 6464153
[info]        Gas Used:            52197
[info]        Contract Address:    None
[info]        Logs:                0 => EthLogEntry [source=0xc9da38847c36c8c01b4ad2f4febf3945a63c739d] (
[info]                                    topics=[
[info]                                      0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef,
[info]                                      0x000000000000000000000000465e79b940bc2157e4259ff6b2d92f454497f1e4,
[info]                                      0x000000000000000000000000c33071ead8753b04e0ee108cc168f2b22f93525d
[info]                                    ],
[info]                                    data=000000000000000000000000000000000000000000000a968163f0a57b400000
[info]                                  )
[info]        Events:              0 => Transfer [source=0xc9da38847c36c8c01b4ad2f4febf3945a63c739d] (
[info]                                    from (of type address): 0x465e79b940bc2157e4259ff6b2d92f454497f1e4,
[info]                                    to (of type address): 0xc33071ead8753b04e0ee108cc168f2b22f93525d,
[info]                                    tokens (of type uint256): 50000000000000000000000
[info]                                  )
[success] Total time: 59 s, completed Apr 5, 2019 9:34:15 PM
```

@@@
