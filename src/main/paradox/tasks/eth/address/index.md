@@@ index

* [Alias*](alias.md)
* [Sender*](sender.md)

@@@

@@@ div { .no-display #ethAddressToc }

@@toc { depth=4 }

@@@

# ethAddress*

_sbt-ethereum_ tasks related to addresses, including @ref:[checking address balances](#ethaddressbalance), @ref:[managing address aliases](alias.md) and @ref:[configuring the current sender address](sender.md).

### ethAddressAlias*

Manage human-friendly aliases for _ethereum_ addresses. See the @ref:[address alias commands page](alias.md), or choose a command below:

@@@ div { #addressAliasList .embedded-toc-list }

&nbsp;

@@@

### ethAddressBalance

@@@ div { .keydesc}

**Usage:**
```
> ethAddressBalance [optional-address]
```

Finds the Ether balance of any _ethereum_ address. The address can be formatted as an full hex address, as an _sbt-ethereum_ address alias, or as an ENS address. If no address is provided,
the current sender address of the current sbt-ethereum session is queried.

**Examples:**
```
> ethAddressBalance default-sender
> ethAddressBalance 0x465e79b940bc2157e4259ff6b2d92f454497f1e4
> ethAddressBalance stevewaldman.eth
> ethAddressBalance
```
**Example response:**
```
1.25024244606611496 ether (as of the latest incorporated block, address 0x465e79b940bc2157e4259ff6b2d92f454497f1e4)
This corresponds to approximately 195.21 USD (at a rate of 156.135 USD per ETH, retrieved at 10:41 PM from Coinbase)
[success] Total time: 0 s, completed Jan 4, 2019 10:44:46 PM
```

@@@

## ethAddressOverride

@@@ div { .keydesc }

**Usage:**
```
> ethAddressOverride <address-as-hex-or-ens-or-alias>
```
This is just an alias to @ref:[`ethAddressSenderOverrideSet`](sender.md#ethaddresssenderoverrideset). Please see that command for more information.

@@@

## ethAddressOverrideDrop

@@@ div { .keydesc }

**Usage:**
```
> ethAddressOverrideDrop
```
This is just an alias to @ref:[`ethAddressSenderOverrideDrop`](sender.md#ethaddresssenderoverridedrop). Please see that command for more information.

@@@

## ethAddressOverridePrint

@@@ div { .keydesc }

**Usage:**
```
> ethAddressOverridePrint
```
This is just an alias to @ref:[`ethAddressSenderOverridePrint`](sender.md#ethaddresssenderoverrideprint). Please see that command for more information.

@@@

## ethAddressOverrideSet

@@@ div { .keydesc }

**Usage:**
```
> ethAddressOverrideSet <address-as-hex-or-ens-or-alias>
```
This is just an alias to @ref:[`ethAddressSenderOverrideSet`](sender.md#ethaddresssenderoverrideset). Please see that command for more information.

@@@

### ethAddressSender*

Manage the current sender associated with your sbt-ethereum sessions. See the @ref:[sender commands page](sender.md), or choose a command below:

@@@ div { #senderList .embedded-toc-list }

&nbsp;

@@@



