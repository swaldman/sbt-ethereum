# Other

For contract addresses, you can look up the contents of an arbitrary storage location.

### ethContractStorageLookup

@@@ div { .keydesc}

**Usage:**
```
> ethContractStorageLookup <contract-address-hex-alias-or-ens> <storage-slot> [optional-blocknumber-decimal-hex-or-earliest|latest|pending]
```
Looks up the value at a slot in a deployed contract's storage.

Storage slots can be specified as ints (decimal or hex), but there are some special named storage slots. Use tab completion to check those out.

If no block number is explicitly supplied, storage as of the latest block will be checked.

**Example:**
```
> ethContractStorageLookup AaveLendingPoolV2-Proxy <tab>
;                  eip-1967:admin     eip-1967:beacon    eip-1967:storage

> ethContractStorageLookup AaveLendingPoolV2-Proxy eip-1967:storage
[info] For contract at address '0x7d2768dE32b0b80b7a3454c06BdAc94A69DDc7A9' (with aliases ['AaveLendingPoolV2-Proxy'] on chain with ID 1)...
[info]   as of the most recent block...
[info]     storage slot 0x360894a13ba1a3210667c828492db98dca3e2076cc3735a920a3ca505d382bbc contains value 0x000000000000000000000000c6845a5c768bf8d7681249f8927877efda425baf.
[success] Total time: 0 s, completed Feb 7, 2021, 3:03:40 AM
```
@@@

