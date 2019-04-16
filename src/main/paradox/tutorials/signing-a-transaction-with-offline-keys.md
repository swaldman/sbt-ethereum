# Signing a Transaction with Offline Keys

From a security perspective, it can be dangerous and nerve-wracking to have significant value
attached to an _ethereum_ account. If it is hacked, all that value could be stolen, irreversibly.
Besides raw cryptocurrency value, _ethereum_ addresses often represent authenticated identities
in smart contracts and applications. If compromised, those identities can do a great deal of mischief.

_sbt-ethereum_ tries to keep your accounts safe by only storing the "secret key" that authenticates
them in a standard, encrypted format (_ethereum_'s "V3" JSON wallet format), decrypting it with your
passphrase only in application memory.

But internet malefactors can be very clever. If your computer gets "rooted", an adversary could steal
the encrypted wallet files and install a "keylogger" to capture the passphrase you type. They could
install a tool that examines application member in search of the decrypted secret key. These kinds of
attacks are fortunately not (yet) very common, but the more valuable your addresses (and so the keys
that control them) become, the more likely they will attract uncommon interest and cleverness.

There is no magic bullet, no foolproof approach to protecting cryptographic keys. But one common, pretty
good, approach that _sbt-ethereum_ supports is _offline signing_. The idea is that you keep your wallet
files on a dedicated computer that, ideally, is _never connected to the internet once your wallet file
is generated_. (Obviously, this has a cost. You have to buy a cheap laptop basically just for this.)

Once you have set up your laptop with _sbt-ethereum_, you disconnect it from the network and generate
your "cold" wallets. On your regular, internet-connected machine, you then use _sbt-ethereum_ to create
@ref:[unsigned transactions](../tasks/eth/transaction/unsigned.md) and save them to a USB thumb drive.

You then plug the thumb drive into your offline computer, and use @ref:[`ethTransactionSign`](../tasks/eth/transaction/index.md#ethtransactionsign)
to sign the transaction and save it back to the thumb drive.

Finally, remove the thumb drive and reinsert it in your main, internet-connected computer. Use @ref:[`ethTransactionForward`](../tasks/eth/transaction/index.md#ethtransactionforward)
to sumbit the signed transaction to the network. Although the signed transaction is now available to the internet-connected computer,
the secret that signed it is not. All a malicious actor could do is submit this one, signed transaction.
There is no way they could craft a new transaction and authenticate it without the key, which remains on the offline computer.

Let's go through this procedure in detail. But first, let's pause for a warning.

@@@ warning

**USB thumb drives, even USB connectors, can be compromised or maliciously crafted.**

A sufficiently diabolical adversary might, for example, compromise your internet-connected computer, and
then use that to install some kind of clever malware you get tricked into executing from your [thumb drive](https://en.wikipedia.org/wiki/USB_flash_drive_security)
which compromises your offline computer and causes it to reconnect to the internet, or exports your secret keys back to the thumb drive
where they can be retrieved by a compromised connected computer.

These kinds of attacks are much harder than "pwning" a directly internet-connected device, but when
sufficient value is at stake, anything is possible. Be careful out there.

@@@

### Setting up an offline device

We find a cheap or old laptop. For the purpose of this tutorial, that'll be a Windows device, but it needn't be.
You'll need to have @ref:[installed git](../appendix/prerequisites.md#git).

If it is on Windows, you'll also need to @ref:[pre-install sbt](../appendix/prerequisites.md#sbt).

As we did in [the "getting started" tutorial](getting-started.md), we'll use `eth-command-line` as our entry to
_sbt-ethereum_.

@@@vars

```

PS C:\Users\swaldman> git clone https://github.com/swaldman/eth-command-line.git --branch $project.version$
Cloning into 'eth-command-line'...
remote: Enumerating objects: 21, done.
remote: Counting objects: 100% (21/21), done.
remote: Compressing objects: 100% (15/15), done.
remote: Total 141 (delta 8), reused 17 (delta 5), pack-reused 120
Receiving objects: 100% (141/141), 25.33 KiB | 785.00 KiB/s, done.
Resolving deltas: 100% (65/65), done.
```

@@@

You'll end up with an `eth-command-line` directory. Go into that, and run `sbt`.

```
PS C:\Users\swaldman> cd .\eth-command-line\
PS C:\Users\swaldman\eth-command-line> sbt
"C:\Users\swaldman\.sbt\preloaded\org.scala-sbt\sbt\"1.0.2"\jars\sbt.jar"
Java HotSpot(TM) 64-Bit Server VM warning: ignoring option MaxPermSize=256m; support was removed in 8.0
[info] Loading settings for project eth-command-line-build from plugins.sbt ...
[info] Loading project definition from C:\Users\swaldman\eth-command-line\project
[info] Updating ProjectRef(uri("file:/C:/Users/swaldman/eth-command-line/project/"), "eth-command-line-build")...
[info] downloading https://repo1.maven.org/maven2/com/mchange/mlog-scala_2.12/0.3.11/mlog-scala_2.12-0.3.11.jar ...
[info]  [SUCCESSFUL ] com.mchange#mlog-scala_2.12;0.3.11!mlog-scala_2.12.jar (828ms)
[info] downloading https://repo1.maven.org/maven2/com/mchange/etherscan-utils_2.12/0.0.1/etherscan-utils_2.12-0.0.1.jar ...
[info] downloading https://repo1.maven.org/maven2/com/mchange/danburkert-continuum_2.12/0.3.99/danburkert-continuum_2.12-0.3.99.jar ...
[info] downloading https://repo1.maven.org/maven2/com/mchange/consuela_2.12/0.0.13/consuela_2.12-0.0.13.jar ...
[info] downloading https://repo1.maven.org/maven2/com/mchange/sbt-ethereum_2.12_1.0/0.1.9/sbt-ethereum-0.1.9.jar ...
[info] downloading https://repo1.maven.org/maven2/com/mchange/literal_2.12/0.0.2/literal_2.12-0.0.2.jar ...
[info] downloading https://repo1.maven.org/maven2/com/mchange/ens-scala_2.12/0.0.10/ens-scala_2.12-0.0.10.jar ...
[info]  [SUCCESSFUL ] com.mchange#literal_2.12;0.0.2!literal_2.12.jar (1281ms)
[info]  [SUCCESSFUL ] com.mchange#danburkert-continuum_2.12;0.3.99!danburkert-continuum_2.12.jar (1625ms)
[info]  [SUCCESSFUL ] com.mchange#etherscan-utils_2.12;0.0.1!etherscan-utils_2.12.jar (1484ms)
[info] downloading https://repo1.maven.org/maven2/com/h2database/h2/1.4.192/h2-1.4.192.jar ...
[info] downloading https://repo1.maven.org/maven2/com/mchange/c3p0/0.9.5.4/c3p0-0.9.5.4.jar ...
[info] downloading https://repo1.maven.org/maven2/com/mchange/texttable_2.12/0.0.2/texttable_2.12-0.0.2.jar ...
[info]  [SUCCESSFUL ] com.mchange#sbt-ethereum;0.1.9!sbt-ethereum.jar (2656ms)
[info]  [SUCCESSFUL ] com.mchange#texttable_2.12;0.0.2!texttable_2.12.jar (437ms)
[info]  [SUCCESSFUL ] com.mchange#consuela_2.12;0.0.13!consuela_2.12.jar (3437ms)
[info]  [SUCCESSFUL ] com.mchange#ens-scala_2.12;0.0.10!ens-scala_2.12.jar (3047ms)
   ...
   ...
   ...
[info] downloading https://repo1.maven.org/maven2/org/scala-sbt/util-scripted_2.12/1.2.4/util-scripted_2.12-1.2.4.jar ...
[info]  [SUCCESSFUL ] org.scala-sbt#template-resolver;0.1!template-resolver.jar (250ms)
[info] downloading https://repo1.maven.org/maven2/com/github/cb372/scalacache-core_2.12/0.20.0/scalacache-core_2.12-0.20.0.jar ...
[info]  [SUCCESSFUL ] org.scala-sbt.ipcsocket#ipcsocket;1.0.0!ipcsocket.jar (343ms)
[info] downloading https://repo1.maven.org/maven2/com/github/ben-manes/caffeine/caffeine/2.5.6/caffeine-2.5.6.jar ...
[info]  [SUCCESSFUL ] org.scala-sbt#util-scripted_2.12;1.2.4!util-scripted_2.12.jar (360ms)
[info] downloading https://repo1.maven.org/maven2/org/scala-sbt/ivy/ivy/2.3.0-sbt-cb9cc189e9f3af519f9f102e6c5d446488ff6832/ivy-2.3.0-sbt-cb9cc189e9f3af519f9f102e6c5d446488ff6832.jar ...
[info]  [SUCCESSFUL ] com.google.protobuf#protobuf-java;3.3.1!protobuf-java.jar(bundle) (1469ms)
[info]  [SUCCESSFUL ] com.github.cb372#scalacache-core_2.12;0.20.0!scalacache-core_2.12.jar (562ms)
[info]  [SUCCESSFUL ] com.github.ben-manes.caffeine#caffeine;2.5.6!caffeine.jar (844ms)
[info]  [SUCCESSFUL ] org.scala-sbt.ivy#ivy;2.3.0-sbt-cb9cc189e9f3af519f9f102e6c5d446488ff6832!ivy.jar (1078ms)
[info]  [SUCCESSFUL ] org.scala-sbt#protocol_2.12;1.2.8!protocol_2.12.jar (1828ms)
[info]  [SUCCESSFUL ] org.scala-lang#scala-compiler;2.12.7!scala-compiler.jar (7251ms)
[info] Done updating.
[warn] There may be incompatibilities among your library dependencies; run 'evicted' to see detailed eviction warnings.
[info] Loading settings for project eth-command-line from build.sbt ...
[info] Set current project to eth-command-line (in build file:/C:/Users/swaldman/eth-command-line/)
There are no wallets in the sbt-ethereum keystore. Would you like to generate one? [y/n]
```

Say `n` to this question! We don't want to generate any wallets while we're still connected to the internet!

But do say `y` to the next question about installing the default Solidity compiler. _sbt-ethereum_ will keep asking you
if you want to install it if you don't, which is really annoying.

```
There are no wallets in the sbt-ethereum keystore. Would you like to generate one? [y/n] n
No wallet created. To create one later, use the command 'ethKeystoreWalletV3Create'.
The current default solidity compiler ['0.4.24'] is not installed. Install? [y/n] y
[info] Installed local solcJ compiler, version 0.4.24 in 'C:\Users\swaldman\AppData\Roaming\sbt-ethereum\solcJ'.
[info] Testing newly installed compiler... ok.
[info] Updating available solidity compiler set.
[info] sbt-ethereum-0.1.9 successfully initialized (built Sat, 13 Apr 2019 00:19:09 -0700)
sbt:eth-command-line>
```

Woohoo!

Now type `<ctrl-d>` to exit from `sbt`.

```
sbt:eth-command-line>
PS C:\Users\swaldman\eth-command-line>
```

Now, we take the big step: Disconnect the laptop from the network, for now and henceforth. Ideally, we will never connect this laptop
again, as once we have wallets, we want to be sure that the machine cannot be compromised.

If your laptop has only a wired connection, you can just unplug it. Unfortunately, it's basically impossible to get a laptop without a WiFi adaptor
nowadays. On a Windows laptop, it's a good idea to go into Windows settings, "Network & Internet", and diasble your wireless network adapter.
On a Mac, you can turn off WiFi, then "Open Network Preferences", highlight WiFi, click "Advanced", and then check "Require administrator authorization to: Turn WiFi on or off".

Once your network is disabled, you can go ahead and run `sbt` again, and this time let it create a wallet for you.

```
PS C:\Users\swaldman\eth-command-line> sbt
"C:\Users\swaldman\.sbt\preloaded\org.scala-sbt\sbt\"1.0.2"\jars\sbt.jar"
Java HotSpot(TM) 64-Bit Server VM warning: ignoring option MaxPermSize=256m; support was removed in 8.0
[info] Loading settings for project eth-command-line-build from plugins.sbt ...
[info] Loading project definition from C:\Users\swaldman\eth-command-line\project
[info] Loading settings for project eth-command-line from build.sbt ...
[info] Set current project to eth-command-line (in build file:/C:/Users/swaldman/eth-command-line/)
There are no wallets in the sbt-ethereum keystore. Would you like to generate one? [y/n] y
[info] Generated keypair for address '0x9d3f825a3151b38c23ebc2e091d13c45fda444fa'
[info] Generating V3 wallet, alogorithm=scrypt, n=262144, r=8, p=1, dklen=32
Enter passphrase for new wallet: ***************
Please retype to confirm: ***************
[info] Wallet generated into sbt-ethereum shoebox: 'C:\Users\swaldman\AppData\Roaming\sbt-ethereum'. Please backup, via 'ethShoeboxBackup' or manually.
[info] Consider validating the wallet using 'ethKeystoreWalletV3Validate 0x9d3f825a3151b38c23ebc2e091d13c45fda444fa'.
Would you like the new address '0x9d3f825a3151b38c23ebc2e091d13c45fda444fa' to be the default sender on chain with ID 1? [y/n] y
[info] Successfully set default sender address for chain with ID 1 to '0x9d3f825a3151b38c23ebc2e091d13c45fda444fa'.
[info] You can use the synthetic alias 'default-sender' to refer to this address.
[info] Refreshing caches.
[info] Updating available solidity compiler set.
[info] sbt-ethereum-0.1.9 successfully initialized (built Sat, 13 Apr 2019 00:19:09 -0700)
sbt:eth-command-line>
```

You'll probably want more than one offline wallet, so make a few more using @ref:[`ethKeystoreWalletV3Create`](../tasks/eth/keystore/index.md#ethkeystorewalletv3create)

```
PS C:\Users\swaldman\eth-command-line> sbt
"C:\Users\swaldman\.sbt\preloaded\org.scala-sbt\sbt\"1.0.2"\jars\sbt.jar"
Java HotSpot(TM) 64-Bit Server VM warning: ignoring option MaxPermSize=256m; support was removed in 8.0
[info] Loading settings for project eth-command-line-build from plugins.sbt ...
[info] Loading project definition from C:\Users\swaldman\eth-command-line\project
[info] Loading settings for project eth-command-line from build.sbt ...
[info] Set current project to eth-command-line (in build file:/C:/Users/swaldman/eth-command-line/)
There are no wallets in the sbt-ethereum keystore. Would you like to generate one? [y/n] y
[info] Generated keypair for address '0x9d3f825a3151b38c23ebc2e091d13c45fda444fa'
[info] Generating V3 wallet, alogorithm=scrypt, n=262144, r=8, p=1, dklen=32
Enter passphrase for new wallet: ***************
Please retype to confirm: ***************
[info] Wallet generated into sbt-ethereum shoebox: 'C:\Users\swaldman\AppData\Roaming\sbt-ethereum'. Please backup, via 'ethShoeboxBackup' or manually.
[info] Consider validating the wallet using 'ethKeystoreWalletV3Validate 0x9d3f825a3151b38c23ebc2e091d13c45fda444fa'.
Would you like the new address '0x9d3f825a3151b38c23ebc2e091d13c45fda444fa' to be the default sender on chain with ID 1? [y/n] y
[info] Successfully set default sender address for chain with ID 1 to '0x9d3f825a3151b38c23ebc2e091d13c45fda444fa'.
[info] You can use the synthetic alias 'default-sender' to refer to this address.
[info] Refreshing caches.
[info] Updating available solidity compiler set.
[info] sbt-ethereum-0.1.9 successfully initialized (built Sat, 13 Apr 2019 00:19:09 -0700)
sbt:eth-command-line> ethKeystoreWalletV3Create
[info] Generated keypair for address '0xf4a1a0093290003b2b1ddb6ea873431d22a6034e'
[info] Generating V3 wallet, alogorithm=scrypt, n=262144, r=8, p=1, dklen=32
Enter passphrase for new wallet: ***************
Please retype to confirm: ***************
[info] Wallet generated into sbt-ethereum shoebox: 'C:\Users\swaldman\AppData\Roaming\sbt-ethereum'. Please backup, via 'ethShoeboxBackup' or manually.
[info] Consider validating the wallet using 'ethKeystoreWalletV3Validate 0xf4a1a0093290003b2b1ddb6ea873431d22a6034e'.
[success] Total time: 11 s, completed Apr 13, 2019 11:40:24 PM
sbt:eth-command-line> ethKeystoreWalletV3Create
[info] Generated keypair for address '0xbba0b43eec475fdb203c56ae69d836c24f008ba2'
[info] Generating V3 wallet, alogorithm=scrypt, n=262144, r=8, p=1, dklen=32
Enter passphrase for new wallet: ***************
Please retype to confirm: ***************
[info] Wallet generated into sbt-ethereum shoebox: 'C:\Users\swaldman\AppData\Roaming\sbt-ethereum'. Please backup, via 'ethShoeboxBackup' or manually.
[info] Consider validating the wallet using 'ethKeystoreWalletV3Validate 0xbba0b43eec475fdb203c56ae69d836c24f008ba2'.
[success] Total time: 9 s, completed Apr 13, 2019 11:40:38 PM
sbt:eth-command-line> ethKeystoreWalletV3Create
[info] Generated keypair for address '0x20c924f780c21e00be3233da55242ed73ae17530'
[info] Generating V3 wallet, alogorithm=scrypt, n=262144, r=8, p=1, dklen=32
Enter passphrase for new wallet: ***************
Please retype to confirm: ***************
[info] Wallet generated into sbt-ethereum shoebox: 'C:\Users\swaldman\AppData\Roaming\sbt-ethereum'. Please backup, via 'ethShoeboxBackup' or manually.
[info] Consider validating the wallet using 'ethKeystoreWalletV3Validate 0x20c924f780c21e00be3233da55242ed73ae17530'.
[success] Total time: 9 s, completed Apr 13, 2019 11:41:04 PM
```

Obviously, take note of all the passcodes you create, and do try validating them with @ref:[`ethKeystoreWalletV3Validate`](../tasks/eth/keystore/index.md#ethkeystorewalletv3validate). Here's one:

```
sbt:eth-command-line> ethKeystoreWalletV3Validate 0x20c924f780c21e00be3233da55242ed73ae17530
[info] V3 wallet(s) found for '0x20c924f780c21e00be3233da55242ed73ae17530'
Enter passphrase or hex private key for address '0x20c924f780c21e00be3233da55242ed73ae17530': ***************
[info] A wallet for address '0x20c924f780c21e00be3233da55242ed73ae17530' is valid and decodable with the credential supplied.
[success] Total time: 7 s, completed Apr 13, 2019 11:46:03 PM
```

Do the rest too. Now let's list them all with @ref:[`ethKeystoreList`](../tasks/eth/keystore/index.md#ethkeystorelist)

```
sbt:eth-command-line> ethKeystoreList
+--------------------------------------------+
| Keystore Addresses                         |
+--------------------------------------------+
| 0x20c924f780c21e00be3233da55242ed73ae17530 |
| 0x9d3f825a3151b38c23ebc2e091d13c45fda444fa | <-- default-sender
| 0xbba0b43eec475fdb203c56ae69d836c24f008ba2 |
| 0xf4a1a0093290003b2b1ddb6ea873431d22a6034e |
+--------------------------------------------+
[success] Total time: 0 s, completed Apr 13, 2019 11:41:12 PM
sbt:eth-command-line>
```

Usually, we'll want to give our offline addresses aliases:

```
sbt:eth-command-line> ethAddressAliasSet offline1 0x9d3f825a3151b38c23ebc2e091d13c45fda444fa
[info] Alias 'offline1' now points to address '0x9d3f825a3151b38c23ebc2e091d13c45fda444fa' (for chain with ID 1).
[info] Refreshing caches.
[success] Total time: 0 s, completed Apr 13, 2019 11:48:11 PM
sbt:eth-command-line> ethAddressAliasSet offline2 0x20c924f780c21e00be3233da55242ed73ae17530
[info] Alias 'offline2' now points to address '0x20c924f780c21e00be3233da55242ed73ae17530' (for chain with ID 1).
[info] Refreshing caches.
sbt:eth-command-line[success] Total time: 0 s, completed Apr 13, 2019 11:48:24 PM
> ethAddressAliasSet offline3 0xbba0b43eec475fdb203c56ae69d836c24f008ba2
[info] Alias 'offline3' now points to address '0xbba0b43eec475fdb203c56ae69d836c24f008ba2' (for chain with ID 1).
[info] Refreshing caches.
[success] Total time: 0 s, completed Apr 13, 2019 11:48:36 PM
sbt:eth-command-line> ethAddressAliasSet offline4 0xf4a1a0093290003b2b1ddb6ea873431d22a6034e
[info] Alias 'offline4' now points to address '0xf4a1a0093290003b2b1ddb6ea873431d22a6034e' (for chain with ID 1).
[info] Refreshing caches.
[success] Total time: 0 s, completed Apr 13, 2019 11:48:47 PM
sbt:eth-command-line> ethKeystoreList
+--------------------------------------------+
| Keystore Addresses                         |
+--------------------------------------------+
| 0x20c924f780c21e00be3233da55242ed73ae17530 | <-- offline2
| 0x9d3f825a3151b38c23ebc2e091d13c45fda444fa | <-- default-sender, offline1
| 0xbba0b43eec475fdb203c56ae69d836c24f008ba2 | <-- offline3
| 0xf4a1a0093290003b2b1ddb6ea873431d22a6034e | <-- offline4
+--------------------------------------------+
[success] Total time: 0 s, completed Apr 13, 2019 11:48:51 PM
```

@@@ warning

**Don't forget to backup your offline computer's shoebox!**

If you lose the wallet files you have created, you'll lose all value associated with your offline addresses.

You can use `ethShoeboxBackup`, and then copy the resulting file to a thumb drive.

(That will produce a zip file. You might want to be neurotic, unzip it, and verfify that there are JSON files for each address in the `keystore` directory.)

_**But never, ever put that thumb drive into an internet connected computer!**_

Remeber, we don't want to internet to ever touch these files.

@@@

Now you'll want to make your new addresses (but not your private waller files!) accessible from your usual, internet-connected
machine. We only communicate from the offline laptop via thumb drive, so plug that in. On my device, it comes up as `F:\`.

Create a new text file on the thumb drive (I'll use `notepad`.) Then copy and paste the output of `ethKeystoreList` into that file.
Properly eject and then remove the thumb drive.

### Prepare your online computer

Plug the thumb drive into your online computer. This is fine: Only the addresses are on it, nothing secret.

Run `sbt` or `sbtw` from within an _sbt-ethereum_ repository. (My online computer is a Mac, so the wrapper script, `sbtw`, works if `sbt` is not preinstalled.)

```
$ cd ./eth-command-line/
$ ./sbtw 
[info] Loading settings for project eth-command-line-build from plugins.sbt ...
[info] Loading project definition from /Users/testuser/eth-command-line/project
[info] Loading settings for project eth-command-line from build.sbt ...
[info] Set current project to eth-command-line (in build file:/Users/testuser/eth-command-line/)
[info] Updating available solidity compiler set.
[info] sbt-ethereum-0.1.7-SNAPSHOT successfully initialized (built Sun, 24 Mar 2019 20:12:21 -0700)
sbt:eth-command-line> 
```

Now, open the text file you created on your thumb drive, and use @ref:[`ethAddressAliasSet`](../tasks/eth/address/alias.md#ethaddressaliasset)
to define parallel aliases to your offline addresses.

```
sbt:eth-command-line> ethAddressAliasSet offline1 0x9d3f825a3151b38c23ebc2e091d13c45fda444fa
[info] Alias 'offline1' now points to address '0x9d3f825a3151b38c23ebc2e091d13c45fda444fa' (for chain with ID 1).
[info] Refreshing caches.
[success] Total time: 1 s, completed Apr 14, 2019 12:06:58 AM
sbt:eth-command-line> ethAddressAliasSet offline2 0x20c924f780c21e00be3233da55242ed73ae17530
[info] Alias 'offline2' now points to address '0x20c924f780c21e00be3233da55242ed73ae17530' (for chain with ID 1).
[info] Refreshing caches.
[success] Total time: 0 s, completed Apr 14, 2019 12:07:10 AM
sbt:eth-command-line> ethAddressAliasSet offline3 0xbba0b43eec475fdb203c56ae69d836c24f008ba2
[info] Alias 'offline3' now points to address '0xbba0b43eec475fdb203c56ae69d836c24f008ba2' (for chain with ID 1).
[info] Refreshing caches.
[success] Total time: 0 s, completed Apr 14, 2019 12:07:25 AM
sbt:eth-command-line> ethAddressAliasSet offline4 0xf4a1a0093290003b2b1ddb6ea873431d22a6034e
[info] Alias 'offline4' now points to address '0xf4a1a0093290003b2b1ddb6ea873431d22a6034e' (for chain with ID 1).
[info] Refreshing caches.
[success] Total time: 0 s, completed Apr 14, 2019 12:07:38 AM
```

### Fund an offline address

Before we can transact from an offline address, we have to fund it. (An _ethereum_ address must have at least enough Ether
to cover the gas if it is going to transact.) So, from our online machine, let's send some Ether to `offline1`.

Note that it's no problem to send funds to an _ethereum_ address we just generated, one the blockchain has never seen before!

```
sbt:eth-command-line> ethTransactionEtherSend offline1 0.01 ether
[info] Unlocking address '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2' (on chain with ID 1, aliases ['default-sender'])
Enter passphrase or hex private key for address '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2': ***************
[info] V3 wallet(s) found for '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2' (aliases ['default-sender'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x9d3f825a3151b38c23ebc2e091d13c45fda444fa (with aliases ['offline1'] on chain with ID 1)
==>   From:  0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2 (with aliases ['default-sender'] on chain with ID 1)
==>   Data:  None
==>   Value: 0.01 Ether
==>
==> The transaction is signed with Chain ID 1 (which correctly matches the current session's 'ethNodeChainId').
==>
==> The nonce of the transaction would be 15.
==>
==> $$$ The transaction you have requested could use up to 25200 units of gas.
==> $$$ You would pay 2 gwei for each unit of gas, for a maximum cost of 0.0000504 ether.
==> $$$ This is worth 0.008188992 USD (according to Coinbase at 12:13 AM).
==> $$$ You would also send 0.01 ether (1.6248 USD), for a maximum total cost of 0.0100504 ether (1.632988992 USD).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0xea2a42d9fc3ef8adeb49efa2b0a4fb51ba6d2951f17cc07c0fc28cecc0aee267' will be submitted. Please wait.
[info] Sending 10000000000000000 wei to address '0x9d3f825a3151b38c23ebc2e091d13c45fda444fa' in transaction '0xea2a42d9fc3ef8adeb49efa2b0a4fb51ba6d2951f17cc07c0fc28cecc0aee267'.
[info] Waiting for the transaction to be mined (will wait up to 5 minutes).
[info] Transaction Receipt:
[info]        Transaction Hash:    0xea2a42d9fc3ef8adeb49efa2b0a4fb51ba6d2951f17cc07c0fc28cecc0aee267
[info]        Transaction Index:   47
[info]        Transaction Status:  SUCCEEDED
[info]        Block Hash:          0x283c3d8e7335fed39c351715cf45d198df7441966297e765b22c320cf05de4e3
[info]        Block Number:        7564660
[info]        From:                0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2
[info]        To:                  0x9d3f825a3151b38c23ebc2e091d13c45fda444fa
[info]        Cumulative Gas Used: 7455410
[info]        Gas Used:            21000
[info]        Contract Address:    None
[info]        Logs:                None
[info]        Events:              None
[info] Ether sent.
[success] Total time: 96 s, completed Apr 14, 2019 12:15:13 AM
```

### Transact from an offline address

Let's send some ether back from the offline address!

We do this in several steps:

First, we create an unsigned transaction from our offline address, and save it to the thumb drive.

Next, we move the thumb drive to the offline laptop and sign the unsigned transaction from there. We save the signed transaction to the thumb drive.

Finally, we move the thumb drive back to the online machine, and forward it to the blockchain.

Let's go!

#### Create and save an unsigned transaction

_**We want to be careful we create the transaction as the appropriate sender.**_ 
(For this transaction, it would not matter very much, but often the effect of a transaction &mdash; whether it will succeed or fail, the gas required, etc &mdash; depend
on transactor identity, so it is important to create the transaction from the correct identity.) We'll use @ref:[`ethAddressSenderOverride`](../tasks/eth/address/sender.md#ethaddresssenderoverride):

```
sbt:eth-command-line> ethAddressSenderOverride offline1
[info] Sender override set to '0x9d3f825a3151b38c23ebc2e091d13c45fda444fa' (on chain with ID 1, aliases ['offline1'])).
[success] Total time: 1 s, completed Apr 14, 2019 12:26:06 AM
```

Next, we use [`ethTransactionUnsignedEtherSend`](../tasks/eth/transaction/unsigned.md#ethtransactionunsignedethersend) to create and save the unsigned transaction.

```
sbt:eth-command-line> ethTransactionUnsignedEtherSend default-sender 0.009 ether
[warn] The nonce for this transaction (0) was automatically computed for '0x9d3f825a3151b38c23ebc2e091d13c45fda444fa' (with aliases ['offline1'] on chain with ID 1).
Full unsigned transaction:
0xe7808477359400826270941144f4f7aad0c463c667e0f8d73fc13f1e7e86a2871ff973cafa800080

[warn] The transaction will likely be invalid if signed on behalf of any other address, or if some of transaction is submitted by this address prior to this transaction.
Enter the path to a (not-yet-existing) file into which to write the binary unsigned transaction, or [return] not to save: /Volumes/XFER/offline1-send-unsigned.bin
[info] Unsigned transaction saved as '/Volumes/XFER/offline1-send-unsigned.bin'.
[success] Total time: 21 s, completed Apr 14, 2019 12:29:25 AM
```

#### Sign the unsigned transaction from the offline computer

Carefully eject and remove the thumb drive, and insert it into the offline computer.

On the offline computer, make sure you are operating as the correct identity (`offline1`), and use @ref:[`ethTransactionSign`](../tasks/eth/transaction/index.md#ethtransactionsign)
to sign the transaction.

```
PS C:\Users\swaldman\eth-command-line> sbt
"C:\Users\swaldman\.sbt\preloaded\org.scala-sbt\sbt\"1.0.2"\jars\sbt.jar"
Java HotSpot(TM) 64-Bit Server VM warning: ignoring option MaxPermSize=256m; support was removed in 8.0
[info] Loading settings for project eth-command-line-build from plugins.sbt ...
[info] Loading project definition from C:\Users\swaldman\eth-command-line\project
[info] Loading settings for project eth-command-line from build.sbt ...
[info] Set current project to eth-command-line (in build file:/C:/Users/swaldman/eth-command-line/)
[info] Updating available solidity compiler set.
[info] sbt-ethereum-0.1.9 successfully initialized (built Sat, 13 Apr 2019 00:19:09 -0700)
sbt:eth-command-line> ethAddressSenderPrint
[info] The current effective sender address is '0x9d3f825a3151b38c23ebc2e091d13c45fda444fa' (with aliases ['default-sender','offline1'] on chain with ID 1).
[info]  + This value is the default sender address defined in the sbt-ethereum shoebox for chain with ID 1.
[info]  + It has not been overridden with a session override or by an 'ethcfgAddressSender' setting in the project build or the '.sbt' folder.
[success] Total time: 0 s, completed Apr 14, 2019 12:35:23 AM
sbt:eth-command-line> ethTransactionSign
Enter the path to a file containing a binary unsigned transaction, or just [return] to enter transaction data manually: F:\offline1-send-unsigned.bin
Do you wish to sign for the sender associated with the current session, '0x9d3f825a3151b38c23ebc2e091d13c45fda444fa' (with aliases ['default-sender','offline1'] on chain with ID 1)? [y/n] y
The Chain ID associated with your current session is 1. Would you like to sign with this Chain ID? [y/n] y
[info] Unlocking address '0x9d3f825a3151b38c23ebc2e091d13c45fda444fa' (on chain with ID 1, aliases ['default-sender','offline1'])
Enter passphrase or hex private key for address '0x9d3f825a3151b38c23ebc2e091d13c45fda444fa': ***************
[info] V3 wallet(s) found for '0x9d3f825a3151b38c23ebc2e091d13c45fda444fa' (aliases ['default-sender','offline1'])

==> T R A N S A C T I O N   S I G N A T U R E   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2 (on chain with ID 1)
==>   From:  0x9d3f825a3151b38c23ebc2e091d13c45fda444fa (with aliases ['default-sender','offline1'] on chain with ID 1)
==>   Data:  None
==>   Value: 0.009 Ether
==>
==> The nonce of the transaction would be 0.
==>
==> $$$ The transaction you have requested could use up to 25200 units of gas.
==> $$$ You would pay 2 gwei for each unit of gas, for a maximum cost of 0.0000504 ether.
==> $$$ (No USD value could be determined for ETH on chain with ID 1 from Coinbase).
==> $$$ You would also send 0.009 etherfor a maximum total cost of 0.0090504 ether.

Would you like to sign this transaction? [y/n] y

Full signed transaction:
0xf86a808477359400826270941144f4f7aad0c463c667e0f8d73fc13f1e7e86a2871ff973cafa80008025a0e18eca9d6e1fb11d8f0df69a4be3392cde399d012c84c00aaee4c9f0c9ebf26ea01772f00b4579b0ec16c73a60150a4a588253f38a19e355b48b1e42a3a984e5f1

Enter the path to a (not-yet-existing) file in which to write the binary signed transaction, or [return] to skip: F:\offline1-send-signed.bin
[info] Signed transaction saved as 'F:\offline1-send-signed.bin'.
[success] Total time: 85 s, completed Apr 14, 2019 12:37:00 AM
```

#### Forward the signed transaction to the blockchain

Carefully eject and remove the thumb drive, and insert it into the online computer.

Now, we can forward the signed transaction to the blockchain using @ref:[`ethTransactionForward`](../tasks/eth/transaction/index.md#ethtransactionforward).

```
sbt:eth-command-line> ethTransactionForward
Enter the path to a file containing a binary signed transaction, or just [return] to paste or type hex data manually: /Volumes/XFER/offline1-send-signed.bin

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2 (with aliases ['default-sender'] on chain with ID 1)
==>   From:  0x9d3f825a3151b38c23ebc2e091d13c45fda444fa (with aliases ['offline1'] on chain with ID 1)
==>   Data:  None
==>   Value: 0.009 Ether
==>
==> The transaction is signed with Chain ID 1 (which correctly matches the current session's 'ethNodeChainId').
==>
==> The nonce of the transaction would be 0.
==>
==> $$$ The transaction you have requested could use up to 25200 units of gas.
==> $$$ You would pay 2 gwei for each unit of gas, for a maximum cost of 0.0000504 ether.
==> $$$ This is worth 0.0082159560 USD (according to Coinbase at 12:41 AM).
==> $$$ You would also send 0.009 ether (1.467135 USD), for a maximum total cost of 0.0090504 ether (1.4753509560 USD).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0x809b4f777311c4de61fc590aafa05e8d1b0c7f8105b425474498e53f3a30c0f3' will be submitted. Please wait.
[info] Sending signed transaction with transaction hash '0x809b4f777311c4de61fc590aafa05e8d1b0c7f8105b425474498e53f3a30c0f3'.
[info] Transaction Receipt:
[info]        Transaction Hash:    0x809b4f777311c4de61fc590aafa05e8d1b0c7f8105b425474498e53f3a30c0f3
[info]        Transaction Index:   150
[info]        Transaction Status:  SUCCEEDED
[info]        Block Hash:          0x4b58056bf70d151423f42e9493035eb20809bd77e07ad5420adcf71cff099eed
[info]        Block Number:        7564773
[info]        From:                0x9d3f825a3151b38c23ebc2e091d13c45fda444fa
[info]        To:                  0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2
[info]        Cumulative Gas Used: 7892001
[info]        Gas Used:            21000
[info]        Contract Address:    None
[info]        Logs:                None
[info]        Events:              None
[info] Transaction mined.
[success] Total time: 85 s, completed Apr 14, 2019 12:41:40 AM
```

And we are done! We have successfully sent a transaction without exposing the transactor's private key to the network.

@@@ note

**Consider making old-school paper cold wallets**

---

**Variation #1: Raw Private Keys**

For each of your offline accounts, you can use `ethKeystorePrivateKeyReveal` to display the raw private key associated with your addresses to the console.

_**This is a very sensitive operation that should only be done in private**_

Then print (over a physical USB connection, not over a network!) the transcript of your session, and place that in a very, very secure location.

*(Be careful that you don't accidentally leave your unencrypted private keys lying around in a file. This could happen if you have set your
terminal or shell to save transcripts of your session, or if you have copied-and-pasted into an editor or word processor that stores autosave files!)*

_**If you don't have a very, very secure location, then DON'T make paper wallets with your raw private keys**_ Use Variation #2.

---

**Variation #2: JSON V3 Wallet Files**

An alternative is to use `ethKeystoreWalletV3Print` to create paper copies of the JSON fies that are encrypted private keys.

The downside of this is it will be much less convenient to recover access to your address from printed JSON objects.

The upside is, even if an unauthorized party finds the printed JSON file, they will be unable to access your accounts withut the associated passcodes.

_**Obviously, do NOT keep the passcodes in the same place as the printed JSON files**_ If you do that, anyone who accesses that place has all they need to reconstruct your private keys.

@@@

