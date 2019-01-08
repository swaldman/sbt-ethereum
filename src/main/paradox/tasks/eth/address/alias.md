# Alias*

_Address aliases_ are mappings between easy to type (and tab complete!) names and _Ethereum_ addresses. If you define aliases for the addresses you use
frequently, you should very rarely have to copy and paste long, error-prone hex addresses.

Address alias mappings are defined within, and so scoped to, an _sbt-ethereum shoebox database_. Typically, this means a user has access to all
address aliases she has defined from any _sbt-ethereum_ project she works with on a single machine (and from a single user's account).

Address aliases are scoped to [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain IDs.
So an address alias defined in a mainnet session (Chain ID 1) will not be available in a Ropsten session (Chain ID 3).
See @ref:[`ethNodeChainId*`](../node/chain_id.md) for information on how to manage which chain your session addresses.

### ethAddressAliasCheck

@@@ div { .keydesc}

**Usage:**
```
> ethAddressAliasCheck <address-alias-or-hex-address>
```

When an address alias is provided, prints the hex address that alias references.

When a hex address is provided, prints the address aliases known in the shoebox database for that address.

**Example 1:**
```
> ethAddressAliasCheck fortune
The alias 'fortune' points to address '0x82ea8ab1e836272322f376a5f71d5a34a71688f1'.
[success] Total time: 0 s, completed Jan 4, 2019 11:16:49 PM
```
**Example 2:**
```
> ethAddressAliasCheck 0x82ea8ab1e836272322f376a5f71d5a34a71688f1
The address '0x82ea8ab1e836272322f376a5f71d5a34a71688f1' is associated with aliases ['fortune', 'fortune3'].
[success] Total time: 0 s, completed Jan 4, 2019 11:17:44 PM
```

@@@

### ethAddressAliasDrop

@@@ div { .keydesc}

**Usage:**
```
> ethAddressAliasDrop <address-alias>
```
Removes the mapping of an address alias to a hex address from the _sbt-ethereum_ shoebox database.

**Example:**
```
> ethAddressAliasDrop fortune
[info] Alias 'fortune' successfully dropped (for chain with ID 1).
[info] Refreshing caches.
[success] Total time: 1 s, completed Jan 4, 2019 11:41:13 PM
```

@@@

### ethAddressAliasList

@@@ div { .keydesc}

**Usage:**
```
> ethAddressAliasList
```
Lists all mappings of an address alias to a hex _Ethereum_ address defined in the _sbt-ethereum_ shoebox database.

**Example:**
```
> ethAddressAliasList
default-sender -> 0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2
fortune -> 0x82ea8ab1e836272322f376a5f71d5a34a71688f1
fortune3 -> 0x82ea8ab1e836272322f376a5f71d5a34a71688f1
secondary-address -> 0x13e3d8d785cdeb1d18f298dcf07ea35a659e157d
[success] Total time: 0 s, completed Jan 4, 2019 11:46:37 PM
```

@@@

### ethAddressAliasSet

@@@ div { .keydesc }

**Usage:**
```
> ethAddressAliasSet <new-alias> <address-as-hex-ens-or-alias>
```

Defines a new alias and point it to an address, which may be specified as a hex Ethereum address, via an ENS name, or an already existing address alias.

**Examples:**
```
> ethAddressAliasSet fortune 0x82ea8ab1e836272322f376a5f71d5a34a71688f1
> ethAddressAliasSet fortune fortune3
> ethAddressAliasSet fortune fount-of-wisdom.eth
```
**Example response:**
```
[info] Alias 'fortune' now points to address '0x82ea8ab1e836272322f376a5f71d5a34a71688f1' (for chain with ID 1).
[info] Refreshing caches.
[success] Total time: 0 s, completed Jan 4, 2019 11:42:35 PM
```

@@@


