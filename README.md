# SBT-Ethereum

#### An SBT-based develoment environment and command line for Ethereum

## What is this?

sbt-ethereum is very much a work in progress. For the moment you can...

1. Write, compile, and deploy solidity contracts, along with code in Scala and other languages, in a standard SBT project 
2. Send ether to ethereum addresses
3. Generate accounts and wallets
4. Interact with already-deployed ethereum smart contracts
5. Keep track of ABIs and other metainformation regarding contracts you develop or interact with

**_This is new, largely untested code (based on [consuela](https://github.com/swaldman/consuela), also a new, fluid, and largely untested library). Nothing is yet stable about this project.
Expect everything to change. Obviously, NO WARRANTIES, see the [LICENSE](LICENSE) and all of that. But I'd love it if you gave it a (cautious) try._**

## Getting Started

1. `sbt-ethereum` is not a standalone Ethereum client. It requires access to a provider of [Ethereum's JSON-RPC API](https://github.com/ethereum/wiki/wiki/JSON-RPC),
preferably (for security reasons) running only on the localhost interface. sbt-ethereum has mostly been tried against [go-ethereum](https://github.com/ethereum/go-ethereum) by
running `geth --rpc`, but it should work with any client that supports the JSON-RPC interface.

2. You'll need to download [sbt](http://www.scala-sbt.org) if you don't have it already. sbt-ethereum is an "autoplugin", which requires sbt 0.13.5 or later.

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

    addSbtPlugin("com.mchange" % "sbt-ethereum" % "0.0.1")

## Using the SBT command line

Make sure a full Ethereum client is running with JSON-RPC enabled. (You can just open a terminal window and leave it up while `geth --rpc` runs there.)
If you haven't already, you'll need to let the blockchain sync, which might take a long time. (Leave `geth` running overnight, or use its new fast-sync.)

Open a fresh terminal window, go into your project's top-level director (`my-project/` not `project/` in the example project above), and run `sbt`. If
you intend to deploy contracts or send ether, you'll want to specify an ethereum address on whose behalf it will act. You can do that three ways:

- Set the environment variable `ETH_ADDRESS` prior to running `sbt`
- Set the JVM System property `eth.address` when running `sbt`, that is, run `sbt -Deth.address=0x465e79b940bc2157e4259ff6b2d92f454497f1e4`
- At any time, on the SBT command like, run
```
> set ethAddress := "0x465e79b940bc2157e4259ff6b2d92f454497f1e4"
```    

Your `ethAddress` will be stable throughout your interactive session (unless you reset it with `set ethAddress` as above).
You will be prompted for its passphrase only once. (Be careful, as commands to send ether or deploy contracts will execute
without further ceremony.)

You can also specify the ethereum address you wish to work from directly within your `build.sbt` file, by adding

    ethAddress := "0x465e79b940bc2157e4259ff6b2d92f454497f1e4"

But keep in mind, if you are distributing your code, an `ethAddress` specified in the build file will not be portable
to other developers.

If geth is running on the local machine, everything should "just work", but if you want to interact with a JSON-RPC server on another machine or on a nonstandard
port, you can set the key `ethJsonRpcUrl`, either in `build.sbt` or by typing

    > set ethJsonRpcUrl := "http://my.host:8545"

### Sending ether

**_Please bear in mind that sbt-ethereum is young, largely untested code, offered without warranties. Don't try high-stakes stuff, or work from high-stakes addresses!_**

To send ether:

    > ethSendEther 0xae79b77e31387a3b2409b70c27cebc7220101026 1500 wei

This will send 1500 wei to address `0xae79b77e31387a3b2409b70c27cebc7220101026` from the configured `ethAddress`. You will be asked for a credential, which can be the passphrase to a V3 ethereum wallet (currently expected in `geth`'s standard keystore directory) or can be a hex private key directly. (Input will be masked, of course, but it's probably safer to keep your private key ever-encrypted.)   

You can use the `<tab>` key for suggestions and completions. Instead of `wei`, you can use denominations `ether`, `finney`, or `szabo`.

### Compiling and deploying contracts

Compilation of soldity contracts is integrated into sbt's standard compilation pipeline. So, just type

    > compile

You can have sbt continually compile your contracts, every time you save by typing
    
To compile your contracts.

    > ~compile

To deploy one of the contracts in your `src/main/solidity` directory, use the `ethDeployOnly` task:

    > ethDeployOnly my_contract

If your contracts are already compiled, tab completion will help you select a deployable contract.

Unless you have already provided it, you'll be asked for a credential of the `ethAddress` that you supplied (see above). If your contracts have not yet been compiled, they will be compiled automatically first.

If your contract deploys successfully, you'll see no failure, and information about the deployment will be logged in sbt-ethereum's repository. For the moment, ABI metadata is not yet stored in
the repository, but an entry is added to the file `transaction-log` in your repository directory, showing the deployment
transaction and the transaction's hash. The ABI can be found, embedded in the JSON files that result from your compilation under the directory `${project-root}/target/ethereum/solidity`
(which will be automatically created by the build).

A message indicating the address of your newly deployed contract will usually be emitted. (If for some reason sbt-ethereum cannot get a transaction receipt with the address,
try an ethereum blockchain browser ([ether.camp](https://live.ether.camp), [etherchain](https://www.etherchain.org), [etherscan](http://etherscan.io)) to lookup the address of your deployed contract.
You can search on the transaction hash, or on the address from which you are deploying.)

### Interacting with deployed smart contracts

**_Please bear in mind that sbt-ethereum is young, largely untested code, offered without warranties. Don't try high-stakes stuff, or work from high-stakes addresses!_**

Metainformation about any smart contract you deploy, from any project, will be saved in the sbt-ethereum database. You can interact with those contracts using
the `ethInvoke` task. [_Note: The call below is restricted to the contract's issuer, `0x465e79b940bc2157e4259ff6b2d92f454497f1e4`. You won't be able to try this example at home._]

    > ethInvoke 0xde895c2c73e9f5332c90e3e7ffa705f751f747b0 <tab>
    balances              cancelRedemption      issue                 issuer                redeem                redemptionRequests    requestRedemption     totalSupply           transfer              uncommittedBalances
    > ethInvoke 0xde895c2c73e9f5332c90e3e7ffa705f751f747b0 issue <tab>
    <recipient, of type address>   ​ <amount, of type uint256>     ​ ​ <message, of type string>    ​ ​ ​                             issuer
    > ethInvoke 0xde895c2c73e9f5332c90e3e7ffa705f751f747b0 issue 0xfbace661786d580ed4373c79689e7e8eb6ba05df 888 "Good luck!"
    [info] V3 wallet found for '0x465e79b940bc2157e4259ff6b2d92f454497f1e4'
    [info] Call data for function call: ebf469dc000000000000000000000000fbace661786d580ed4373c79689e7e8eb6ba05df00000000000000000000000000000000000000000000000000000000000003780000000000000000000000000000000000000000000000000000000000000060000000000000000000000000000000000000000000000000000000000000000a476f6f64206c75636b2100000000000000000000000000000000000000000000
    [info] Gas estimated for function call: 64486
    [info] Called function 'issue', with args '0xfbace661786d580ed4373c79689e7e8eb6ba05df, 888, "Good luck!"', sending 0 wei to address '0xde895c2c73e9f5332c90e3e7ffa705f751f747b0' in transaction '0x6065dc98620f5f3e9683b1dd4941ed1e865eee3efd0d71a1868c9b302b2fbe90'.
    [info] Receipt for transaction '0x6065dc98620f5f3e9683b1dd4941ed1e865eee3efd0d71a1868c9b302b2fbe90' not yet available, will try again in 15 seconds. Attempt 1/9.
    [info] Receipt for transaction '0x6065dc98620f5f3e9683b1dd4941ed1e865eee3efd0d71a1868c9b302b2fbe90' not yet available, will try again in 15 seconds. Attempt 2/9.
    [info] Receipt for transaction '0x6065dc98620f5f3e9683b1dd4941ed1e865eee3efd0d71a1868c9b302b2fbe90' not yet available, will try again in 15 seconds. Attempt 3/9.
    [info] Receipt for transaction '0x6065dc98620f5f3e9683b1dd4941ed1e865eee3efd0d71a1868c9b302b2fbe90' not yet available, will try again in 15 seconds. Attempt 4/9.
    [info] Receipt received for transaction '0x6065dc98620f5f3e9683b1dd4941ed1e865eee3efd0d71a1868c9b302b2fbe90':
    [info] ClientTransactionReceipt(Keccak256[6065dc98620f5f3e9683b1dd4941ed1e865eee3efd0d71a1868c9b302b2fbe90],Unsigned256(6),Keccak256[574bb5fe35bdd82fa6a5f02ae2e74899665ffb76965e7dacc36d5e4d0fa14260],Unsigned256(2833845),Unsigned256(196033),Unsigned256(53739),None,List(EthLogEntry(EthAddress(ByteSeqExact20(0xde895c2c73e9f5332c90e3e7ffa705f751f747b0)),Vector(ByteSeqExact32(0x56efce5854bf1d184d330cc0c05667850247efb9dd786f6efac4c3cf93e7e60f), ByteSeqExact32(0x000000000000000000000000fbace661786d580ed4373c79689e7e8eb6ba05df)),ImmutableArraySeq.Byte(0x00000000000000000000000000000000000000000000000000000000000003780000000000000000000000000000000000000000000000000000000000000040000000000000000000000000000000000000000000000000000000000000000a476f6f64206c75636b2100000000000000000000000000000000000000000000))))
    [success] Total time: 61 s, completed Dec 18, 2016 3:41:29 PM

You get a lot of ugly noise when invoking a smart constract method. But one piece of information you do not get is a return value. When you want to look up
information about a smart contract, do so by calling `constant` functions using the task `ethCallEphemeral`:

    > ethCallEphemeral 0xde895c2c73e9f5332c90e3e7ffa705f751f747b0 balances 0xfbace661786d580ed4373c79689e7e8eb6ba05df
    [info] Call data for function call: 27e235e3000000000000000000000000fbace661786d580ed4373c79689e7e8eb6ba05df
    [info] Gas estimated for function call: 28231
    [info] Raw result of call to function 'balances': 0x0000000000000000000000000000000000000000000000000000000000000378
    [info] Decoded return value of type 'uint256': 888
    [success] Total time: 1 s, completed Dec 18, 2016 3:45:38 PM

If you wish to interact with a smart contract that you did not deploy from sbt-ethereum, you'll have to import its ABI into the sbt-ethereum database.

    > ethMemorizeAbi
    Contract address in hex: 0x18a672E11D637fffADccc99B152F4895Da069601
    Contract ABI: [{"constant":false,"inputs":[{"name":"name","type":"string"}],"name":"setNickname","outputs":[],"type":"function"},{"constant":false,"inputs":[{"name":"amountToWithdrawInWei","type":"uint256"}],"name":"withdrawInvestment","outputs":[],"type":"function"},{"constant":false,"inputs":[],"name":"manualUpdateBalances_only_Dev","outputs":[],"type":"function"},{"constant":true,"inputs":[],"name":"checkProfitLossSinceInvestorChange","outputs":[{"name":"profit_since_update_balances","type":"uint256"},{"name":"loss_since_update_balances","type":"uint256"},{"name":"profit_VIP_since_update_balances","type":"uint256"}],"type":"function"},{"constant":false,"inputs":[{"name":"extraInvestFeesRate_0_to_99","type":"uint8"}],"name":"voteOnNewEntryFees_only_VIP","outputs":[],"type":"function"},{"constant":false,"inputs":[{"name":"newInvestorAccountOwner","type":"address"},{"name":"newInvestorAccountOwner_confirm","type":"address"}],"name":"transferInvestorAccount","outputs":[],"type":"function"},{"constant":true,"inputs":[],"name":"getTotalGambles","outputs":[{"name":"_totalGambles","type":"uint256"}],"type":"function"},{"constant":false,"inputs":[],"name":"disableBetting_only_Dev","outputs":[],"type":"function"},{"constant":false,"inputs":[{"name":"Red","type":"bool"},{"name":"Black","type":"bool"}],"name":"betOnColor","outputs":[],"type":"function"},{"constant":true,"inputs":[],"name":"investmentEntryInfos","outputs":[{"name":"current_max_nb_of_investors","type":"uint256"},{"name":"investLockPeriod","type":"uint256"},{"name":"voted_Fees_Rate_on_extra_investments","type":"uint256"}],"type":"function"},{"constant":true,"inputs":[{"name":"player","type":"address"}],"name":"checkMyBet","outputs":[{"name":"player_status","type":"uint8"},{"name":"bettype","type":"uint8"},{"name":"input","type":"uint8"},{"name":"value","type":"uint256"},{"name":"result","type":"uint8"},{"name":"wheelspinned","type":"bool"},{"name":"win","type":"bool"},{"name":"blockNb","type":"uint256"},{"name":"blockSpin","type":"uint256"},{"name":"gambleID","type":"uint256"}],"type":"function"},{"constant":true,"inputs":[{"name":"index","type":"uint256"}],"name":"getInvestorList","outputs":[{"name":"investor","type":"address"},{"name":"endLockPeriod","type":"uint256"}],"type":"function"},{"constant":false,"inputs":[],"name":"enableBetting_only_Dev","outputs":[],"type":"function"},{"constant":false,"inputs":[{"name":"new_dev","type":"address"}],"name":"changeDeveloper_only_Dev","outputs":[],"type":"function"},{"constant":true,"inputs":[],"name":"getSettings","outputs":[{"name":"maxBet","type":"uint256"},{"name":"blockDelayBeforeSpin","type":"uint8"}],"type":"function"},{"constant":true,"inputs":[],"name":"getPayroll","outputs":[{"name":"payroll_at_last_update_balances","type":"uint256"}],"type":"function"},{"constant":true,"inputs":[{"name":"_address","type":"address"}],"name":"getNickname","outputs":[{"name":"_name","type":"string"}],"type":"function"},{"constant":false,"inputs":[{"name":"Low","type":"bool"},{"name":"High","type":"bool"}],"name":"betOnLowHigh","outputs":[],"type":"function"},{"constant":false,"inputs":[{"name":"numberChosen","type":"uint8"}],"name":"betOnNumber","outputs":[],"type":"function"},{"constant":false,"inputs":[{"name":"Odd","type":"bool"},{"name":"Even","type":"bool"}],"name":"betOnOddEven","outputs":[],"type":"function"},{"constant":false,"inputs":[],"name":"splitProfitVIP_only_Dev","outputs":[],"type":"function"},{"constant":true,"inputs":[{"name":"index","type":"uint256"}],"name":"getGamblesList","outputs":[{"name":"player","type":"address"},{"name":"bettype","type":"uint8"},{"name":"input","type":"uint8"},{"name":"value","type":"uint256"},{"name":"result","type":"uint8"},{"name":"wheelspinned","type":"bool"},{"name":"win","type":"bool"},{"name":"blockNb","type":"uint256"},{"name":"blockSpin","type":"uint256"}],"type":"function"},{"constant":false,"inputs":[{"name":"First","type":"bool"},{"name":"Second","type":"bool"},{"name":"Third","type":"bool"}],"name":"betOnDozen","outputs":[],"type":"function"},{"constant":false,"inputs":[{"name":"newCasinoStatLimit","type":"uint256"},{"name":"newMaxBetsBlock","type":"uint256"},{"name":"newMinGamble","type":"uint256"},{"name":"newMaxGamble","type":"uint256"},{"name":"newMaxInvestor","type":"uint16"},{"name":"newMinInvestment","type":"uint256"},{"name":"newMaxInvestment","type":"uint256"},{"name":"newLockPeriod","type":"uint256"},{"name":"newBlockDelay","type":"uint8"},{"name":"newBlockExpiration","type":"uint8"}],"name":"changeSettings_only_Dev","outputs":[],"type":"function"},{"constant":false,"inputs":[{"name":"First","type":"bool"},{"name":"Second","type":"bool"},{"name":"Third","type":"bool"}],"name":"betOnColumn","outputs":[],"type":"function"},{"constant":false,"inputs":[{"name":"spin_for_player","type":"address"}],"name":"spinTheWheel","outputs":[],"type":"function"},{"constant":false,"inputs":[],"name":"invest","outputs":[],"type":"function"},{"constant":true,"inputs":[{"name":"investor","type":"address"}],"name":"checkInvestorBalance","outputs":[{"name":"balanceInWei","type":"uint256"}],"type":"function"},{"inputs":[],"type":"constructor"},{"anonymous":false,"inputs":[{"indexed":false,"name":"player","type":"address"},{"indexed":false,"name":"result","type":"uint8"},{"indexed":false,"name":"value_won","type":"uint256"},{"indexed":false,"name":"bHash","type":"bytes32"},{"indexed":false,"name":"sha3Player","type":"bytes32"},{"indexed":false,"name":"gambleId","type":"uint256"}],"name":"Win","type":"event"},{"anonymous":false,"inputs":[{"indexed":false,"name":"player","type":"address"},{"indexed":false,"name":"result","type":"uint8"},{"indexed":false,"name":"value_loss","type":"uint256"},{"indexed":false,"name":"bHash","type":"bytes32"},{"indexed":false,"name":"sha3Player","type":"bytes32"},{"indexed":false,"name":"gambleId","type":"uint256"}],"name":"Loss","type":"event"},{"anonymous":false,"inputs":[{"indexed":false,"name":"player","type":"address"},{"indexed":false,"name":"invest_v","type":"uint256"},{"indexed":false,"name":"net_invest_v","type":"uint256"}],"name":"newInvest","type":"event"},{"anonymous":false,"inputs":[{"indexed":false,"name":"player","type":"address"},{"indexed":false,"name":"withdraw_v","type":"uint256"}],"name":"withdraw","type":"event"}]
    [info] ABI is now known for the contract at address 18a672e11d637fffadccc99b152f4895da069601
    [success] Total time: 19 s, completed Dec 18, 2016 3:53:23 PM
    > ethCallEphemeral 0x18a672E11D637fffADccc99B152F4895Da069601 <tab>
    betOnColor                           betOnColumn                          betOnDozen                           betOnLowHigh                         betOnNumber                          betOnOddEven                         
    changeDeveloper_only_Dev             changeSettings_only_Dev              checkInvestorBalance                 checkMyBet                           checkProfitLossSinceInvestorChange   disableBetting_only_Dev              
    enableBetting_only_Dev               getGamblesList                       getInvestorList                      getNickname                          getPayroll                           getSettings                          
    getTotalGambles                      invest                               investmentEntryInfos                 manualUpdateBalances_only_Dev        setNickname                          spinTheWheel                         
    splitProfitVIP_only_Dev              transferInvestorAccount              voteOnNewEntryFees_only_VIP          withdrawInvestment   

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

### Repository location and structure

sbt-ethereum's repository directory, located in

- Windows: `%APPDATA%\sbt-ethereum`
- Mac: `~/Library/sbt-ethereum`
- Other Unix: `~/.sbt-ethereum`

__You should be sure to back up this directory, as any account wallets you have created in sbt-ethereum will be stored in the repository's keystore!__

For the moment (this may evolve rapidly), the repository contains sbt-ethereum's `transaction-log`, a `keystore` directory, and a `database` directory.

If you have generated new account wallets, those will be found under `keystore/V3` in a format and under filenames that are interoperable with `geth`.
(You can copy these files into your `geth` keystore if you'd like.)

The database constains metainformation about contracts that you have deployed or interacted with using sbt-ethereum. It is shared by all projects.

### Examining sbt-ethetherum repository data

You can list addresses available in the sbt-ethereum keystore.

    > ethListKeystoreAddresses
    +--------------------------------------------+
    | Keystore Address                           |
    +--------------------------------------------+
    | 0x1f19ca320b1ee0d083b4757d15172521db7b7ea3 |
    | 0x27dd70735fa8cb93c8164b0b43bf733d71638497 |
    | 0x29fbd976b0a0e7ae0d0ed7062fe28d04d522c0df |
    | 0x3fe40fbd919aad2818df01ee4df46c46842ac539 |
    | 0x465e79b940bc2157e4259ff6b2d92f454497f1e4 |
    | 0x47cd9e257d144a2d54d20b1b3695f939f5208b10 |
    | 0x4aa20155e73604737c6701235cd8f6a3d36287d7 |
    | 0x4abc98ee7565fed7d76bdcafbdda244c78e28662 |
    | 0x502f9aa95d45426915bff7b92ef90468b100cc9b |
    | 0xae79b77e31387a3b2409b70c27cebc7220101026 |
    | 0xc9f707e01c3f57c77137a59730ed917ee713bb51 |
    | 0xfbace661786d580ed4373c79689e7e8eb6ba05df |
    +--------------------------------------------+
    [success] Total time: 1 s, completed Dec 18, 2016 3:58:31 PM

You can also list contracts known by the repository.

    > ethListKnownContracts
    +--------------------------------------------+----------------------+--------------------------------------------------------------------+------------------------------+
    | Address                                    | Name                 | Code Hash                                                          | Timestamp                    |
    +--------------------------------------------+----------------------+--------------------------------------------------------------------+------------------------------+
    |                                            | owned                | 0x6d1df5389a869c855e4a39665953bf1e6c368d860de3272cec4338545af34269 |                              |
    | 0xbf4ed7b27f1d666546e30d74d50d173d20bca754 |                      | 0x39f24e90b84ff5c8807616c813dbbe511e0a884d6b292f5b61bcca4faff52578 |                              |
    | 0x18a672e11d637fffadccc99b152f4895da069601 |                      | 0x2dbe2ae2225b560bae4576689b50d6a6d657e12d03c85a236b1f79f77639fce0 |                              |
    | 0x8a656b9c30cd0a48ae55a6759ad6c8c46cc22bb6 | YouToken             | 0x65a2c547d5f3cef497648281156f0dd9f409f9e973a16b4427de93cb87129305 | 2016-10-26T09:09:21.933-0700 |
    | 0x6c289af3fc42541cbf3399d036cfa2595d1fbc68 | Foo                  | 0xba4cf11f18ccc99e123a3f1494bab6af5aad4311f71195884e23b5e6cb6256c5 | 2016-11-07T19:52:23.749-0800 |
    | 0x83a3e8ad22c2578f02d596f478100d52bab7dee7 | Foo                  | 0x0f516a97d10b504d9cdb07c5afab1b3e3d31f4dcf1fe9ee5ad6f9643450dd19f | 2016-12-13T23:29:04.309-0800 |
    | 0xded05e75ae5b06d07aa90479e60939562ed43e45 | TestABI              | 0xf6e9c378758e40150c3f680e0fd51564d80603e3cbb9f24e1d7588dfe7527e2a | 2016-12-16T20:25:13.709-0800 |
    | 0x855a1e374e528ee6239b551280568183ba8906e7 | TestABI              | 0xef2d85c820be1df36a2b26ab4cd48794bb4393ebf23fc2124f9995c7fcdb0839 | 2016-12-16T22:07:57.260-0800 |
    | 0x1d230660f09e2c4e42e0fd9a2144da07e1f0b68c | TestABI              | 0x581029fb1eba2b13d0a8f248ea532feb98b08a97710e64aaca0e349b4b2a279d | 2016-12-16T22:28:03.075-0800 |
    | 0xde895c2c73e9f5332c90e3e7ffa705f751f747b0 | RedeemableToken      | 0xd933ea17cfaab6363826d3c61fcc74cb76fd52b8b700e351254f02f5dc8611f7 | 2016-12-17T21:15:47.483-0800 |
    +--------------------------------------------+----------------------+--------------------------------------------------------------------+------------------------------+
    [success] Total time: 1 s, completed Dec 18, 2016 4:02:26 PM

For complete information known by the repository about a contract, try

    > ethDumpContractInfo <tab>
    <address-hex>          <contract-code-hash>   

Supply a contract's address or the hash of its code (see the table just above), and you'll get a detailed dump of information,
including if available the contract's source code, ABI, etc.

You'll get somehwhat more information if you supply an address rather than a hash, because the repository keeps track of
deployment-specific information like the deployer's address and the time of deployment.


## SBT Keys defined by sbt-ethereum

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

## Appendix I: Using experimental SNAPSHOT releases of sbt-ethereum

Define `plugins.sbt` like this:

    // only necessary while using a SNAPSHOT version of sbt-ethereum
    resolvers += ("snapshots" at "https://oss.sonatype.org/content/repositories/snapshots")

    addSbtPlugin("com.mchange" % "sbt-ethereum" % "0.0.1-SNAPSHOT" changing())


