# SBT-Ethereum

#### An SBT-based develoment environment and command line for Ethereum

## What is this?

sbt-ethereum is very much a work in progress. For the moment you can...

1. Write, compile, and deploy solidity contracts, along with code in Scala and other languages, in a standard SBT project 
2. Send ether to ethereum addresses

Eventually, some goals are

- to offer a convenient command line for a lot of ethereum-related stuff
- to provide a means of methodically keeping track of contract ABIs and other meta-information

This is new, largely untested code (based on [consuela](https://github.com/swaldman/consuela), also a new, largely untested library). Nothing is yet stable about this project. Expect everything to change. Obviously, NO WARRANTIES, see the [LICENSE](LICENSE) and all of that. But I'd love it if you gave it a (cautious) try.

## Getting Started

1. `sbt-ethereum` is not a standalone Ethereum client. It requires access to a provider of [Ethereum's JSON-RPC API](https://github.com/ethereum/wiki/wiki/JSON-RPC), preferably (for security reasons) running only on the localhost interface. sbt-ethereum has mostly been tried against [go-ethereum](https://github.com/ethereum/go-ethereum) by running `geth --rpc`, but it should work with any client that supports the JSON-RPC interface.

2. You'll need to download [sbt](http://www.scala-sbt.org) if you don't have it already. This is an "autoplugin", which requires sbt 0.13.5 or later.

3. Define a standard project (see [below](https://gist.github.com/swaldman/38ffc4f069a8672b2b86841892fd6762#sbt-project-layout)) that includes the plugin and sets an `ethAddress` key, representing the address from which you will be deploying your contracts and/or sending ether

4. Enter your project's top-level directory in a terminal and type `sbt` to enter the environment. sbt-ethereum functionality will now be available.

## SBT project layout

A minimal SBT project looks something like this:

     my-project/
          |
          |—— build.sbt
          |
          |—— src/
          |    |
          |    +—— solidity/
          |            |
          |            +—— my_contract.sol
          |
          |—— project/
                 |
                 +—— plugins.sbt
                 
Here's a minimal `build.sbt`:

    project := "YouTopia"
    
    version := "0.0.1-SNAPSHOT"
    
    ethAddress := "0x465e79b940bc2157e4259ff6b2d92f454497f1e4"

And `plugins.sbt`:

    addSbtPlugin("com.mchange" % "sbt-ethereum" % "0.0.1-SNAPSHOT" changing())

## Using the SBT command line

Make sure a full Ethereum client is running with JSON-RPC enabled. (You can just open a terminal window and leave it up while `geth --rpc` runs there.) Open a fresh terminal window, go into your project's top-level director (`my-project/` not `project/` in the example project above), and type `sbt`. 

To send ether:

    > ethSendEther 0xae79b77e31387a3b2409b70c27cebc7220101026 1500 wei

This will send 1500 wei to address `0xae79b77e31387a3b2409b70c27cebc7220101026` from the configured `ethAddress`. You will be asked for a credential, which can be the passphrase to a V3 ethereum wallet (currently expected in `geth`'s standard keystore directory) or can be a hex private key directly. (Input will be masked, of course, but it's probably safer to keep your private key ever-encrypted.)   

You can use the `<tab>` key for suggestions and completions. Instead of `wei`, you can use denominations `ether`, `finney`, or `szabo`.

Compilation of soldity contracts is integrated into sbt's standard compilation pipeline. So, just type

    > compile
    
To compile your contracts.

To deploy one of the contracts in your `src/solidity` directory, use the `ethDeployOnly` task:

    > ethDeployOnly my_contract

Again, you'll be asked for a credential of the `ethAddress` that you supplied in `build.sbt` as a setting. If your contracts have not yet been compiled, they will be compiled automatically first.

If your contract deploys successfully, you'll see no failure, and information about the deployment will be logged in sbt-ethereum's repository directory, located in

- Windows: `%APPDATA%\sbt-ethereum`
- Mac: `~/Library/sbt-ethereum`
- Other Unix: `~/.sbt-ethereum`

For the moment, ABI metadata is not yet stored in the repository, but an entry is added to the file `transaction-log` in your repository directory, showing the deployment transaction and the transaction's hash. The ABI can be found, embedded in the JSON files that result from your compilation under the directory `${project-top}/target/ethereum/solidity` (which will be automatically created by the build).

For now, use an ethereum blockchain browser ([ether.camp](https://live.ether.camp), [etherchain](https://www.etherchain.org), [etherscan](http://etherscan.io)) to lookup the address of your deployed contract.

## SBT Keys Added

These are likely to be pretty fluid. The docs may not be up-to-date. From within `sbt`, you can see a quick description of settings with

    > settings -V ^eth
    
and

    > tasks -V ^eth

### Settings

- `ethAddress` -- a `String`, the ethereum address that will be responsible for actions from this project. [Defaults to "0x0000000000000000000000000000000000000000"]

- `ethGasMarkup` -- a `Double`, the fraction by which the automatically estimated gas for a deployment should be marked up in setting transaction gas limits. [Defaults to `0.2`, which means a 20% markup.]

- `ethGasOverrides` -- a `Map[String,BigInt]`, lets you directly override the gas amount that would otherwise be automatically computed and marked up for deployment of contracts. If a contract name is in the map, its associated value will be the gas given for the contract's deployment. [Defaults to an empty `Map`.]

- `ethGasPrice` -- a `BigInt`, the gas price in `wei`, lets you set the gas price directly for your transactions rather than relying on 
the current default gas price. [Defaults to `0`, which signifies reverting to the default gas price.] `// maybe rename to ethForceGasPrice, make it easy to set markups over default rather than just a specific override?`

- `ethGethKeystore` -- a `File`, a directory which contains ethereum V3 wallets, named according to `go-ethereum` keystore conventions. If a wallet for `ethAddress` is contained in this directory, its passphrase can be used as a credential when deploying contracts or sending ETH. [Defaults to the platform-specific `go-ethereum` keystore directory.]

- `ethJsonRpcVersion` -- a `String`, the JSON-RPC version. Currently this is not checked or used for anything. [Defaults to `2.0`]

- `ethJsonRpcUrl` -- a `String`, the URL of the JSON-RPC service. [Defaults to `"http://localhost:8545"`]

- `ethSolidityDestination in Compile` -- a `File`, the directory into which solidity compilations will be emitted as JSON files. [Defaults to `${(sourceDirectory in Compile).value}/solidity`, which is usually just `src/solidity` beneath the project root.]

- `ethSoliditySource in Compile` -- a `File`, the directory which will be searched for solidity source files for compilation. [Defaults to `${ethTargetDir.value}/solidity`, which is usually just `target/ethereum/solidity` beneath the project root.]
 
- `ethTargetDir` -- a `File`, the directory into which sbt-ethereum should generate reproducible artifacts (like compilations). [Defaults to `${target.value}/ethereum`, where `${target.value}` is SBT's main target, which is usually just called `target` beneath the project root.]

### Tasks

- `ethCompileSolidity` -- returns `Unit`, compiles source files from `ethSoliditySource` into `ethSolidityDestination`

- `ethDefaultGasPrice` -- returns `BigInt`, looks up the current default gas price via JSON-RPC

- `ethDeployOnly` -- returns `EthHash` of transaction in which the contract was deployed, an `InputTask` that accepts arguments in the form
    ethSendEther <contract-name>
deploys the named contract

- `ethGethWallet` -- returns `Option[wallet.V3]`, which will hold an object representing an ethereum V3 wallet if one is available for `ethAddress` in `ethGethKeystore`, or be `None` if no wallet is available

- `ethGetCredential` -- returns `Option[String]`, tries to read a passphrase or a hex private key for `ethAddress`

- `ethLoadCompilations` -- returns `Map[String,jsonrpc20.Compilation.Contract]`, a mapping of contract names to compiled contracts. Forces compilation is it has not already occurred.

- `ethNextNonce` -- returns `BigInt`, looks up the nonce that should be used for the next transaction from `ethAddress`

- `ethSendEther` -- returns `EthHash` of transaction in which the ether was sent, an `InputTask` that accepts arguments in the form
    ethSendEther <destination-eth-address> <amount> <ether|finney|szabo|wei>
sends ETH from `ethAddress` to the destination address.
