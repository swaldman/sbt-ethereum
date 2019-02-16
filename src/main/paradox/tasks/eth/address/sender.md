# Sender*

In any _sbt-ethereum_ session, there should generally be defined a _current sender_, an Ethereum address
on whose behalf any interactions with the block chain will be made.

Any time you want, you can define the current sender by setting a _sender override_.
But you will usually want to define a _default sender_, which will always be available if it is not
overridden.

**The _default sender_ is persistent, It is stored in the _sbt-ethereum_ shoebox database, and will remain in place
between sessions and across projects.**

**The _sender override_ is scoped only to the current session. If you restart
`sbt` the _sender override_ will be gone, and you'll have to set it again if you want it back.**

_sbt-ethereum_ projects can also define a sender in a project build or globally (in `.sbt`) via the SBT setting `ethAddressSender`.

The order of preference that defines the current sender is:

@@@ div { .smaller .bolder }

1. Any _sender override_ (defined for the current sessions's [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID)
2. Any `ethcfgAddressSender` hard-coded in SBT build
3. Any `ethcfgAddressSender` hard-coded in `.sbt` folder
4. Any _default sender_, defined persistently in the _sbt-etherem_ shoebox database for the current [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID)
5. Any external address defined via the system property `eth.sender` upon SBT startup
6. Any external address defined via the environment variable `ETH_SENDER` upon SBT startup

@@@

Default senders and sender overrides are scoped to [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain IDs.
For example, a default sender defined for Ethereum Mainnet (Chain ID 1) may and usually will be a different address than that set for
the Ethereum Ropsten testnet (Chain ID 3). See @ref:[`ethNodeChainId*`](../node/chain_id.md) for information on how to manage which
chain your session addresses.

The commands below allow you to manage the default sender and the sender override directly within _sbt-ethereum_. For most purposes they are all you need.
(Senders hardcoded into SBT configuration or defined externally are rare.)

### ethAddressSenderDefaultDrop

@@@ div { .keydesc }

**Usage:**
```
> ethAddressSenderDefaultDrop
```
Removes any default sender that may have been set for the current [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID.

**Example:**
```
> ethAddressSenderDefaultDrop
[info] The default sender address for chain with ID 1 was '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2', but it has now been successfully dropped.
[info] Refreshing caches.
[success] Total time: 0 s, completed Jan 7, 2019 1:59:33 PM
```

@@@

### ethAddressSenderDefaultPrint

@@@ div { .keydesc }

**Usage:**
```
> ethAddressSenderDefaultPrint
```
Displays any default sender that may have been set for the current [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID.

**Example:**
```
> ethAddressSenderDefaultPrint
[info] The default sender address for chain with ID 1 is '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2'.
[success] Total time: 0 s, completed Jan 7, 2019 2:08:20 PM
```

@@@

### ethAddressSenderDefaultSet

@@@ div { .keydesc }

**Usage:**
```
> ethAddressSenderDefaultSet <address-as-hex-or-ens-or-alias>
```
Defines a default sender for the current [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID.

**Examples:**
```
> ethAddressSenderDefaultSet 0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2
> ethAddressSenderDefaultSet some-address-alias
> ethAddressSenderDefaultSet some-address.eth
```

**Example response:**
```
[info] Successfully set default sender address for chain with ID 1 to '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2'.
[info] You can use the synthetic alias 'default-sender' to refer to this address.
[info] Refreshing caches.
[success] Total time: 0 s, completed Jan 7, 2019 2:18:32 PM
```

@@@

### ethAddressSenderOverrideDrop

@@@ div { .keydesc }

**Usage:**
```
> ethAddressSenderOverrideDrop
```
Removes the any sender override that may have been set for the current session and [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID.

**Example:**
```
sbt:eth-command-line> ethAddressSenderOverrideDrop
[info] No sender override is now set.
[info] Effective sender will be determined by 'ethcfgAddressSender' setting, a value set via 'ethAddressSenderDefaultSet', the System property 'eth.sender', or the environment variable 'ETH_SENDER'.
[success] Total time: 0 s, completed Jan 7, 2019 2:34:35 PM
```

@@@

### ethAddressSenderOverridePrint

@@@ div { .keydesc }

**Usage:**
```
> ethAddressSenderOverridePrint
```
Displays any sender override that may have been set for the current session and [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID.

**Example:**
```
> ethAddressSenderOverridePrint
[info] A sender override is set, address '0x13e3d8d785cdeb1d18f298dcf07ea35a659e157d' (on chain with ID 1, aliases ['secondary-address'])).
[success] Total time: 0 s, completed Jan 7, 2019 2:38:37 PM
```

@@@

### ethAddressSenderOverride

@@@ div { .keydesc }

**Usage:**
```
> ethAddressSenderOverride <address-as-hex-or-ens-or-alias>
```
This is a shorthand for @ref:[`ethAddressSenderOverrideSet`](#ethAddressSenderOverrideSet). Please see that command for more information.

@@@

### ethAddressSenderOverrideSet

@@@ div { .keydesc }

**Usage:**
```
> ethAddressSenderOverrideSet <address-as-hex-or-ens-or-alias>
```
Defines a sender override for the current session and [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID.

The sender override address will take precedence over any default sender that may have been defined, but will not persist beyond a single _sbt-ethereum_ session.

**Examples:**
```
> ethAddressSenderOverrideSet 0x13e3d8d785cdeb1d18f298dcf07ea35a659e157d
> ethAddressSenderOverrideSet some-address-alias
> ethAddressSenderOverrideSet some-address.eth
```

**Example response:**
```
[info] Sender override set to '0x13e3d8d785cdeb1d18f298dcf07ea35a659e157d' (on chain with ID 1, aliases ['secondary-address'])).
[success] Total time: 0 s, completed Jan 7, 2019 2:42:16 PM
```

@@@

### ethAddressSenderPrint

@@@ div { .keydesc }

**Usage:**
```
> ethAddressSenderPrint
```
Displays the current effective sender for the current session and [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID,
and explains how that value has been determined.

**Example:**
```
> ethAddressSenderPrint
[info] The current effective sender address is '0x13e3d8d785cdeb1d18f298dcf07ea35a659e157d' (with aliases ['secondary-address'] on chain with ID 1).
[info]  + This value has been explicitly set as a session override via 'ethAddressSenderOverrideSet'.
[info]  + It has overridden a default sender address for chain with ID 1 set in the sbt-ethereum shoebox: '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2' (with aliases ['default-sender'] on chain with ID 1)
[success] Total time: 0 s, completed Jan 7, 2019 5:45:25 PM
```

@@@

