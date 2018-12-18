# Working with Ether & Multiple Addresses

_Ether_ is the "fuel" of the _Ethereum_ network. Whenever you wish to interact with a smart contract
in a way that might update its state, you have to pay some Ether for the privilege (as we have
@ref:[seen](using-a-smart-contract-i.md), just looking up information from an Ethereum smart contract without
changing anything is always free).

_Ether_ is a "cryptocurrency", like Bitcoin and many others. We'll generally refer to it as ETH.
To work with the Ethereum network, you need a little bit of ETH. _But just a little, not much!_
10 or 20 USD will go a long way, in terms of letting you develop, deploy, and interact with
sanely written Ethereum smart contracts.

@@@ note

While users need a little bit of ETH to interact with the network, __most financial smart contracts &mdash; applications
that manipulate flows of economic value &mdash; should not use ETH as their primary currency or store of value.__

Like most cryptocurrencies, __the value of ETH in terms of real-world goods and services is very, very
volatile.__

It's better to define applications in terms of _stablecoins_, cryptographic _tokens_ whose
value is credibly pegged to a real-world currency like the US dollar.

But be careful, choose well!
Some stablecoins are more credible than others.
Holding value in a bad stablecoin is like holding money in a bad bank with no deposit insurance.
You might lose yur money.

@@@

### Checking the Ether balance of addresses

We did this in the @ref:[prior tutorial](using-a-smart-contract-i.md), but let's do it again.
To check your default account's ETH balance, use the command `ethAddressBalance`:
```
sbt:eth-command-line> ethAddressBalance default-sender
0 ether (as of the latest incorporated block, address 0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2)
This corresponds to approximately 0.00 USD (at a rate of 84.24 USD per ETH, retrieved at 9:13 PM from Coinbase)
[success] Total time: 0 s, completed Dec 16, 2018 9:13:59 PM
```

The argument to `ethAddressBalance` is an _Ethereum_ account, which can be provided in all the forms
that _sbt-ethereum_ accepts. We've used the automatic _address alias_ `default-sender`, but we could
also have provided a hex address directly, or an ENS name, like this:
```
sbt:eth-command-line> ethAddressBalance pejorative.eth
0.36043144606611496 ether (as of the latest incorporated block, address 0x465e79b940bc2157e4259ff6b2d92f454497f1e4)
This corresponds to approximately 30.31 USD (at a rate of 84.1 USD per ETH, retrieved at 9:40 PM from Coinbase)
[success] Total time: 1 s, completed Dec 16, 2018 9:43:51 PM
```
We can also run this command without providing any argument at all! How does that work? **_sbt-ethereum_ relies on the
notion of a _current sender_.** Many commands need to act "on behalf" of a particular _Ethereum_ address, and
use the current sender.

Let's check this out:
```
sbt:eth-command-line> ethAddressSenderPrint
[info] The current effective sender address is '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2' (with aliases ['default-sender'] on chain with ID 1).
[info]  + This value is the default sender address defined in the sbt-ethereum shoebox for chain with ID 1. 
[info]  + It has not been overridden with a session override or by an 'ethcfgAddressSender' setting in the project build or the '.sbt' folder.
[success] Total time: 0 s, completed Dec 16, 2018 10:00:10 PM
```
By default, the current sender is the account we set up during our @ref:[initial setup](getting-started.md) as the default
sender, and which automatically has the alias `default-sender`. However, _we can override the current sender at any time_, using the task
`ethAddressSenderOverrideSet`.

If we try `ethAddressBalance` with no sender, it will use the current sender.
```
sbt:eth-command-line> ethAddressBalance
0 ether (as of the latest incorporated block, address 0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2)
This corresponds to approximately 0.00 USD (at a rate of 84.22 USD per ETH, retrieved at 10:00 PM from Coinbase)
[success] Total time: 0 s, completed Dec 16, 2018 10:04:14 PM
```

@@@ warning

When sending ETH, or tokens, or interacting with _Ethereum_ applications in any capacity, **be sure you understand
on behalf of which _Ethereum_ address you are acting!**

You can run `ethAddressSenderPrint` explicitly, but before transacting on your behalf, _sbt-ethereum_ will always ask
you to confirm your willingness to transact, and will display the raw, hex _Ethereum_ address. Always double check
that you are transact from the address that you intend to transact.

@@@

### Funding an Ethereum address

Before you can really work with _Ethereum_, you will need an address (probably your `default-sender` address) to be funded
with a bit of ETH.

The easiest way to do this is to buy a bit (you don't need much, $10 or $20 is plenty to get started!) from a service such
as [Coinbase](https://www.coinbase.com). Once you have bought some ETH, you can go under the "Accounts" tab, find your
Ethereum wallet, and click "Send". Send to your _sbt-ethereum_ default sender address.

If you've forgotten your default sender, try `ethAddressSenderDefaultPrint`.
(Obviously, _send to your own default sender address, **NOT** to the one below!_):
```
sbt:eth-command-line> ethAddressSenderDefaultPrint
[info] The default sender address for chain with ID 1 is '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2'.
[success] Total time: 0 s, completed Dec 16, 2018 10:28:40 PM

```
Before sending money to your address, you may want to verify that you remember the passcode that unlocks it.
(If you don't remember the passcode, the address will receive your funds just fine, but no one will ever, _ever_, be able to access them.
Let's verify, using the command `ethKeystoreWalletV3Validate`:
```
sbt:eth-command-line> ethKeystoreWalletV3Validate 0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2
[info] V3 wallet(s) found for '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2' (aliases ['default-sender'])
Enter passphrase or hex private key for address '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2': ***************
[info] A wallet for address '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2' is valid and decodable with the credential supplied.
[success] Total time: 8 s, completed Dec 16, 2018 10:33:57 PM
```

The output looks good (note the statement "is valid and decodable with the credential supplied", and the `[success]`). So
we can go ahead and send to this address.

@@@ warning

If you purchasing ETH via websites like Coinbase, **it may take several days before your ETH is available for sending**.

That is annoying, but difficult to avoid.

If you know someone who already has ETH, you can buy it from them informally. Just ask them to send to your address,
and pay them what it's worth in a regular currency.

But it's probably best to keep informal exchanges to small amounts. Large informal exchanges may prompt regulatory
scrutiny.

@@@