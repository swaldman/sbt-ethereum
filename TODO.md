# TODO

* Inquire to auto-set 'defaultSender' when a autogenerating a wallet on startup

* Fix bizarre string literal parsing issue when """Fortune "Some string."""" style strings are used in auto spawn

* Some form of logging of overwriting of aliases

* Redo failable
  * Afterwards, undo temporary extra protection in SolidityEvent
  * type parameterized fail?
  * assert / assertoStackTrace / assertRecover

* Let compilations store the name of the project from which they were compiled

* Make stub generation incremental
    
* interactive migrate (or restore) of database

* Setting for turning optimization on / off in compilation

* Generalize reasoning surrounding when interactive transaction approver should be used

* enable libraries to deploy as well as interact with already-deployed contracts
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
* Prettify output of log items in client transactions
* Excise Compilation from package.scala (jsonrpc) by wrapping the map that is currently its type definition
* ens TTL, and Deed stuff
* erc20*

* add getEvents [backed by RPC getLogs(...)] methods to stub events
* Move stub.ScalaParameterHelper somewhere more sensible
* Uncloseable wrappers for default Poller (done!), Exchanger.Factory, jsonrpc.Client.Factory

* built-in solcJ-based eth-netcompile
* Place a time limit on compiler checks, so that a freeze doesn't prevent sbt startup!
* Support for compiling and deploying solidity libraries and linking contracts that reference them
* Import raw private key as wallet
* Backup / restore of repository (especially db)
  * use DROP ALL OBJECTS then RUNSCRIPT to restore
* Import/Export of contract metadata between repositories
* Hide keystore stuff behind a facade, maybe incorporate local keystore in DB (only after revision of consuela keystore stuff)
* Incorporate transaction log in DB (?)
* add type resriction to log / event Topic Seq, to limit length to four elements
* Integrate keystore into database? (?)
* Generate Java stubs
* More consistency about when tasks use println(...) vs log.info(...)

* Maybe someday put logging configuration into sbt-ethereum repository directory (where it can be easily edited). (maybe put log files there too?)

* Warn if no 'defaultSender' is set?

