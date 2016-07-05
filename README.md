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

For the moment, ABI metadata is not yet stored in the repository, but an entry is added to the file `transaction-log` in your repository directory, showing the deployment transaction and the transaction's hash. The ABI can be found, embedded in the JSON files that result from your compilation under the directory `${project-root}/target/ethereum/solidity` (which will be automatically created by the build).

For now, use an ethereum blockchain browser ([ether.camp](https://live.ether.camp), [etherchain](https://www.etherchain.org), [etherscan](http://etherscan.io)) to lookup the address of your deployed contract.

## SBT Keys Added

These are likely to be pretty fluid. The docs may not be up-to-date. From within `sbt`, you can see a quick description of settings with

    > settings -V ^eth
    
and

    > tasks -V ^eth
