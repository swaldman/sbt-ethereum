# Known Shortcomings

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

### Line numbers in Solidity compiler error messages don't match those in your source code

_sbt-ethereum_ takes full control of the _Solidity_ import process, and combines all resources into a single generated
solidity file. Even if your code has no imports, solidity will embed some comments into the generated file that is actually
compiled, so the line numbers won't match.

To see precisely the files that got compiled, and source code whose line numbers match compiler errors, look in your
projects's `target/ethereum/solidity` directory for a file ending in `.sol`.

We'll hopefully provide a convenience for this shortly.

### Things change quickly, we are always a step behind

@@@ div { .tight }

  * _sbt-ethereum_ supports "traditional" auction-based ENS. A transition to a new system is expected to begin in May 2019.
  * _sbt-ethereum_ does not yet support Vyper contracts (but hopes to soon!)

@@@

### Linking predeployed Solidity libraries is not yet supported

Solidity libraries come in too basic flavors. They can be defined to contain purely `internal` function, in which case they must be compiled with the contracts that use them.
_sbt-ethereum_ supports these libraries.

But Solidity libraries can also be defined with external functions, in which case they are isolated constructs that must be separately predeployed.
_sbt-ethereum_ does not currently support linking to such libraries.

(This shouldn't be difficult to add, but we've rarely wanted it. Let us know if this is a feature you need!)

### Java 11 support is untested

It might work! It might not.




  