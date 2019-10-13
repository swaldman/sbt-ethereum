# eth

The vast majority of _sbt-ethereum_ tasks begin with an `eth`. Prefix. Typing
```
> eth<tab>
```
will yield too many tasks to make sense of.

@@@ note

_**There are some tasks that do not begin with `eth`**_

@@@@ div {.tight}
 * @ref:[ENS tasks](../ens.md) (`ens*`)
 * @ref:[ERC-20 tasks](../erc20.md) (`erc20*`)
 * @ref:[Etherscan tasks](../etherscan.md) (`etherscan*`)
@@@@

@@@

And then there is one simple task @ref:[`eth`](#eth), which gives a quick summary of the state of your current session.

### eth

@@@ div {.keydesc}

**Usage:**
```
> eth
```

Logs a quick summary of the state of the current user session. It will print the current session's [chain ID and node URL](node/index.md), [sender address](address/sender.md), and whether
any the sender, node URL, contact ABIs, gas limits, gas price, or next transaction nonce have been overridden from their default or automatic values
for this session.

**Example:**
```
> eth
[info] The session is now active on chain with ID 1, with node URL 'https://ethjsonrpc.mchange.com/'.
[info] The current session sender is '0x465e79b940bc2157e4259ff6b2d92f454497f1e4' (with aliases ['default-sender','testing0'] on chain with ID 1).
[info] The current default gas price according to your node is 1.8 gwei. (THIS MAY CHANGE AT ANY TIME.)
[info]  + Your current session includes an override of the gas price, default gas price plus a markup of 0.50 (50.00%), not subject to any cap or floor.
[info]  + So, the actual price you would pay is 2.7 gwei.
[warn] NOTE: A gas price override remains set for this chain, default gas price plus a markup of 0.50 (50.00%), not subject to any cap or floor.
[success] Total time: 1 s, completed Oct 13, 2019 1:17:43 AM
```

@@@