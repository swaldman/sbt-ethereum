# TODO

DB schema updates

* Implement a tags table

* Implement logic that checks prior existence of tables ("fresh" vs "preexisting") to inform schema upgrades

* Even with freshness restriction (only upgading "fresh" tables), upgrades from very old schemas might fail if old upgrades try to
  impose foreign key constraints on old versions of "fresh" tables. Think about fixing this.

Other

* Tool to derive linearization from AST
  ( see https://ethereum.stackexchange.com/questions/56802/a-solidity-linearization-puzzle/56803?noredirect=1#comment67743_56803 )

* Make all *List tasks regex sensitive using new filters in texttable lib

* ethContractAbiEncode <abi-identifier> args => data (task name? unfortunately we can't do decode, would conflict with drop)

* ethTransactionProxyDeploy

* Prompt for alias after ethKeystoreWalletV3Create?

* ethTransactionAsync*

* Wrap Poller.TimoutException as Invoker.TimeoutException, or define a cross-cutting Timeout trait (so users of Invoker or stubs don't have to work with an Exception representing an implementation detail)
  - Note that we currently have defined a stub.TransactionInfo.TimeoutException
  - or maybe we should just alias Poller.TimeoutException everywher that seems convenient?

* change (or alias) `contractAddress` field in stubs to simple address

* stub.Sender -- synchronous versions of convenience methods?

* Implement EIP-191 and EIP-712 signing.

* Warn prominently when waiting for a transaction times out, rather than successfully getting receipt. (Observed problem in ensAuctionFinalize.)

* Maybe remove stack trace from jsonrpc.Invoker$TransactionDisapprovedExceptions generated from the transaction approver function?

* ethTransactionExportInvoke / ethTransactionExportSend  

* The parser for ethTransactionDeploy should handle aliases / ens-names etc when ctor args are addresses

* make price feeds sensitive to chainId

* better formatting of currency amounts (fewer decimals)

* ethHelp

* Fix bizarre string literal parsing issue when """Fortune "Some string."""" style strings are used in auto spawn

* Some form of logging of overwriting of aliases

* Generalize reasoning surrounding when interactive transaction approver should be used

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
* Prettify output of log items in client transactions
* Excise jsonrpc.Compilation from package.scala (jsonrpc) by wrapping the map that is currently its type definition
* ens TTL, and Deed stuff
* erc20*

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

* Warn if no 'defaultSender' is set?


