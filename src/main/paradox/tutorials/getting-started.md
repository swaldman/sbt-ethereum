# Getting Started

### Download a repository

_sbt-ethereum_ runs in the context of a _repository_, a folder which potentially represents source code for a particular project or application.
But in order to just interact with the _ethereum_ blockchain, or to manage or create wallets and addresses, no partcular project or
application is required.

For this purpose, a simple _sbt-ethereum_ repository called [eth-command-line](https://github.com/swaldman/eth-command-line) is available.
Let's start with that.

Make sure that you have @ref:[git](../appendix/a_prerequisites.md#git) installed and available on your @ref:[command line](../appendix/a_prerequisites.md#command-line).
Then try the following command:

@@@vars

```
$ git clone https://github.com/swaldman/eth-command-line.git --branch $project.version$
```

@@@

You should see something like
```
Cloning into 'eth-command-line'...
remote: Enumerating objects: 7, done.
remote: Counting objects: 100% (7/7), done.
remote: Compressing objects: 100% (5/5), done.
remote: Total 108 (delta 1), reused 6 (delta 1), pack-reused 101
Receiving objects: 100% (108/108), 22.62 KiB | 609.00 KiB/s, done.
Resolving deltas: 100% (50/50), done.
```
When it is done, you should have a directory called `eth-command-line`.

### Initialize and set-up _sbt-ethereum_

Go into that directory and list the directory's contents.
```
$ cd eth-command-line
$ ls
LICENSE		README.md	build.sbt	project		sbtw
```
The file `sbtw` is an _sbt wrapper script_. You can run it, and it will download and install @ref:[sbt](../appendix/a_prerequisites.md#sbt) as needed.

Alternatively, if you already have `sbt` installed on your machine, you can just run that. We'll try the wrapper script:
```
$ ./sbtw
```
If this is the first time you are running the script, expect it to take a few minutes and print a lot of stuff.
```
Downloading sbt launcher for 1.2.7:
  From  http://repo.scala-sbt.org/scalasbt/maven-releases/org/scala-sbt/sbt-launch/1.2.7/sbt-launch.jar
    To  /Users/testuser/.sbt/launchers/1.2.7/sbt-launch.jar
Getting org.scala-sbt sbt 1.2.7  (this may take some time)...
downloading https://repo1.maven.org/maven2/org/scala-sbt/sbt/1.2.7/sbt-1.2.7.jar ...
	[SUCCESSFUL ] org.scala-sbt#sbt;1.2.7!sbt.jar (141ms)
downloading https://repo1.maven.org/maven2/org/scala-lang/scala-library/2.12.7/scala-library-2.12.7.jar ...
	[SUCCESSFUL ] org.scala-lang#scala-library;2.12.7!scala-library.jar (1353ms)
	  ...
	  ...
[info] 	[SUCCESSFUL ] org.slf4j#slf4j-api;1.7.20!slf4j-api.jar (111ms)
[info] 	[SUCCESSFUL ] ch.qos.logback#logback-core;1.1.7!logback-core.jar (206ms)
[info] downloading https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-core/2.11.1/log4j-core-2.11.1-tests.jar ...
[info] 	[SUCCESSFUL ] org.apache.logging.log4j#log4j-core;2.11.1!log4j-core.jar(test-jar) (337ms)
[info] 	[SUCCESSFUL ] com.mchange#sbt-ethereum;0.1.7-SNAPSHOT!sbt-ethereum.jar (5574ms)
[info] 	[SUCCESSFUL ] com.mchange#consuela_2.12;0.0.11-SNAPSHOT!consuela_2.12.jar (10602ms)
[info] Done updating.
[warn] There may be incompatibilities among your library dependencies; run 'evicted' to see detailed eviction warnings.
[info] Loading settings for project eth-command-line from build.sbt ...
[info] Set current project to eth-command-line (in build file:/Users/testuser/eth-command-line/)
There are no wallets in the sbt-ethereum keystore. Would you like to generate one? [y/n] y
```
_sbt-ethereum_ presents an interactive, text-based user interface. When it's done bootstrapping itself, it notices that it knows of no "wallets",
which are a combination of an Ethereum "address" (analogous to a bank account number), and the secret that unlocks it. (Learn about wallets in greater
detail here **TK**). For now, let's go ahead and make one. _sbt-ethereum_ will ask you to type a passphrase. 
```
There are no wallets in the sbt-ethereum keystore. Would you like to generate one? [y/n] y
[info] Generated keypair for address '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2'
[info] Generating V3 wallet, alogorithm=scrypt, n=262144, r=8, p=1, dklen=32
Enter passphrase for new wallet: ***************
Please retype to confirm: ***************
[info] Wallet generated into sbt-ethereum shoebox: '/Users/testuser/Library/Application Support/sbt-ethereum'. Please backup, via 'ethShoeboxBackup' or manually.
[info] Consider validating the wallet using 'ethKeystoreWalletV3Validate 0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2'.
```
@@@ warning

Your "wallet" is protected both by the passphrase that you choose, and by files that _sbt-ethereum_ stores in its shoebox directory. **You need both of these pieces
to access your new _ethereum_ account, so if you lose the wallet files or forget your passphrase, you will lose all value and privileges associated with the wallet's
address.** So back them up!

You can back the shoebox directory shown above manually, or use the command `ethShoeboxBackup`, which will save important shoebox data in a zip file
from which _sbt-ethereum_ can automatically restore.

@@@

@@@ warning

_Anyone_ who discovers the passphrase you have chosen _and_ who has access to the wallet file in your shoebox can take control of your wallet's address and all
value and privileges associated with it.

**Keep the wallet files in your shoebox database (including any backups!) _and_ the passphrase you have chosen to unlock those files secret and secure!**

@@@

After generating a wallet for address `0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2` &mdash; *your address will be different!* &mdash; _sbt-etherum_ asks

```
Would you like the new address '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2' to be the default sender on chain with ID 1? [y/n] y
```

When you wish to interact with the Ethereum blockchain, _sbt-ethereum_ needs to know an address representing on whose behalf it is interacting.
At Any time, you can set this to any address you like, but it is convenient to have a default address present. Let's answer yes.

```
Would you like the new address '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2' to be the default sender on chain with ID 1? [y/n] y
[info] Successfully set default sender address for chain with ID 1 to '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2'.
[info] You can use the synthetic alias 'default-sender' to refer to this address.
[info] Refreshing caches.
The current default solidity compiler ['0.4.24'] is not installed. Install? [y/n] y
```

_sbt-ethereum_ notices that it does not have the latest supported version of the "solidity compiler" available,
and will ask if you would like to insall it. A solidity compiler is useful if you are developing your own smart contract applications,
rather than just interacting with applications that others have deployed. Regardless, it doesn't hurt to have one. Let's say yes.

```
[info] Installed local solcJ compiler, version 0.4.24 in '/Users/testuser/Library/Application Support/sbt-ethereum/solcJ'.
[info] Testing newly installed compiler... ok.
[info] Updating available solidity compiler set.
[info] sbt-ethereum-0.1.7-SNAPSHOT successfully initialized (built Sun, 9 Dec 2018 22:12:18 -0800)
sbt:eth-command-line> 
```

sbt-ethereum installs the compiler, tests it, and brings us to a command prompt. Finally we are ready to go!

@@@ note

__*The set-up steps we've completed were a one-time thing.*__

Next time we enter an
_sbt-ethereum_ repository, we won't have to go through all this set up, and we won't download so much stuff on startup.
We will arrive pretty directly at the command prompt.

@@@

### Run your first command

When we generated our new address, you may have noticed the message
```
[info] Consider validating the wallet using 'ethKeystoreWalletV3Validate 0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2'.
```
Let's try that. You could just copy and paste the (very long!) command, but please don't. _sbt-ethereum_ by design
relies up _tab completion_ to make typing long but descriptive commands easy. Writing out a "tutorial" in tab completion
will make it seem long and cumbersome, but it's not. It becomes muscle memory, second nature, very quickle.
**(TL; DR: When in doubt, type `<tab><tab>`.)**

Start by typing `eth` and then hitting the tab key. (We'll write that from now on as `eth<tab>`, where the angle-braced `<tab>`
does not mean you should type out `<`, `t`, etc. &mdash; _don't do that!_ &mdash; but instead means "hit the tab key".)

When you type `eth<tab>` you should see a rather useless and intimidatingly long list, something like...

```
sbt:eth-command-line> eth
Display all 103 possibilities? (y or n) 
eth-command-line/                         ethAddressAliasCheck                      ethAddressAliasDrop                       ethAddressAliasList                       
ethAddressAliasSet                        ethAddressBalance                         ethAddressSenderDefaultDrop               ethAddressSenderDefaultPrint              
ethAddressSenderDefaultSet                ethAddressSenderOverrideDrop              ethAddressSenderOverridePrint             ethAddressSenderOverrideSet               
ethAddressSenderPrint                     ethContractAbiAliasDrop                   ethContractAbiAliasList                   ethContractAbiAliasSet                    
ethContractAbiDecode                      ethContractAbiEncode                      ethContractAbiForget                      ethContractAbiImport                      
ethContractAbiList                        ethContractAbiMatch                       ethContractAbiOverrideAdd                 ethContractAbiOverrideClear               
ethContractAbiOverrideList                ethContractAbiOverridePrint               ethContractAbiOverrideRemove              ethContractAbiPrint                       
ethContractAbiPrintCompact                ethContractAbiPrintPretty                 ethContractCompilationCull                ethContractCompilationInspect             
ethContractCompilationList                ethDebugGanacheRestart                    ethDebugGanacheStart                      ethDebugGanacheStop                       
ethDebugGanacheTest                       ethKeystoreList                           ethKeystorePrivateKeyReveal               ethKeystoreWalletV3Create                 
ethKeystoreWalletV3FromJsonImport         ethKeystoreWalletV3FromPrivateKeyImport   ethKeystoreWalletV3Print                  ethKeystoreWalletV3Validate               
ethLanguageSolidityCompilerInstall        ethLanguageSolidityCompilerPrint          ethLanguageSolidityCompilerSelect         ethNodeBlockNumberPrint                   
ethNodeUrlDefaultDrop                     ethNodeUrlDefaultPrint                    ethNodeUrlDefaultSet                      ethNodeUrlOverrideDrop                    
ethNodeUrlOverridePrint                   ethNodeUrlOverrideSet                     ethNodeUrlPrint                           ethShoeboxBackup                          
ethShoeboxDatabaseDumpCreate              ethShoeboxDatabaseDumpRestore             ethShoeboxRestore                         ethTransactionDeploy                      
ethTransactionGasLimitOverrideDrop        ethTransactionGasLimitOverridePrint       ethTransactionGasLimitOverrideSet         ethTransactionGasPriceOverrideDrop        
ethTransactionGasPriceOverridePrint       ethTransactionGasPriceOverrideSet         ethTransactionInvoke                      ethTransactionLookup                      
ethTransactionNonceOverrideDrop           ethTransactionNonceOverridePrint          ethTransactionNonceOverrideSet            ethTransactionPing                        
ethTransactionRaw                         ethTransactionSend                        ethTransactionView                        ethcfgAddressSender                       
ethcfgAutoDeployContracts                 ethcfgBaseCurrencyCode                    ethcfgChainId                             ethcfgEntropySource                       
ethcfgGasLimitCap                         ethcfgGasLimitFloor                       ethcfgGasLimitMarkup                      ethcfgGasPriceCap                         
ethcfgGasPriceFloor                       ethcfgGasPriceMarkup                      ethcfgIncludeLocations                    ethcfgKeystoreAutoImportLocationsV3       
ethcfgKeystoreAutoRelockSeconds           ethcfgNetcompileUrl                       ethcfgNodeUrl                             ethcfgScalaStubsPackage                   
ethcfgSolidityCompilerOptimize            ethcfgSolidityCompilerOptimizerRuns       ethcfgSolidityDestination                 ethcfgSoliditySource                      
ethcfgTargetDir                           ethcfgTransactionReceiptPollPeriod        ethcfgTransactionReceiptTimeout           ethcfgUseReplayAttackProtection           
etherscanApiKeyDrop                       etherscanApiKeyImport                     etherscanApiKeyReveal                     
```
Wow. That's a lot. That's a list of (almost) all of the ethereum-related commands
available to you. But it's too much information to be useful.

The command that we wanted to try was `ethKeystoreWalletV3Validate`. But instead of typing that out, type `ethK<tab>`.
You should see that the tab now automatically completes to
```
sbt:eth-command-line> ethKeystore
```
How did that work? In the giant list of commands above, you'll notice that the only commands that begin with capital `K`
are those that begin with `ethKeystore`. So _sbt_ could "fill in the blanks" for you, there was no other choice.

Now type `<tab>` again. You should see something like this:
```
sbt:eth-command-line> ethKeystore
ethKeystoreList                           ethKeystorePrivateKeyReveal               ethKeystoreWalletV3Create                 
ethKeystoreWalletV3FromJsonImport         ethKeystoreWalletV3FromPrivateKeyImport   ethKeystoreWalletV3Print                  
ethKeystoreWalletV3Validate               
sbt:eth-command-line> ethKeystore
```
_sbt_ has given you a much more manageable list of possible commands, beginning with `ethKeystore`. The one we want is
`ethKeystoreWalletV3Validate`, so we could type `WalletV3Validate` from here. _**Don't do that!**_ Instead, just type `W<tab><tab>`,
The first time you hit `<tab>`, _sbt_ will do its autocomplete thing, which will yield `ethKeystoreWalletV3`. The second `<tab>`
asks _sbt_ to show remaining completions.
```
sbt:eth-command-line> ethKeystoreWalletV3
ethKeystoreWalletV3Create                 ethKeystoreWalletV3FromJsonImport         ethKeystoreWalletV3FromPrivateKeyImport   
ethKeystoreWalletV3Print                  ethKeystoreWalletV3Validate               
sbt:eth-command-line> ethKeystoreWalletV3
```
We're getting close! Now _sbt_ is showing us just the "WalletV3" related commands. We want `Validate` so we just type `V<tab>`
to finally complete the command. Hooray!
```
sbt:eth-command-line> ethKeystoreWalletV3Validate
```
(If you hit tab again, when the command is complete, you'll get a list of completions that begins with `/   ::`. This means
that you have entered a complete command.)

Once a command is complete, it will often require "arguments" &mdash; more information about how to run the command. In our case,
`ethKeystoreWalletV3Validate` requires the address whose wallet it is supposed to "validate". We could copy and paste the long hex
address we generated upon intiaization, but let's see if we can avoid that.

Once a command is complete, try pressing`<space><tab>` (where `<space>` means "press the space bar").
```
sbt:eth-command-line> ethKeystoreWalletV3Validate 
<address-hex>    <ens-name>.eth   default-sender   
sbt:eth-command-line> ethKeystoreWalletV3Validate
```
Just as in this tutorial, items in angle braces are description of what you might type, rather than things that you literally should type.
The first two items are telling you that you could type out (or really paste) the full, long hex address, you could provide an ens name
that is bound to an address. Or, you could literally write `default-sender`, which you will note is _not_ in angle braces.

But don't type `default-sender`. Stay foolish, be lazy. Just type `d<tab>` and let `sbt-ethereum` do the typing for you!
```
sbt:eth-command-line> ethKeystoreWalletV3Validate default-sender
```
Now hit `<return>`, and follow the instructions. Let's hope you remember the passphrase you provided for your `defaultSender` address!
```
sbt:eth-command-line> ethKeystoreWalletV3Validate default-sender
[info] V3 wallet(s) found for '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2' (aliases ['default-sender'])
Enter passphrase or hex private key for address '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2': ***************
[info] A wallet for address '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2' is valid and decodable with the credential supplied.
[success] Total time: 7 s, completed Dec 9, 2018 11:22:46 PM
```
Hooray! We've successfully run an _sbt-ethereum_ command, and (hopefully!) successfully validated our wallet.

### Run your second command

With far less fanfare, let's try running the command `ethKeystoreList`. Again, we don't want to actually type it out. It's looong! We're lazy!
Just type `ethK<tab>L<tab>`. If you type tab again, you'll see this one doesn't take any arguments.
(The available completions are just `/    ::`, which refer to ways of qualifying _sbt_ tasks that are not likely to be useful to you.) So, just hit `<return>`!
```
sbt:eth-command-line> ethKeystoreList
+--------------------------------------------+
| Keystore Addresses                         |
+--------------------------------------------+
| 0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2 | <-- default-sender
+--------------------------------------------+
[success] Total time: 0 s, completed Dec 9, 2018 11:23:25 PM
```
_sbt-ethereum_ knows about precisely one address, to which has been linked the special _address alias_ `default-sender`.
You can define as many address aliases as you want for any ethereum address. Type `ethA<tab>A<tab><tab>` to see the relevant commands,
which all begin with `ethAddressAlias`.

### Back up your "shoebox"

Your wallets and address aliases, as well as other information such as your transaction history, smart contract compilations,
and ABIs of contracts you interact with are stored in the sbt-ethereum "shoebox", which you will want to get into the habit of
backing up. So, using our usual tab-completion _skillz_, let's run the command `ethShoeboxBackup` (which takes no arguments).

The command is interactive.
It will ask us for a directory in which we like to store backups (which will be retained &mdash; in the shoebox! &mdash; for
optional future reuse). Provide a directory appropriate to your system. You may want to backup to a thumbdrive or external disk,
so if something happens to your computer's hard disk, you have a copy elsewhere.
```
sbt:eth-command-line> ethShoeboxBackup
[warn] No default shoebox backup directory has been selected. Please select a new shoebox backup directory.
Enter the path of the directory into which you wish to create a backup: /Volumes/thumbdrive/sbt-ethereum-backups
Use directory '/Volumes/thumbdrive/sbt-ethereum-backups' as the default sbt-ethereum shoebox backup directory? [y/n] y
[info] Creating SQL dump of sbt-ethereum shoebox database...
[info] Successfully created SQL dump of the sbt-ethereum shoebox database: '/Users/testuser/Library/Application Support/sbt-ethereum/database/h2-dumps/sbt-ethereum-v7-20181204T12h55m24s459msPST.sql'
[info] Backing up sbt-ethereum shoebox. Reinstallable compilers will be excluded.
[info] sbt-ethereum shoebox successfully backed up to '/Volumes/thumbdrive/sbt-ethereum-backups/sbt-ethereum-shoebox-backup-20181204T12h55m24s475msPST.zip'.
```
Voila! We have backed up our shoebox. Verify that there is a backup file in the directory you have selected.
We can restore our shoebox, if something bad happens, from the generated file using `ethShoeboxRestore`. (Try it if you like! It's easy!)

@@@ warning

**If you lose the wallet files in your shoebox, or the passcodes that unlock them, you will lose any money or value in them, irrecoverably and forever!**

_sbt-ethereum_ stores wallet files in its internal shoebox directory. You can back up that directory by hand, or use the command `ethShoeboxBackup`.

_sbt-ethereum_ **does not store the passcodes** that unlock these wallets. You need to store these yourself, preferably somewhere offline, and be sure not to lose them.

If you lose _either one of_ a wallet file or its passcode, **all of the value stored in that wallet's associated address will likely be lost forever**. Ouch.

@@@

### Connect to a node

So far, neither of the commands that we've tried have needed to communicate with a node on the Ethereum network. Let's try that now. __*(Don't type it out! Use the tab completion, Luke.)*__
```
sbt:eth-command-line> ethNodeBlockNumberPrint
```
It might work! If it does work, you should see something like this:
```
sbt:eth-command-line> ethNodeBlockNumberPrint
[info] The current blocknumber is 6859638, according to node at 'https://ethnode.somewhere.com/'.
[success] Total time: 0 s, completed Dec 9, 2018 11:44:09 PM
```
But it might not work! In which case, you will see something like this:
```
sbt:eth-command-line> ethNodeBlockNumberPrint
[error] java.net.UnknownHostException: ethnode.somewhere.com: nodename nor servname provided, or not known
[error] 	at java.net.Inet6AddressImpl.lookupAllHostAddr(Native Method)
[error] 	at java.net.InetAddress$2.lookupAllHostAddr(InetAddress.java:928)
[error] 	at java.net.InetAddress.getAddressesFromNameService(InetAddress.java:1323)
[error] 	at java.net.InetAddress.getAllByName0(InetAddress.java:1276)
[error] 	at java.net.InetAddress.getAllByName(InetAddress.java:1192)
[error] 	at java.net.InetAddress.getAllByName(InetAddress.java:1126)
[error] 	at org.eclipse.jetty.util.SocketAddressResolver$Async.lambda$resolve$1(SocketAddressResolver.java:167)
[error] 	at org.eclipse.jetty.util.thread.QueuedThreadPool.runJob(QueuedThreadPool.java:762)
[error] 	at org.eclipse.jetty.util.thread.QueuedThreadPool$2.run(QueuedThreadPool.java:680)
[error] 	at java.lang.Thread.run(Thread.java:748)
[error] (Compile / ethNodeBlockNumberPrint) java.net.UnknownHostException: ethnode.somewhere.com: nodename nor servname provided, or not known
[error] Total time: 0 s, completed Dec 9, 2018 11:40:17 PM
```
Oops! Don't panic!

#### Setting up a URL

_**You only have to do this once!**_

To communicate with the Ethereum network, you need to have access to a _node_, a piece of software somewhere
on the internet that participates in the network and serves as your gateway. _sbt-ethereum_ may (or may not!)
have a built-in default URL, and it might (or might not!) even work. But you really, really shouldn't rely upon that.
You need access to a reliable node.

An easy way to get that access is via [infura.io](https://infura.io.). Create a login, and then a "project". (It doesn't
matter what the project is, call it "stinky-socks" for all I care. _Infura_ will only give you a URL these days if you tell them you
have a project.)

Inside your project you'll see an "Endpoint" for `MAINNET`. Copy it. (Click on the little "clipboard icon".) You'll
have copied a URL, something like `https://mainnet.infura.io/v3/353e8352f0782b827d72757dab9cc946`, except with different
gibberish at the end. That (your version, not mine!) can be your personal URL into the Ethereum network.

To set-up _sbt-ethereum_ to use URL, just run the following command _(but use your own gosh-danged URL not mine!)_
```
sbt:eth-command-line> ethNodeUrlDefaultSet https://mainnet.infura.io/v3/353e8352f0782b827d72757dab9cc946
[info] Successfully set default node json-rpc URL for chain with ID 1 to https://mainnet.infura.io/v3/353e8352f0782b827d72757dab9cc946
```

@@@ note

**The value you set with `ethNodeUrlDefaultSet` is persistent.**

It will remain in your "shoebox" until you replace it with a different value,
or you explicitly remove it with `ethNodeUrlDefaultDrop`. You can always check your current default node URL with `ethNodeUrlDefaultPrint`.
Try it!

@@@

Now you should be able to very reliably connect to a node:
```
sbt:eth-command-line> ethNodeBlockNumberPrint
[info] The current blocknumber is 6859824, according to node at 'https://mainnet.infura.io/v3/353e8352f0782b827d72757dab9cc946'.
[success] Total time: 0 s, completed Dec 10, 2018 12:31:24 AM
```

@@@ note

**The "block number" that we have printed is useful information. It's like the ticking clock of the Ethereum network, and
you always want it to be current.**

You can check websites like [etherscan](https://etherscan.io) or [ethstats](https://ethstats.net) to make sure that your blocknumber
is close to theirs. (The block number changes every fifteen seconds or so, don't worry if your the numbers are off by one or two.)

If the node you are working with &mdash; the one behind your URL &mdash; falls behind, it can lead to strange misbehavior. In particular,
you will be looking "back in time", at old data. **This can lead to heart attacks**, when, for example, you transfer money to an
address but it seems not to show up, because you don't realize you are looking at yesterday's numbers.

@@@

Did you get a block number? Hooray! You are successfully connecting to a node.

