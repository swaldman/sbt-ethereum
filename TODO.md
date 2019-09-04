# TODO

DB schema updates

* Implement a tags table

* Implement node url table / rework node finding logic

* Implement logic that checks prior existence of tables ("fresh" vs "preexisting") to inform schema upgrades

* Even with freshness restriction (only upgrading "fresh" tables), upgrades from very old schemas might fail if old upgrades try to
  impose foreign key constraints on old versions of "fresh" tables. Think about fixing this.

* Maybe define in-database constraints to prevent aliases that might mimic addresses or ENS names from ever being provided.
  (Currently there is just a require statement in `Table.AddressAliases.sert(...)`)

* Maybe destaticify "shoebox" so we could interactively open, choose whether to migrate, pay attention to sbt setting etc on startup
  - maybe provide read-only access to previous db versions when opening old repositories? but might be a bit hard to manage.

Security

* Define a cache that holds multiple EthSigners according to autoRelockSeconds (use EthSigner rather than EthPrivateKey, see below).

* Make all sbt-ethereum EthSigners (including cached ones) hit the InteractionService, for either a credential or a confirmation, so
  no signatures can be silently made.

* Define a Java object whose internal byte[] is private that is wrappable to an EthSigner. Try to use either SecurityManager (unlikely
  given sbt.TrapExit, no control over sysprops etc) or Java-9-modules (future version, when we require java 11), to ensure that the private
  storage is not accessible -- checked by the runtime and not susceptible to a reflectve setAccessible(...)

Other

* ethAddressPrint

* Modify 'ethAddressSenderDrop' message to 1) reveal the sender dropped; 2) provide better information about the current sender

* Fix tab-completion of a space in ethTransactionView/Mock/Invoke

* Fix broken stub generation for solidity array / scala Seq return values

* More informative message about how to deal with timeouts.

* Update default solidity version.

* Redefine TransactionApprover in terms of (senderAddress, EthTransaction.Unsigned) rather than EthTransaction.Signed. Approve before signing.

* Define `ethNodeChainId` for CLIs to get Chain ID. Expose `verboseAddress` to CLIs.

* Make a stub.Nonce constructor that accepts Option[BigInt] / BigInt (and maybe other integral types)

* Exported signatures are coming out as Vector[Byte] wrather than ImmutableArraySeq.Byte (because our export functions add to Vectors...). Fix this.

* EthSignature.fromXXX methods sometimes accept immutable.Seq[Byte] when they should accept the more tolerant Seq[Byte]

* Test whether EthAddress.Source and similar require explicitly an immutable.Seq[Byte] variant (rather than just Seq[Byte])

* Ensure drop and set commands at least print what they drop or replace

* Make it possible to specify an optional Chain ID in ethNodeChainIdUrlDefaultSet

* Utilities -- unixtime, denomination conversions, etc

* Consuela EthSignature.fromXXX factory methods should test V for presence of Chain ID, and build EthSignature.WithChainId when appropriate.
  (Now these methods would just fail if asked to decode the hex of a signature with Chain ID.

* Consuela EthSignature should include public support for signatures of raw, unhashed bytes.

* Maybe prompt should include current sender and overrides?

* Support multiple shoeboxes and user-definable locations

* Make price feed more robust to scheduler failure. (Why are there sometimes scheduler failures?)

* Why in Test config no tab-complete compilations?

* Define setting etherscancfgApiKey, for users who prefer global-sbt-style configuration

* object prepare in stubs, that with functions that yield unsigned transactions

* clean up solidity compiler autosetting, let compilers be specified in builds, let there be a settable user-default compiler that persists between sessions.
  - Current logic in xethFindCurrentSolidityCompilerTask is not so great

* ensUnsigned*

* let repositories suggest ABIs <--> addresses <--> aliases ???

* Tool to derive linearization from AST
  ( see https://ethereum.stackexchange.com/questions/56802/a-solidity-linearization-puzzle/56803?noredirect=1#comment67743_56803 )

* Make all *List tasks regex sensitive using new filters in texttable lib

* ethTransactionProxyDeploy

* Prompt for alias after ethKeystoreWalletV3Create?

* ethTransactionAsync*

* Wrap Poller.TimoutException as Invoker.TimeoutException, or define a cross-cutting Timeout trait (so users of Invoker or stubs don't have to work with an Exception representing an implementation detail)
  - Note that we currently have defined a stub.TransactionInfo.TimeoutException
  - or maybe we should just alias Poller.TimeoutException everywher that seems convenient?

* stub.Sender -- synchronous versions of convenience methods?

* Implement EIP-191 and EIP-712 signing.

* Warn prominently when waiting for a transaction times out, rather than successfully getting receipt. (Observed problem in ensAuctionFinalize.)

* The parser for ethTransactionDeploy should handle aliases / ens-names etc when ctor args are addresses

* ethHelp

* Fix bizarre string literal parsing issue when """Fortune "Some string."""" style strings are used in auto spawn

* enable Scala libraries to deploy as well as interact with already-deployed contracts
  * embed compilations as a resource in jar files
  * add method in jsonrpc.Invoker for contract creation

* ethDebugListing and ethDebugInBrowser [ Desktop.getDesktop().browse( ... ) ]
  * get rid of excess lines in listings, change the suffixes to '.soldebug'
  * also for stubs and testing resources
* richer balances (multi-unit, fiat balances)
* ethContractCompilationsAlias*
* xethEstimateGas
* xethSignMessage
* Excise jsonrpc.Compilation from package.scala (jsonrpc) by wrapping the map that is currently its type definition

* fetch (accessing events bia RPC getLogs(...)) in stub utilities is async by default. Consistent with rest of design, define asyncFetch(...) and fetch(...)
* Move stub.ScalaParameterHelper somewhere more sensible
* Uncloseable wrappers for default Poller (done!), Exchanger.Factory, jsonrpc.Client.Factory
*   -- Also, maybe eliminate global-implicit by default status for these factories, require some ceremony to import them

* built-in solcJ-based eth-netcompile
* Place a time limit on compiler checks, so that a freeze doesn't prevent sbt startup!
* Support for compiling and deploying solidity libraries and linking contracts that reference them
* Import/Export of contract metadata between repositories
* Incorporate transaction log in DB (?)
* add type resriction to log / event Topic Seq, to limit length to four elements

* Generate Java stubs

* More consistency about when tasks use println(...) vs log.info(...)

* Maybe someday put logging configuration into sbt-ethereum repository directory (where it can be easily edited). (maybe put log files there too?)

* A flag in Invoker.Context (update stub.Context factories and stub factories in stub.Generator) for broadcasting rather than round-robin-ing Seq URL.Sources

* documentation: glossary (hex), (ens), (wallet), (ABI), (transaction)

* Should I switch from Compile to Zero as the config setting for tasks expected to be run wthout a user-specified config?

* Should I define distinct sbt configs for Mainnet, Rinkeby, Ropsten, etc (so we might do "Rinkeby / ethTransactionInvoke ..." etc)?

* ethTransactionMockRaw ?

* Rationalize / modularize Mutables

* Include information about overrides in transaction approver messages?

* 'ethAddressSender' as a task silently returning the current sender for tasks and plugins




