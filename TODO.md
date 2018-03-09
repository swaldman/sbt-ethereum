# TODO

* reset plugin state on unload
* aggressively privatify stuff
* interactive migrate (or restore) of database, default compiler
* tld-sensitive ENS parsers
* Figure out why mlog output seems no longer to be showing up, on the console
  or in sbt-ethereum.log
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
* ethToken*
* add getEvents [backed by RPC getLogs(...)] methods to stub events
* Make stub compilation incremental
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
* Redo failable
