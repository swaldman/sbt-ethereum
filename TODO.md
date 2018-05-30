# TODO

* ethHelp

* Fix bizarre string literal parsing issue when """Fortune "Some string."""" style strings are used in auto spawn

* Some form of logging of overwriting of aliases

* Let compilations store the name of the project from which they were compiled

* Make stub generation incremental
    
* The parser for ethTransactionDeploy should handle aliases / ens-names etc when ctor args are addresses

* Generalize reasoning surrounding when interactive transaction approver should be used

* enable Scala libraries to deploy as well as interact with already-deployed contracts
  * embed compilations as a resource in jar files
  * add method in jsonrpc.Invoker for contract creation

* make price feeds sensitive to blockchainId
* better formatting of currency amounts (fewer decimals)

* ethTransactionExportInvoke / ethTransactionExportSend  
* ethDebugListing and ethDebugInBrowser [ Desktop.getDesktop().browse( ... ) ]
  * get rid of excess lines in listings, change the suffixes to '.soldebug'
  * also for stubs and testing resources
* richer balances (multi-unit, fiat balances)
* ethContractCompilationsAlias*
* xethEstimateGas
* xethSendMessage
* xethSignMessage
* Prettify output of log items in client transactions
* Excise Compilation from package.scala (jsonrpc) by wrapping the map that is currently its type definition
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


