# Mac/Linux

#### Download a repository

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

#### Initialize and set-up _sbt-ethereum_

Go into that directory and list the directory's contents.
```
$ cd eth-command-line
$ ls
LICENSE		README.md	build.sbt	project		sbtw
```
The file `sbtw` is an _sbt wrapper script_. You can run it, and it will download and install @ref:[sbt](../appendix/a_prerequisites.md#sbt) as needed.

Alternatively, if you already have `sbt` installed on your machine, you can just run that. We'll try the warpper script:
```
$ ./sbtw
```
If this is the first time you are running the script, expect it to take a few minutes and print a lot of stuff.
```
[info] Loading settings for project eth-command-line-build from plugins.sbt ...
[info] Loading project definition from /Users/testuser/eth-command-line/project
[info] Updating ProjectRef(uri("file:/Users/testuser/eth-command-line/project/"), "eth-command-line-build")...
...
[info] 	[SUCCESSFUL ] org.scala-lang.modules#scala-java8-compat_2.12;0.8.0!scala-java8-compat_2.12.jar(bundle) (3192ms)
[info] 	[SUCCESSFUL ] org.apache.logging.log4j#log4j-core;2.8.1!log4j-core.jar (3173ms)
[info] 	[SUCCESSFUL ] com.mchange#mchange-commons-scala_2.12;0.4.7!mchange-commons-scala_2.12.jar (3299ms)
[info] 	[SUCCESSFUL ] com.typesafe.akka#akka-actor_2.12;2.4.18!akka-actor_2.12.jar (3832ms)
[info] 	[SUCCESSFUL ] org.scala-lang#scala-reflect;2.12.7!scala-reflect.jar (3834ms)
[info] 	[SUCCESSFUL ] org.scala-lang#scala-library;2.12.7!scala-library.jar (3988ms)
[info] 	[SUCCESSFUL ] org.scala-lang#scala-compiler;2.12.7!scala-compiler.jar (4495ms)
[info] Done updating.
[warn] There may be incompatibilities among your library dependencies.
[warn] Run 'evicted' to see detailed eviction warnings
[info] Loading settings for project eth-command-line from build.sbt ...
[info] Set current project to eth-command-line (in build file:/Users/testuser/eth-command-line/)
There are no wallets in the sbt-ethereum keystore. Would you like to generate one? [y/n] 
```

_sbt-ethereum_ presents an interactive, text-based user interface. When it's done bootstrapping itself, it notices that it knows of no "wallets",
which are a combination of an Ethereum "address" (analogous to a bank account number), and the secret that unlocks it. (Learn about wallets in greater
detail here **TK**). For now, let's go ahead and make one. _sbt-ethereum_ will ask you to type a passphrase. 
```
There are no wallets in the sbt-ethereum keystore. Would you like to generate one? [y/n] y
[info] Generated keypair for address '0xf2f2f96b6b303ecf1090efd622b915d9083d8df2'
[info] Generating V3 wallet, alogorithm=scrypt, n=262144, r=8, p=1, dklen=32
Enter passphrase for new wallet: ***************
Please retype to confirm: ***************
[info] Wallet generated into sbt-ethereum shoebox: '/Users/testuser/Library/Application Support/sbt-ethereum'. Please backup, via 'ethShoeboxBackup' or manually.
[info] Consider validating the wallet using 'ethKeystoreWalletV3Validate 0xf2f2f96b6b303ecf1090efd622b915d9083d8df2'.
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

After generating your wallet for address `0xf2f2f96b6b303ecf1090efd622b915d9083d8df2`, _sbt-etherum_ asks

```
Would you like the new address '0xf2f2f96b6b303ecf1090efd622b915d9083d8df2' to be the default sender on chain with ID 1? [y/n] 
```

When you wish to interact wih the Ethereum blockchain, _sbt-ethereum_ needs to know an address representing on whose behalf it is interacting.
At Any time, you can set this to any address you like, but it is convenient to have a default address present. Let's answer yes.

```
Would you like the new address '0xf2f2f96b6b303ecf1090efd622b915d9083d8df2' to be the default sender on chain with ID 1? [y/n] y
[info] Alias 'defaultSender' now points to address '0xf2f2f96b6b303ecf1090efd622b915d9083d8df2' (for chain with ID 1).
[info] Refreshing caches.
The current default solidity compiler ['0.4.24'] is not installed. Install? [y/n]
```

_sbt-ethereum_ notices that it does not have the latest supported version of the "solidity compiler" available,
and will ask if you would like to insall it. A solidity compiler is useful if you are developing your own smart contract applications,
rather than just interacting with applications that others have deployed. Regardless, it doesn't hurt to have one. Let's say yes.

```
The current default solidity compiler ['0.4.24'] is not installed. Install? [y/n] y
[info] Installed local solcJ compiler, version 0.4.24 in '/Users/testuser/Library/Application Support/sbt-ethereum/solcJ'.
[info] Testing newly installed compiler... ok.
[info] Updating available solidity compiler set.
[info] sbt-ethereum-0.1.6-SNAPSHOT successfully initialized (built Fri, 16 Nov 2018 23:23:26 -0800)
sbt:eth-command-line>
```

sbt-ethereum installs the compiler, tests it, and brings us to a command prompt. Finally we are ready to go!

__*The set-up steps we've completed were a one-time thing.*__ Next time we enter an
_sbt-ethereum_ repository, we won't have to go through all this set up, and we won't download so much stuff on startup.
We will arrive directly at the command prompt.
