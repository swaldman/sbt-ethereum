# Creating a Smart Contract

We will start by developing a very simple smart contract, a "timelock" that accepts a payment of `ETH` when the contract is created,
and only allows that `ETH` to be withdrawn by the creator of the contract, and after a specified delay.

### Create a development repository

In prior tutorials, we've run `sbt` by executing the `./sbtw` wrapper script within the `eth-command-line` repository.

Now we will need to make our own repository, for our own newly developed smart contracts.

It's best to @ref:[install the `sbt` command](../appendix/prerequisites.md) on your command line before developing your own smart contracts.
Once you've done that, we can get started.

The easiest way to create a fresh sbt-ethereum repository is to use the [giter8](http://www.foundweekends.org/giter8/) template `swaldman/solidity-seed.g8`:

```
$ sbt new swaldman/solidity-seed.g8
```

You'll be prompted with some interactive queries:
```
[info] Loading project definition from /Users/testuser/project
[info] Set current project to testuser (in build file:/Users/testuser/)

A minimal solidity project for sbt-ethereum 

name [my-solidity-project]: eth-timelock
version [0.0.1-SNAPSHOT]: 
sbt_ethereum_version [0.1.7-SNAPSHOT]: 
sbt_version [1.2.8]: 
chain_id [1]: 

Template applied in ./eth-timelock
```

Voila! You'll find a new directory called `eth-timelock` has been created. It's a fully functional _sbt-ethereum_ repository.
If you `cd` into that directory and type `sbt`, you will get an _sbt-ethereum_ command line just as rich as the `eth-command-line`
we've used previously.

@@@ note

__Use version control software!__

If you use @ref:[git](../appendix/prerequisites.md#git) or similar version control software, now would be a good time
to initialize the repository and perform an initial commit!

&nbsp;

@@@

### Define your smart contract source code

In your repository, you will find a folder called `src/main/solidity`. Solidity is the most common language for developing
_Ethereum_ smart contracts. Any files that you place in this directory that end in the suffix `.sol` will be compiled by
_sbt-ethereum_ as Solidity smart contracts.

Create a file in this directory called `Timelock.sol`, and paste in the following contents:
```
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
```

### Compile the source code

From inside your `eth-timelock` directory, type `sbt`, then the command `compile`:
```
$ sbt
[info] Loading settings for project eth-timelock from build.sbt ...
[info] Set current project to eth-timelock (in build file:/Users/testuser/eth-timelock/)
[info] Updating available solidity compiler set.
[info] sbt-ethereum-0.1.7-SNAPSHOT successfully initialized (built Sun, 17 Feb 2019 21:58:11 -0800)
sbt:eth-timelock> compile
[info] Compiling 1 Solidity source to /Users/testuser/eth-timelock/target/ethereum/solidity...
[info] Compiling 'Timelock.sol'. (Debug source: '/Users/testuser/eth-timelock/target/ethereum/solidity/Timelock.sol')
[info] No Scala stubs will be generated as the setting 'ethcfgScalaStubsPackage' has not ben set.
[info] If you'd like Scala stubs to be generated, please define 'ethcfgScalaStubsPackage'.
[info] Updating ...
[info] Done updating.
[success] Total time: 1 s, completed Feb 25, 2019 12:16:01 AM
```

### Deploying the contract

Once your smart contract has been compiled, you should be able to deploy it. The command you will want to use is @ref:[`ethTransactionDeploy`](../tasks/eth/transaction/index.md#ethtransactiondeploy)

The smart contract we are deploying has a _constructor_ that includes arguments, and is payable. Let's use tab completion to check that out:
```
sbt:eth-timelock> ethTransactionDeploy <tab>
Timelock
```
If we `<tab>` after the `ethTransactionDeploy` command, we get a list of contracts that we can deploy, that have been compiled within our repository.
In our case, the only contract is `Timelock`. Let's tab again to see what's next:
```
sbt:eth-timelock> ethTransactionDeploy Timelock 
<_days, of type uint256>   ​
```
The next constructor argument is the number of days delay the contract will enforce before permiting us to withdraw whatever we have paid into it.
We don't want to wait days to test this thing, so let's just say 0, and hit `<tab>` again.
```
sbt:eth-timelock> ethTransactionDeploy Timelock 0 <tab>
<_seconds, of type uint256>
```
In addition to some number of days, the contract allows us to specify a number of seconds in the delay it will enforce. Let's try a 10 minute &mdash; that is, 600 second, delay.
```​                              
sbt:eth-timelock> ethTransactionDeploy Timelock 0 600 <tab>
[ETH to pay, optional]   ether                    finney                   gwei                     szabo                    wei
```
Now the contract is asking for an optional ETH amount to pay. As we saw in @ref[a previous tutorial](working-with-ether.md#sending-ether), we specify ETH amounts as a number
and a unit, one of `wei`, `gwei`, `szabo`, `finney`, or `ether`. A `wei` is the tiniest unit (it represents 10<sup>-18</sup> ETH). Since we are only testing this contract out,
let's deposit only 1 wei.
```
sbt:eth-timelock> ethTransactionDeploy Timelock 0 600 1 wei
[info] Unlocking address '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2' (on chain with ID 1, aliases ['default-sender'])
Enter passphrase or hex private key for address '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2': ***************
[info] V3 wallet(s) found for '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2' (aliases ['default-sender'])
[info] Waiting for the transaction to be mined (will wait up to 5 minutes).

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a contract creation with...
==>   From:  0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2 (with aliases ['default-sender'] on chain with ID 1)
==>   Init:  0x6080604081815280610350833981016040528051602090910151600034116100ae57604080517f08c379a000000000000000000000000000000000000000000000000000000000815260206004820152602f60248201527f54686572652773206e6f20706f696e7420696e206372656174696e6720616e2060448201527f656d7074792054696d656c6f636b210000000000000000000000000000000000606482015290519081900360840190fd5b60008054600160a060020a0319163317905542620151809092029190910101600155610271806100df6000396000f3006080604052600436106100565763ffffffff7c01000000000000000000000000000000000000000000000000000000006000350416633ccfd60b811461005b5780638da5cb5b14610072578063b9e3e2db146100b0575b600080fd5b34801561006757600080fd5b506100706100d7565b005b34801561007e57600080fd5b50610087610223565b6040805173ffffffffffffffffffffffffffffffffffffffff9092168252519081900360200190f35b3480156100bc57600080fd5b506100c561023f565b60408051918252519081900360200190f35b60005473ffffffffffffffffffffffffffffffffffffffff16331461015d57604080517f08c379a000000000000000000000000000000000000000000000000000000000815260206004820152601c60248201527f4f6e6c7920746865206f776e65722063616e2077697468647261772100000000604482015290519081900360640190fd5b60015442116101f357604080517f08c379a000000000000000000000000000000000000000000000000000000000815260206004820152602660248201527f43616e6e6f74207769746864726177207072696f7220746f2072656c6561736560448201527f2064617465210000000000000000000000000000000000000000000000000000606482015290519081900360840190fd5b6040513390303180156108fc02916000818181858888f19350505050158015610220573d6000803e3d6000fd5b50565b60005473ffffffffffffffffffffffffffffffffffffffff1681565b600154815600a165627a7a72305820cce6ece2186b4f5d31d2fefcac36b5c0d30fff3f4f0dabce03115f12361b6e09002900000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000258
==>   Value: 1E-18 Ether
==>
==> The nonce of the transaction would be 7.
==>
==> $$$ The transaction you have requested could use up to 317328 units of gas.
==> $$$ You would pay 18 gwei for each unit of gas, for a maximum cost of 0.005711904 ether.
==> $$$ This is worth 0.785758073760 USD (according to Coinbase at 1:05 AM).
==> You would also send 1E-18 ether (1.37565E-16 USD), for a maximum total cost of 0.005711904000000001 ether (0.785758073760000137565 USD).

Would you like to submit this transaction? [y/n] y
```
We wait a while, then...
```
A transaction with hash '0xf58f30d3ea1d077d8054d77b4c03aca333e49e905b47182c2a7d74d4f026e861' will be submitted. Please wait.
[info] Transaction Receipt:
[info]        Transaction Hash:    0xf58f30d3ea1d077d8054d77b4c03aca333e49e905b47182c2a7d74d4f026e861
[info]        Transaction Index:   107
[info]        Transaction Status:  SUCCEEDED
[info]        Block Hash:          0xdf5237645e7cfb9dc95c2a89756bb55528c3adefadcc8d858223671642982a0c
[info]        Block Number:        7265310
[info]        From:                0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2
[info]        To:                  Unknown
[info]        Cumulative Gas Used: 7553899
[info]        Gas Used:            264440
[info]        Contract Address:    0x3e24bfe40874a2f366ecf05746d6dcbc0cfd6f03
[info]        Logs:                None
[info]        Events:              None
[info] Contract 'Timelock' deployed in transaction '0xf58f30d3ea1d077d8054d77b4c03aca333e49e905b47182c2a7d74d4f026e861'.
[info] Contract 'Timelock' has been assigned address '0x3e24bfe40874a2f366ecf05746d6dcbc0cfd6f03'.
Enter an optional alias for the newly deployed 'Timelock' contract at '0x3e24bfe40874a2f366ecf05746d6dcbc0cfd6f03' (or [return] for none):
```
It asks us for an alias for our new contract's address. Let's give it one:
```
Enter an optional alias for the newly deployed 'Timelock' contract at '0x3e24bfe40874a2f366ecf05746d6dcbc0cfd6f03' (or [return] for none): timelock
[info] Alias 'timelock' now points to address '0x3e24bfe40874a2f366ecf05746d6dcbc0cfd6f03' (for chain with ID 1).
[info] Refreshing caches.
[success] Total time: 47 s, completed Feb 25, 2019 1:05:40 AM
```
Our contract is deployed! We can take a look in [_Etherscan_](http://www.etherscan.io/) by searching for its new address `0x3e24bfe40874a2f366ecf05746d6dcbc0cfd6f03`.

@@@ div { .centered }

<img alt="etherscan-new-timelock" src="../image/etherscan-new-timelock.png" height="906" />

@@@


### Testing the contract

Quickly, quickly, within the 10 minute delay, let's try to see what happens if we attempt an "early withdrawal". Let's
try the command `ethTransactionMock`, which asks our _Ethereum_ node to simulate, but does not actually, permanently, execute a transaction.
We don't expect this transaction to work anyway, and we don't want to pay for or actually make a change to the blockchain while trying.
```
sbt:eth-timelock> ethTransactionMock timelock withdraw
[warn] Simulating the result of calling nonconstant function 'withdraw'.
[warn] An actual transaction would occur sometime in the future, with potentially different results!
[warn] No changes that would have been made to the blockchain by a call of this function will be preserved.
[error] com.mchange.sc.v2.jsonrpc.package$JsonrpcException: gas required exceeds allowance or always failing transaction [code=-32000]: No further information
[error] 	at com.mchange.sc.v2.jsonrpc.Response$Error.vomit(Response.scala:12)
[error] 	at com.mchange.sc.v1.consuela.ethereum.jsonrpc.Client$Implementation$Exchanger.$anonfun$responseHandler$1(Client.scala:282)
[error] 	at scala.util.Success.$anonfun$map$1(Try.scala:251)
[error] 	at scala.util.Success.map(Try.scala:209)
[error] 	at scala.concurrent.Future.$anonfun$map$1(Future.scala:288)
[error] 	at scala.concurrent.impl.Promise.liftedTree1$1(Promise.scala:29)
[error] 	at scala.concurrent.impl.Promise.$anonfun$transform$1(Promise.scala:29)
[error] 	at scala.concurrent.impl.CallbackRunnable.run(Promise.scala:60)
[error] 	at java.util.concurrent.ForkJoinTask$RunnableExecuteAction.exec(ForkJoinTask.java:1402)
[error] 	at java.util.concurrent.ForkJoinTask.doExec(ForkJoinTask.java:289)
[error] 	at java.util.concurrent.ForkJoinPool$WorkQueue.runTask(ForkJoinPool.java:1056)
[error] 	at java.util.concurrent.ForkJoinPool.runWorker(ForkJoinPool.java:1692)
[error] 	at java.util.concurrent.ForkJoinWorkerThread.run(ForkJoinWorkerThread.java:157)
[error] (Compile / ethTransactionMock) com.mchange.sc.v2.jsonrpc.package$JsonrpcException: gas required exceeds allowance or always failing transaction [code=-32000]: No further information
[error] Total time: 0 s, completed Feb 25, 2019 1:10:05 AM
```
The error "gas required exceeds allowance or always failing transaction [code=-32000]: No further information" is what you often see if
a requirement fails or something causes the transaction to revert. This is an "always failing transaction', in the sense that for the moment,
no matter how much gas you give it, it would fail, since withdrawal is not permitted until the delay has passed.

Now let's wait until our 10 minute delay has passed, and simulate our transaction again.
```
sbt:eth-timelock> ethTransactionMock timelock withdraw
[warn] Simulating the result of calling nonconstant function 'withdraw'.
[warn] An actual transaction would occur sometime in the future, with potentially different results!
[warn] No changes that would have been made to the blockchain by a call of this function will be preserved.
[info] The function withdraw yields no result.
[success] Total time: 0 s, completed Feb 25, 2019 1:25:43 AM
```
It looks like our transaction would have worked, but if we check _Etherscan_ we will see that nothing has happened. Our
contract still has a balance of the `1 wei` we sent it, and there are no transactions to it beyond the contract creation.
That's becuase we've used `ethTransactionMock`, which yields only a simulation. To actually withdraw the `1 wei` our contract
holds, we have to (after the delay period has ended) use `ethTransactionInvoke`:
```
sbt:eth-timelock> ethTransactionInvoke timelock withdraw
[info] Unlocking address '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2' (on chain with ID 1, aliases ['default-sender'])
Enter passphrase or hex private key for address '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2': ***************
[info] V3 wallet(s) found for '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2' (aliases ['default-sender'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x3e24bfe40874a2f366ecf05746d6dcbc0cfd6f03 (with aliases ['timelock'] on chain with ID 1)
==>   From:  0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2 (with aliases ['default-sender'] on chain with ID 1)
==>   Data:  0x3ccfd60b
==>   Value: 0 Ether
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: withdraw()
==>
==> The nonce of the transaction would be 8.
==>
==> $$$ The transaction you have requested could use up to 38392 units of gas.
==> $$$ You would pay 12 gwei for each unit of gas, for a maximum cost of 0.000460704 ether.
==> $$$ This is worth 0.063413602080 USD (according to Coinbase at 1:28 AM).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0x9f36bc1dc473198fb952f388302e33823d3dbac2514c75c849b2fedc7960e30f' will be submitted. Please wait.
```
Now we wait a few minutes, until...
```
[info] Called function 'withdraw', with args '', sending 0 wei to address '0x3e24bfe40874a2f366ecf05746d6dcbc0cfd6f03' in transaction '0x9f36bc1dc473198fb952f388302e33823d3dbac2514c75c849b2fedc7960e30f'.
[info] Waiting for the transaction to be mined (will wait up to 5 minutes).
[info] Transaction Receipt:
[info]        Transaction Hash:    0x9f36bc1dc473198fb952f388302e33823d3dbac2514c75c849b2fedc7960e30f
[info]        Transaction Index:   99
[info]        Transaction Status:  SUCCEEDED
[info]        Block Hash:          0x3df21be080343808e95da771aae138a9c8477f3d48d1cd62e00e1288235b9dbf
[info]        Block Number:        7265385
[info]        From:                0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2
[info]        To:                  0x3e24bfe40874a2f366ecf05746d6dcbc0cfd6f03
[info]        Cumulative Gas Used: 4816236
[info]        Gas Used:            29739
[info]        Contract Address:    None
[info]        Logs:                None
[info]        Events:              None
[success] Total time: 102 s, completed Feb 25, 2019 1:30:31 AM
```
Now, if we refresh _Etherscan_, we'll see that the `1 wei` we paid into the contract on construction is gone.
The contract balance is `0 ether`. To see the transaction in which the `1 wei` was sent back to the contract's owner,
check the `Internal Txns` tab.