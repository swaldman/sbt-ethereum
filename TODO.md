# TODO

* Request aliases on
  - ethAbiMemorize
  - ethContractSpawn

* Let ethAddressAliasSet accept any form of address, not just raw hex

* Redo failable

* Let compilations store the name of the project from which they were compiled

* Make stub compilation incremental
    
* reset plugin state on unload
* aggressively privatify stuff

* Better message when no solidity compiler is installed

* interactive migrate (or restore) of database, default compiler

* Setting for turning optimization on / off in compilation

* enable libraries to deploy as well as interact with already-deployed contracts
  * embed compilations as a resource in jar files
  * add method in jsonrpc.Invoker for contract creation

* make price feeds sensitive to blockchainId

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
* ens Resolver, TTL, and Deed stuff
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

