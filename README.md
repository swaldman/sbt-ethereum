# SBT-Ethereum

#### An SBT-based develoment environment and command line for Ethereum

## What is this?

sbt-ethereum is very much a work in progress. For the moment you can...

1. Write, compile, and deploy solidity contracts, along with code in Scala and other languages, in a standard SBT project 
2. Send ether to ethereum addresses
3. Generate accounts and wallets

Eventually, some goals are

- to offer a convenient command line for a lot of ethereum-related stuff
- to provide a means of methodically keeping track of contract ABIs and other meta-information

This is new, largely untested code (based on [consuela](https://github.com/swaldman/consuela), also a new, largely untested library). Nothing is yet stable about this project.
Expect everything to change. Obviously, NO WARRANTIES, see the [LICENSE](LICENSE) and all of that. But I'd love it if you gave it a (cautious) try.

## Getting Started

1. `sbt-ethereum` is not a standalone Ethereum client. It requires access to a provider of [Ethereum's JSON-RPC API](https://github.com/ethereum/wiki/wiki/JSON-RPC),
preferably (for security reasons) running only on the localhost interface. sbt-ethereum has mostly been tried against [go-ethereum](https://github.com/ethereum/go-ethereum) by
running `geth --rpc`, but it should work with any client that supports the JSON-RPC interface.

2. You'll need to download [sbt](http://www.scala-sbt.org) if you don't have it already. This is an "autoplugin", which requires sbt 0.13.5 or later.

3. Define a standard project (see [below](https://gist.github.com/swaldman/38ffc4f069a8672b2b86841892fd6762#sbt-project-layout)) that includes the plugin and
sets an `ethAddress` key, representing the address from which you will be deploying your contracts and/or sending ether

4. Enter your project's top-level directory in a terminal and type `sbt` to enter the environment. sbt-ethereum functionality will now be available.

## SBT project layout

A minimal SBT project looks something like this:

     my-project/
          |
          |—— build.sbt
          |
          |—— src/
          |    |
          |    +—— main/
          |          |
          |          +—— solidity/
          |                  |
          |                  +—— my_contract.sol
          |
          |—— project/
                 |
                 +—— plugins.sbt
                 
Here's a minimal `build.sbt`:

    name := "YouTopia"
    
    version := "0.0.1-SNAPSHOT"
    
And `plugins.sbt`:

    // only necessary while using a SNAPSHOT version of sbt-ethereum
    resolvers += ("snapshots" at "https://oss.sonatype.org/content/repositories/snapshots")

    addSbtPlugin("com.mchange" % "sbt-ethereum" % "0.0.1-SNAPSHOT" changing())

## Using the SBT command line

Make sure a full Ethereum client is running with JSON-RPC enabled. (You can just open a terminal window and leave it up while `geth --rpc` runs there.)
If you haven't already, you'll need to let the blockchain sync, which might take a long time. (Leave `geth` running overnight.)

Open a fresh terminal window, go into your project's top-level director (`my-project/` not `project/` in the example project above), and run `sbt`. If
you intend to deploy contracts or send ether, you'll want to specify an ethereum address on whose behalf it will act. You can do that three ways:

- Set the environment variable `ETH_ADDRESS` prior to running `sbt`
- Set the JVM System property `eth.address` when running `sbt`, that is, run
```
> `sbt -Deth.address=0x465e79b940bc2157e4259ff6b2d92f454497f1e4`
```
- At any time, on the SBT command like, run
```
> set ethAddress := "0x465e79b940bc2157e4259ff6b2d92f454497f1e4"
```

Your `ethAddress` will be stable throughout your interactive session (unless you reset it with `set ethAddress` as above).
You will be prompted for its passphrase only once. (Be careful, as commands to send ether or deploy contracts will execute
without further ceremony.)

### Sending ether

To send ether:

    > ethSendEther 0xae79b77e31387a3b2409b70c27cebc7220101026 1500 wei

This will send 1500 wei to address `0xae79b77e31387a3b2409b70c27cebc7220101026` from the configured `ethAddress`. You will be asked for a credential, which can be the passphrase to a V3 ethereum wallet (currently expected in `geth`'s standard keystore directory) or can be a hex private key directly. (Input will be masked, of course, but it's probably safer to keep your private key ever-encrypted.)   

You can use the `<tab>` key for suggestions and completions. Instead of `wei`, you can use denominations `ether`, `finney`, or `szabo`.

### Compiling and deploying contracts

Compilation of soldity contracts is integrated into sbt's standard compilation pipeline. So, just type

    > compile
    
To compile your contracts.

To deploy one of the contracts in your `src/main/solidity` directory, use the `ethDeployOnly` task:

    > ethDeployOnly my_contract

Again, you'll be asked for a credential of the `ethAddress` that you supplied in `build.sbt` as a setting. If your contracts have not yet been compiled, they will be compiled automatically first.

If your contract deploys successfully, you'll see no failure, and information about the deployment will be logged in sbt-ethereum's repository. For the moment, ABI metadata is not yet stored in
the repository, but an entry is added to the file `transaction-log` in your repository directory, showing the deployment
transaction and the transaction's hash. The ABI can be found, embedded in the JSON files that result from your compilation under the directory `${project-root}/target/ethereum/solidity`
(which will be automatically created by the build).

A message indicating the address of your newly deployed contract will usually be emitted. (If for some reason sbt-ethereum cannot get a transaction receipt with the address,
try an ethereum blockchain browser ([ether.camp](https://live.ether.camp), [etherchain](https://www.etherchain.org), [etherscan](http://etherscan.io)) to lookup the address of your deployed contract.
You can search on the transaction hash, or on the address from which you are deploying.)

### Generating accounts and wallets

To generate a new account, the safest and most straightforward approach is to generate a geth-style V3 wallet:

    > ethGenWalletV3
    
Follow the prompts (to enter and confirm a masked passphrase), and an account and wallet will be generated.
The wallet will reside in the sbt-ethereum repository (see below), under `keystore/v3`, in a format and under filenames that are interoperable with `geth`.
(You can copy these files directly into your `geth` keystore if you'd like.)

__Note: Be sure to test a wallet before sending signficant value to it. And then back it up!__

One way to test your new wallet is with `ethSelfPing`, which causes the currently set `ethAddress` to send a zero ether transaction to itself.
You'll need to send a small amount of ether to your new account, so that the ping can succeed.

    > ethGenWalletV3
    [info] Generated keypair for address '0x47cd9e257d144a2d54d20b1b3695f939f5208b10'
    [info] Generating V3 wallet, alogorithm=scrypt, n=262144, r=8, p=1, dklen=32
    Enter passphrase for new wallet: ****************************
    Please retype to confirm: ****************************
    [success] Total time: 19 s, completed Jul 23, 2016 6:59:30 PM

    > ethSendEther 0x47cd9e257d144a2d54d20b1b3695f939f5208b10 1 finney
    [info] V3 wallet found for '0x465e79b940bc2157e4259ff6b2d92f454497f1e4'
    Enter passphrase or hex private key for address '465e79b940bc2157e4259ff6b2d92f454497f1e4': *******************
    [info] Sent 1000000000000000 wei to address '0x47cd9e257d144a2d54d20b1b3695f939f5208b10' in transaction '0xee0e8006e451eec50a93d128d4d61aa43f892a8c27f83b00f853e793054dfe59'.
    ...

    > set ethAddress := "0x47cd9e257d144a2d54d20b1b3695f939f5208b10"
    [info] Defining *:ethAddress
    ...

    > ethSelfPing
    [info] V3 wallet found for '0x47cd9e257d144a2d54d20b1b3695f939f5208b10'
    Enter passphrase or hex private key for address '0x47cd9e257d144a2d54d20b1b3695f939f5208b10': ****************************
    [info] Sent 0 wei to address '0x47cd9e257d144a2d54d20b1b3695f939f5208b10' in transaction '0xc4ed21cfe6c2a2dd3e2a7c2deec7e436451ba3461ea9b8890be72893fc901546'.
    ...
    [info] Ping succeeded! Sent 0 ether from '0x47cd9e257d144a2d54d20b1b3695f939f5208b10' to itself in transaction '0xc4ed21cfe6c2a2dd3e2a7c2deec7e436451ba3461ea9b8890be72893fc901546'

## The sbt-ethereum repository

sbt-ethereum's repository directory, located in

- Windows: `%APPDATA%\sbt-ethereum`
- Mac: `~/Library/sbt-ethereum`
- Other Unix: `~/.sbt-ethereum`

__You should be sure to back up this directory, as any account wallets you have created in sbt-ethereum will be stored in the repository's keystore!__

For the moment (this may evolve rapidly), the repository contains sbt-ethereum's `transaction-log`, and a `keystore` directory.
If you have generated new account wallets, those will be found under `keystore/V3` in a format and under filenames that are interoperable with `geth`.
(You can copy these files into your `geth` keystore if you'd like.)

## SBT Keys Added

These are likely to be pretty fluid. From within `sbt`, you can see a quick description of settings with

    > settings -V ^eth
    
and

    > tasks -V ^eth

You can also just type

    > eth<tab>

and let sbt's tab completion offer suggestions.

## Troubleshooting

If you don't see the `eth*` tasks and settings, you may be running an older version of sbt.

Consider adding a `build.properties` file under `my-project/project` like this:

    sbt.version=0.13.12

Even if your sbt-launcher is from an older version, this will cause `sbt` to manage your project
from a sufficiently recent sbt.
