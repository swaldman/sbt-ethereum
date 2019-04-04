# Known Shortcomings

### Things change quickly, we are always a step behind

@@@ div { .tight }

  * _sbt-ethereum_ does not yet support Solidity 0.5.x
  * _sbt-ethereum_ supports "traditional" auction-based ENS. A transition to a new system is expected to begin in May 2019.

@@@

### Array inputs cannot include spaces

When providing arguments to solidity functions, you may need to type array values on the command line. Ideally, array literals should
tolerate spaces before and after commas, but _sbt-ethereum_ requires arrays to _not_ contain spaces (except within quoted strings, for string arrays).

This is good:
```
> ethTransactionInvoke calculator sum [1,2,3]
```

This will fail, _**don't do this**_:
```
> ethTransactionInvoke calculator sum [1, 2, 3] 
```

### Addresses in arrays must be specified as hex

Usually, wherever an address is required, you can specify the address as raw hex, as an @ref:[address alias](../tasks/eth/address/alias.md), or as an ENS name.
However, when specifying _arrays_ of addresses (as arguments to solidity functions), only raw hex addresses are currently supported.


  