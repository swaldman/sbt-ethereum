# Using a Smart Contract (I)

_Ethereum_ applications are defined as one or more _smart contract_, each of which live at an
address on the _Ethereum_ blockchain. To interact with one, the first thing we need is it's address.

We are going to
work with a brilliant, very exciting, _fortune cookie application_. Finally you will learn the truth
about your future. And you don't have to just trust me on that. The blockchain is trustless, right?

Anyway, the address of out _fortune_ application is `0x82ea8ab1e836272322f376a5f71d5a34a71688f1`.
This is not my address or your address. It is the public address of the application itself.

### Assigning an alias to an Ethereum address

As you can see, an Ethereum address is a long stupid string of "hex" digits that would be very annoying
and error prone to type. _So try never to type one!_ When absolutely you need to, copy and paste the address, and then double
check that you didn't drop any digits. But much better yet, _sbt-ethereum_ permits you to define
easy to use, tab-completable names as "address aliases". So, let's do it.

The command we'll need is `ethAddressAliasSet`.
But we might not remember that! We can check the @ref:[ethAddress* docs](../tasks/eth/address/index.md), or use tab completion to help us find our command, and figure out what arguments it needs.

<script>writeOptionalReplaceControl("tut_uasc1_optional_1", "tut_uasc1_optional_1_replace_control", "show optional tab completion tutorial?", "hide optional tab completion tutorial")</script>

@@@ div { #tut_uasc1_optional_1 .optional }

**Figuring out our command via tab completion**

We are trying to set an address alias. It's an <u>A</u>ddress thing, so we can do `ethA<tab>` to get
to the `ethAddress*` submenu and hit `<tab>` again:
```
sbt:eth-command-line> ethAddress
ethAddressAliasCheck            ethAddressAliasDrop             ethAddressAliasList             ethAddressAliasSet              ethAddressBalance               ethAddressSenderDefaultDrop     
ethAddressSenderDefaultPrint    ethAddressSenderDefaultSet      ethAddressSenderOverrideDrop    ethAddressSenderOverridePrint   ethAddressSenderOverrideSet     ethAddressSenderPrint           
sbt:eth-command-line> ethAddress
```
It's a <u>A</u>lias-related thing, so we add `A<tab>`:
```
sbt:eth-command-line> ethAddressAlias
ethAddressAliasCheck   ethAddressAliasDrop    ethAddressAliasList    ethAddressAliasSet     
sbt:eth-command-line> ethAddressAlias
```
Of those, it's probably `ethAddressAliasSet` that we want to, um, set an alias. So we try `S<tab>`.
```
sbt:eth-command-line> ethAddressAliasSet
```
But then we'll want to see if it takes any arguments, so we type `<space><tab>`.
```
sbt:eth-command-line> ethAddressAliasSet 
<alias>   
sbt:eth-command-line> ethAddressAliasSet 
```
The command's first argument is an alias, so let's give it that. This application is a fortune cookie, so let's just
call our alias `fortune`.
```
sbt:eth-command-line> ethAddressAliasSet fortune
```
The command might take more than one argument. So let's type `<space><tab>` again:
```
sbt:eth-command-line> ethAddressAliasSet fortune 
<address-hex>    <ens-name>.eth   default-sender
sbt:eth-command-line> ethAddressAliasSet fortune 
```
This is what it looks like when a command is asking for an Ethereum address. In _sbt-ethereum_, an address can
take the form of a long hex string &mdash; `<address-hex>`, an ENS name `<ens-name>.eth` (see the box [below](#ens-box)), or one
of the aliases that has already been defined. When we set the default sender in [Getting Started](getting-started.md),
an alias called `default-sender` was automatically defined.

For the fortune application, what we have is a hex address.
```
sbt:eth-command-line> ethAddressAliasSet fortune 0x82ea8ab1e836272322f376a5f71d5a34a71688f1
```
Just to be sure that the `ethAddressAliasSet` task doesn't require yet another argument, we type `<space><tab>` at the end of that:
```
sbt:eth-command-line> ethAddressAliasSet fortune 0x82ea8ab1e836272322f376a5f71d5a34a71688f1 
{invalid input}   
sbt:eth-command-line> ethAddressAliasSet fortune 0x82ea8ab1e836272322f376a5f71d5a34a71688f1 
```
The response `{invalid input}` means there are no other arguments we can provide. So, just hit `<return>`!

@@@

Once we've figured out our command...
```
sbt:eth-command-line> ethAddressAliasSet fortune 0x82ea8ab1e836272322f376a5f71d5a34a71688f1
[info] Alias 'fortune' now points to address '0x82ea8ab1e836272322f376a5f71d5a34a71688f1' (for chain with ID 1).
[info] Refreshing caches.
[success] Total time: 0 s, completed Dec 12, 2018 4:08:37 PM
```
To make sure that it took, let's list our address aliases:
```
sbt:eth-command-line> ethAddressAliasList
default-sender -> 0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2
fortune -> 0x82ea8ab1e836272322f376a5f71d5a34a71688f1
[success] Total time: 0 s, completed Dec 12, 2018 4:09:34 PM
```
Remember that even though we haven't explicitly defined an alias, if we set up a default address (as we did in [Getting Started](getting-started.md)),
there will automatically be a `default-sender` alias to that address.

We do also have the alias `fortune`, which we've just defined. Success!

@@@ note

<a name="ens-box"></a>
**Some _Ethereum_ addresses have public _ENS names_**, easy-to-use names that usually ends with `.eth`.

_sbt-ethereum_ supports the use of ENS names in place of an address wherever addresses are used,
if an address has been associated with the name.

But you have to pay for public names, most simple ones are already taken (and they take a few days to acquire).

For internal use, it's often more convenient to use addres aliases.

@@@

### Acquiring an ABI for a smart contract

In order to interact with an _Ethereum_ smart contract, we usually require a description of its functions,
called an _ABI_ (or _Application Binary Interface_), so that we know what we can do with it and how to
work with it. The ABI is a bit of "JSON-formatted text". (You don't have to know what that means, but we'll
see an example below!)

_sbt-ethereum_ supports several different ways to associate an ABI with the address of the smart contract it describes.
But before we do that, we need to find the ABI. Sometimes, you may get an ABI directly from the author of your
smart contract. If you come to develop your own smart contracts, _sbt-ethereum_ will automatically generate
and retain ABIs for you. But often, we need to find an ABI that has been published somewhere.

At this writing, a very useful repository of ABIs is [_Etherscan_](http://etherscan.io/). _Etherscan_ only stores
ABIs which the site itself has verified, which offers an extra level of confidence (although of course, we are
trusting _Etherscan_ and its verification process if we use them as a source).

#### Manually acquiring an ABI from _Etherscan_

First, let's manually copy and paste our contract's ABI from _Etherscan_. Let's find it:

1. Go to [http://etherscan.io/](http://etherscan.io)
2. Plug the address of the contract &mdash; `0x82ea8ab1e836272322f376a5f71d5a34a71688f1` &mdash; into the search field in the upper right
   and hit "Go". ( _Etherscan_ doesn't know anything about our _sbt-ethereum_ alias `fortune`, so we can't use that! )
3. On the page that comes up, look for a tab that says "Code" with a small green checkmark. Click on that.
4. Scroll down until you see the "Contract ABI" section.

Now that we have access to the ABI, we can import it into our _sbt-ethereum_ shoebox database:
```
sbt:eth-command-line> ethContractAbiImport fortune
[warn] No Etherscan API key has been set, so you will have to directly paste the ABI.
[warn] Consider acquiring an API key from Etherscan, and setting it via 'etherscanApiKeyImport'.
Contract ABI: 
```
The `ethContractAbiImport` command requires the address we want to associate the ABI with as an argument. We could have
supplied the long hex address but why? We've defined an easy to use alias `fortune`. (Remember, always, to use tab
completion so _sbt-ethereum_'s long names don't drive you nuts. Typing `ethC<tab>A<tab>I<tab> f<tab>` would get us there!)

`ethContractAbiImport` is an _interactive command_. It is prompting us for what it needs, in this case the cotract ABI.
We copy and paste that from the _Etherscan_ page. Yes it is long (any are much longer). Copy the while thing, or use the "Copy"
button on the upper right of the ABI on the _Etherscan_ page. After you have pasted it in, hit `<return>`.
```
sbt:eth-command-line> ethContractAbiImport fortune
[warn] No Etherscan API key has been set, so you will have to directly paste the ABI.
[warn] Consider acquiring an API key from Etherscan, and setting it via 'etherscanApiKeyImport'.
Contract ABI: [{"constant":false,"inputs":[{"name":"fortune","type":"string"}],"name":"addFortune","outputs":[],"payable":false,"stateMutability":"nonpayable","type":"function"},{"constant":true,"inputs":[{"name":"","type":"uint256"}],"name":"fortunes","outputs":[{"name":"","type":"string"}],"payable":false,"stateMutability":"view","type":"function"},{"constant":true,"inputs":[],"name":"drawFortune","outputs":[{"name":"fortune","type":"string"}],"payable":false,"stateMutability":"view","type":"function"},{"constant":true,"inputs":[],"name":"countFortunes","outputs":[{"name":"count","type":"uint256"}],"payable":false,"stateMutability":"view","type":"function"},{"inputs":[{"name":"initialFortune","type":"string"}],"payable":false,"stateMutability":"nonpayable","type":"constructor"},{"anonymous":false,"inputs":[{"indexed":false,"name":"author","type":"address"},{"indexed":false,"name":"fortune","type":"string"}],"name":"FortuneAdded","type":"event"}]
[info] ABI is now known for the contract at address 0x82ea8ab1e836272322f376a5f71d5a34a71688f1
[info] Refreshing caches.
[success] Total time: 176 s, completed Dec 12, 2018 5:00:48 PM
```
Yay! We've associated the contract's ABI with its address.

@@@ note

**On the same _Etherscan_ page where we found the ABI you can find the source code of the `fortune` smart contract.**

If you are interested in understanding _Ethereum_ programming, you may want to review that code and refer to it as we work with the
smart contract. But you don't have to. You can interact with _Ethereum_ smart contracts without understanding their code, but then you
do then have to trust developers or reviewers of the code to correctly explain what the smart contract does.

The code of the `fortune` contract is very simple. You may find you can make sense of it.

@@@

#### Automatically acquiring an ABI from _Etherscan_ (optional)

Manually copying and pasting ABIs gets old if we need to do it a lot. With a quick, one-time setup, we can really streamline the process.

The one-time set-up:

1. Create a sign-in at _Etherscan_
2. Once you are signed in, click on "MY ACCOUNT" near the top of the screen.
3. Look for "API-KEYs" at the bottom of the menu on the left hand side. Click that.
4. Click "Create Api Key" in the panel that appears. You'll be asked for an optional app name.
   Just hit "Continue", you don't need a name.
5. You'll see an "Api-Key Token" appear, something like `87ZVGM4Q4RRMGPSY761MMJZRWYU1RUW339` (but not
   that &mdash; get your own damned key!).

Once you have an "Api-Key Token" available, you are ready to go. Again, use your own damned API-KEY, not the example key shown below.
```
sbt:eth-command-line> etherscanApiKeySet 87ZVGM4Q4RRMGPSY761MMJZRWYU1RUW339
Etherscan API key successfully set.
[success] Total time: 0 s, completed Dec 12, 2018 5:18:15 PM
```
Now we can run the same command that we did before, but it will go more easily:
```
sbt:eth-command-line> ethContractAbiImport fortune
An ABI for '0x82ea8ab1e836272322f376a5f71d5a34a71688f1' on chain with ID 1 has already been memorized. Overwrite? [y/n] y
An Etherscan API key has been set. Would you like to try to import the ABI for this address from Etherscan? [y/n] y
Attempting to fetch ABI for address '0x82ea8ab1e836272322f376a5f71d5a34a71688f1' from Etherscan.
ABI found:
[{"name":"addFortune","inputs":[{"name":"fortune","type":"string"}],"outputs":[],"constant":false,"payable":false,"stateMutability":"nonpayable","type":"function"},{"name":"fortunes","inputs":[{"name":"","type":"uint256"}],"outputs":[{"name":"","type":"string"}],"constant":true,"payable":false,"stateMutability":"view","type":"function"},{"name":"drawFortune","inputs":[],"outputs":[{"name":"fortune","type":"string"}],"constant":true,"payable":false,"stateMutability":"view","type":"function"},{"name":"countFortunes","inputs":[],"outputs":[{"name":"count","type":"uint256"}],"constant":true,"payable":false,"stateMutability":"view","type":"function"},{"name":"FortuneAdded","inputs":[{"name":"author","type":"address","indexed":false},{"name":"fortune","type":"string","indexed":false}],"anonymous":false,"type":"event"},{"inputs":[{"name":"initialFortune","type":"string"}],"payable":false,"stateMutability":"nonpayable","type":"constructor"}]
Use this ABI? [y/n] y
[info] ABI is now known for the contract at address 0x82ea8ab1e836272322f376a5f71d5a34a71688f1
[info] Refreshing caches.
[success] Total time: 13 s, completed Dec 12, 2018 5:19:41 PM
```
Now _sbt-ethereum_ automatically finds and downloads from _Etherscan_ the same ABI that we copied and pasted by hand.
The _Etherscan_ API-KEY you have set is retained for you by _sbt-ethereum_, and is pretty much permanent.
From now on, you can use `ethContractAbiImport` to import ABIs from _Etherscan_ without further ceremony.

### Accessing read-only methods of an _Ethereum_ smart contract

The _methods_ of an _Ethereum_ smart contract are the paths through which you interact with it. In _sbt-ethereum_, you use
`ethTransactionView` to access methods that just "look up" information from or about a smart contract witout changing anything,
and `ethTransactionInvoke` to make changes.

Let's learn our fortune. Let's begin by trying the command `ethTransactionView`, which allows us to access some, but not all, of the methods
of an _Ethereum_ smart contract, and let's us see the result. We'll tab-complete through the process of calling the command.
Type `ethT<tab>V<tab><tab>`, and you should see something like this:
```
sbt:eth-command-line> ethTransactionView
/                ::               <address-hex>    <ens-name>.eth   default-sender   fortune          
sbt:eth-command-line> ethTransactionView
```
Hmm...

@@@ note

**Whenever you see '/' and '::' in your tab completion, you've reached the end of a task name.**

Usually, you will want to price `<space>` to complete the task name and then `<tab>` to see
just the arguments to the command.

@@@

Type `<space><tab>`:
```
sbt:eth-command-line> ethTransactionView 
<address-hex>    <ens-name>.eth   default-sender   fortune          
sbt:eth-command-line> ethTransactionView 
```
The task `ethTransactionView` is asking for an _Ethereum_ address as its first argument, which may take the form
of a long hex String, an [ENS name](#ens-name), or an address alias. We want to work with the smart contract at the
address we have already named `fortune`. So we can type `f<tab><tab>` from here:
```
sbt:eth-command-line> ethTransactionView fortune
countFortunes   drawFortune     fortunes        
sbt:eth-command-line> ethTransactionView fortune
```
`countFortunes`, `drawFortune`, and `fortunes` are _methods_ of the smart contract at that address. _sbt-ethereum_ knows that,
because we have associated the contract's ABI with its address. Let's try `drawFortune` (which is the central method of this
very very very exciting application). We just have to type `d<tab>`.
```
sbt:eth-command-line> ethTransactionView fortune drawFortune
```
Then we type `<space><tab>` to see whether the method requires arguments.
```
sbt:eth-command-line> ethTransactionView fortune drawFortune 
{invalid input}
sbt:eth-command-line> ethTransactionView fortune drawFortune
```
`{invalid input}` means there is nothing more to complete, which means the `drawFortune` method does not require any arguments.
We just press `<return>`.
```
sbt:eth-command-line> ethTransactionView fortune drawFortune 
[info] The function 'drawFortune' yields 1 result.
[info]  + Result 1 of type 'string', named 'fortune', is "Against all evidence, all will be well."
[success] Total time: 0 s, completed Dec 15, 2018 11:57:51 PM
```
Congratulations! You've run a method of an _Ethereum_ smart contract. If you wait 30 seconds and try again, you'll
probably (but not necessarily!) see something different:
```
sbt:eth-command-line> ethTransactionView fortune drawFortune
[info] The function 'drawFortune' yields 1 result.
[info]  + Result 1 of type 'string', named 'fortune', is "This is going to be an amazing day!"
[success] Total time: 0 s, completed Dec 16, 2018 12:01:19 AM
```
The `Fortune` smart contract contains a list of fortunes from which it "randomly" selects one when we call `drawFortune`.
This "random number" changes approximately 15 seconds.

Let's try another of the methods that were available to us:
```
sbt:eth-command-line> ethTransactionView fortune countFortunes
[info] The function 'countFortunes' yields 1 result.
[info]  + Result 1 of type 'uint256', named 'count', is 10
[success] Total time: 0 s, completed Dec 16, 2018 12:02:54 AM
```
This tells us that the list of available fortunes is currently 10 long.

Let's try the final method that was available to us:
```
sbt:eth-command-line> ethTransactionView fortune fortunes
[error] Expected '"'
[error] Expected non-double-quote-space character
[error] ethTransactionView fortune fortunes
[error]                                    ^
sbt:eth-command-line> 
```
This is not a very clear or informative error message, but basically, `sbt` is telling us that something more was expected than what
we typed. Let's try again with `ethTransactionView fortune fortunes`, but then type `<space><tab>` to see what else we need.
```
sbt:eth-command-line> ethTransactionView fortune fortunes 
<mapping key, of type uint256>
sbt:eth-command-line> ethTransactionView fortune fortunes
```
_sbt_ethereum_ is telling us that the method requires an argument, a "mapping key" in this case of type "uint256". "uint" means
"unsigned integer. It is a fancy way of saying that the method expects a number. Let's try the number `6`:
```
sbt:eth-command-line> ethTransactionView fortune fortunes 6
[info] The function 'fortunes' yields 1 result.
[info]  + Result 1 of type 'string' is "This is going to be an amazing day!"
[success] Total time: 1 s, completed Dec 16, 2018 12:21:36 AM
```
The `fortunes` method lets you see all of the fortunes in the application's list. `countFortunes` told us there were 10 fortunes,
which would be numbered from 0 through 9. What happens if we ask for fortune number 999?
```
sbt:eth-command-line> ethTransactionView fortune fortunes 999
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
[error] (Compile / ethTransactionView) com.mchange.sc.v2.jsonrpc.package$JsonrpcException: gas required exceeds allowance or always failing transaction [code=-32000]: No further information
[error] Total time: 1 s, completed Dec 16, 2018 12:43:47 AM
```
The error message is not very helpful, but this is often what you see when you try to do something with an _Ethereum_ smart contract that
just doesn't work.

### Calling methods that change the data on the blockchain

Let's remember how we saw the methods we could access in the `Fortune` application:.
```
sbt:eth-command-line> ethTransactionView fortune
countFortunes   drawFortune     fortunes        
sbt:eth-command-line> ethTransactionView fortune 
```
Let's try a different task `ethTransactionInvoke`. If we type `ethTransactionInvoke fortune` and then tab-complete:
```
sbt:eth-command-line> ethTransactionInvoke fortune 
addFortune      countFortunes   drawFortune     fortunes        
sbt:eth-command-line> ethTransactionInvoke fortune 
```
There is one more method available to `ethTransactionInvoke` that was not available to `ethTransactionDeploy`, `addFortune`. Unlike all of the methods
we looked at previously, `addFortune` wouldn't only show us information about fortunes already on the blockchain, but would add new information.

Let's try it. First, let's see what arguments the method requires:
```
sbt:eth-command-line> ethTransactionInvoke fortune addFortune 
<fortune, of type string>
sbt:eth-command-line> ethTransactionInvoke fortune addFortune 
```
The method wants a thing called "fortune" whose type is "string". Let's try giving it a string. Strings should be set in double quotes.
```
sbt:eth-command-line> ethTransactionInvoke fortune addFortune "Make up a better fortune than this, please."
```
Maybe the method takes more than one argument? Let's see, by typing `<space><tab>` after our quoted string.
```
sbt:eth-command-line> ethTransactionInvoke fortune addFortune "Make up a better fortune than this, please." 
{invalid input}
sbt:eth-command-line> ethTransactionInvoke fortune addFortune "Make up a better fortune than this, please." 
```
Again, `{invalid input}` in response to a `<tab>` means there is nothing more that can be completed. So our one argument is enough.
Let's hit `<return>`:
```
sbt:eth-command-line> ethTransactionInvoke fortune addFortune "Make up a better fortune than this, please." 
[info] Unlocking address '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2' (on chain with ID 1, aliases ['default-sender'])
Enter passphrase or hex private key for address '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2': 
```
To "invoke" a transaction, rather than just to "view" one, requires that we unlock our _Ethereum_ address. Transactions
can alter the state of smart contracts on the _Ethereum_ blockchain, but cost _Ether_, a valuable cryptocurrency, in order to execute.
We'll often refer to ether as "ETH".

Let's go ahead and unlock our address, and see what happens.
```
sbt:eth-command-line> ethTransactionInvoke fortune addFortune "Make up a better fortune than this, please." 
[info] Unlocking address '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2' (on chain with ID 1, aliases ['default-sender'])
Enter passphrase or hex private key for address '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2': ***************
[info] V3 wallet(s) found for '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2' (aliases ['default-sender'])

==> T R A N S A C T I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x82ea8ab1e836272322f376a5f71d5a34a71688f1 (with aliases ['fortune'] on chain with ID 1)
==>   From:  0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2 (with aliases ['default-sender'] on chain with ID 1)
==>   Data:  0x4cf373e60000000000000000000000000000000000000000000000000000000000000020000000000000000000000000000000000000000000000000000000000000002b4d616b6520757020612062657474657220666f7274756e65207468616e20746869732c20706c656173652e000000000000000000000000000000000000000000
==>   Value: 0 Ether
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: addFortune(string)
==>     Arg 1 [name=fortune, type=string]: "Make up a better fortune than this, please."
==>
==> The nonce of the transaction would be 0.
==>
==> $$$ The transaction you have requested could use up to 112773 units of gas.
==> $$$ You would pay 3 gwei for each unit of gas, for a maximum cost of 0.000338319 ether.
==> $$$ This is worth 0.03148396614 USD (according to Coinbase at 1:01 PM).

Would you like to submit this transaction? [y/n] 
```
At the time I am trying this, the transaction I've proposed to invoke would cost up to about 3.1&cent;.

*It may cost more than that when you try to execute it! The price of using the Ethereum network varies over time.*

@@@ warning

**Always check the potential cost of a transaction before agreeing to execute it.**

Invoking, rather than merely viewing, a transaction costs real money.

Be sure that you are okay with what you might be spending.

@@@

I'm okay with spending 3.1&cent;, so I'll go ahead and say `y`.

```
sbt:eth-command-line> ethTransactionInvoke fortune addFortune "Make up a better fortune than this, please." 
[info] Unlocking address '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2' (on chain with ID 1, aliases ['default-sender'])
Enter passphrase or hex private key for address '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2': ***************
[info] V3 wallet(s) found for '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2' (aliases ['default-sender'])

==> T R A N S A C T I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x82ea8ab1e836272322f376a5f71d5a34a71688f1 (with aliases ['fortune'] on chain with ID 1)
==>   From:  0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2 (with aliases ['default-sender'] on chain with ID 1)
==>   Data:  0x4cf373e60000000000000000000000000000000000000000000000000000000000000020000000000000000000000000000000000000000000000000000000000000002b4d616b6520757020612062657474657220666f7274756e65207468616e20746869732c20706c656173652e000000000000000000000000000000000000000000
==>   Value: 0 Ether
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: addFortune(string)
==>     Arg 1 [name=fortune, type=string]: "Make up a better fortune than this, please."
==>
==> The nonce of the transaction would be 0.
==>
==> $$$ The transaction you have requested could use up to 112773 units of gas.
==> $$$ You would pay 3 gwei for each unit of gas, for a maximum cost of 0.000338319 ether.
==> $$$ This is worth 0.03148396614 USD (according to Coinbase at 1:01 PM).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0xa7d785cec2da4b415958e80bb9bbe131a4c39622da4ef064ae1e58fd4338285f' will be submitted. Please wait.
[error] com.mchange.sc.v2.jsonrpc.package$JsonrpcException: insufficient funds for gas * price + value [code=-32000]: No further information
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
[error] (Compile / ethTransactionInvoke) com.mchange.sc.v2.jsonrpc.package$JsonrpcException: insufficient funds for gas * price + value [code=-32000]: No further information
[error] Total time: 15 s, completed Dec 18, 2018 1:01:20 PM
```
An ugly-looking error has occurred. The key piece of information in that mess is `insufficient funds for gas * price + value`.

Our transaction might only have required a few cents worth of ETH to execute, but we don't have a few cents of ETH in our account!

To see that, let's try the command `ethAddressBalance`:
```
sbt:eth-command-line> ethAddressBalance default-sender
0 ether (as of the latest incorporated block, address 0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2)
This corresponds to approximately 0.00 USD (at a rate of 84.74 USD per ETH, retrieved at 1:36 AM from Coinbase)
[success] Total time: 1 s, completed Dec 18, 2018 1:39:56 AM
```
We have no ETH in our account, so we cannot invoke a transaction that changes data on the blockchain.

@@@ note

**We can always "view" a transaction for free, which means executing functions that read data from our smart contract but that don't make changes.**

The methods available via `ethTransactionView` are accessible to us for free, but will make no changes. It is only when we use `ethTransactionInvoke`
or other tasks that would change the _Ethereum_ blockchain that we have to pay.

@@@

We'll have to get some ETH before we can invoke methods like `addFortune` that change the state of the blockchain. We'll turn to that next.


