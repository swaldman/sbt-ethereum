# Using a Smart Contract (I)

_Ethereum_ applications are defined as one or more _smart contract_, each of which live at an
address on the _Ethereum_ blockchain. To interact with one, the first thing you need is it's address.

The first thing we need, if we mean to interact with one, is to know it's address. We are going to
work with a brilliant, very exciting, _fortune cookie application_. Finally you will learn the truth
about your future. And you don't have to just trust me on that. The blockchain is trustless, right?

Anyway, the address of out _fortune_ application is `0x82ea8ab1e836272322f376a5f71d5a34a71688f1`.

### Assigning an alias to an Ethereum address

As you can see, an Ethereum address is a long stupid string of "hex" digits that would be very annoying
and error prone to type. _So try never to type one!_ When absolutely you need to, copy and paste the address, and then double
check that you didn't drop any digits at the beginning or the end. But better yet, _sbt-ethereum_ permits you to define
easy to use, tab-completable names as "address aliases". So, let's do it:
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
Notice that even though we haven't set an alias, if we set up a default address (as we did in [Getting Started](getting-started.md),
there will automatically be a `default-sender` alias to that address.

We do also have the alias `fortune`, which we've just defined. Success!

@@@ note

**Some _Ethereum_ addresses have a public _ENS name_**, and easy to use name that usually ends with `.eth`.

_sbt-ethereum_ supports the use of ENS names in place of an address wherever addresses are used,
if an address has been associated with the name.

But you have to pay for public names, most simple ones are already taken (and they take a few days to acquire).
For internal use, it's often more convenient to use addres aliases.

@@@

### Acquiring an ABI for a smart contract

In order to interact with an _Ethereum_ smart contract, we usually require a description of its functions,
called an _ABI_ (or _Application Binary Interface_), so that you know what you can do with it and how to
work with it. The ABI is a bit of "JSON-formatted text". (You don't have to know what that means, but you'll
see an example below!)

_sbt-ethereum_ supports several different ways to associate an ABI with the address of the smart contract it describes.
But before you do that, you need to find the ABI. Sometimes, you may get an ABI directly from the author of your
smart contract. If you come to develop your own smart contracts, _sbt-ethereum_ will automatically generate
and retain ABIs for you. But often, you need to find an ABI that has been published somewhere.

At this writing, a very useful repository of ABIs is [Etherscan](http://etherscan.io/). _Etherscan_ only stores
ABIs which the site itself has verified, which offers an extra level of confidence (although of course, you are
trusting _Etherscan_ and its verification process if you use them as a source).

#### Manually acquiring an ABI from _Etherscan_

First, let's manually copy and paste our contract's ABI from _Etherscan_. Let's find it:

1. Go to [http://etherscan.io/](http://etherscan.io)
2. Plug the address of the contract &mdash; `0x82ea8ab1e836272322f376a5f71d5a34a71688f1` &mdash; into the search field in the upper right
   and hit "Go". ( _Etherscan_ doesn't know anything about your _sbt-ethereum_ alias `fortune`, so you can't use that! )
3. On the page that comes up, look for a tab that says "Code" with a small green checkmark. Click on that.
4. Scroll down until you see the "Contract ABI" section.

Now that we have access to the ABI, we can import it into our _sbt-ethereum_ shoebox database:
```
sbt:eth-command-line> ethContractAbiImport fortune
[warn] No Etherscan API key has been set, so you will have to directly paste the ABI.
[warn] Consider acquiring an API key from Etherscan, and setting it via 'etherscanApiKeyImport'.
Contract ABI: 
```
The `ethContractAbiImport` command requires the address you want to associate the ABI with as an argument. We could have
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

#### Automatically acquiring an ABI from _Etherscan_ (optional)

Manually copying and pasting ABIs gets old if you need to do it a lot. With a quick, one-time setup, you can really streamline the process.

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
Attempting to fetch ABI for address '${hexString(address)}' from Etherscan.
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


