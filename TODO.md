# TODO

* Invoker.{GasApprover => Approver}, set it in the InvocationContext
* let plugin add appropriate consuela version to libraryDependencies automatically if a stub package is defined
* Make constructor arg hex acessible from repository deployments.
  -- then maybe remove verbosity about arg hex from ethContractSpawnOnly
* ethDebugListing and ethDebugInBrowser [ Desktop.getDesktop().browse( ... ) ]
  * get rid of excess lines in listings, change the suffixes to '.soldebug'
  * also for stubs and testing resources
* richer balances (multi-unit, fiat balances)
* ethContractCompilationsAlias*
* xethEstimateGas
* xethSendMessage
* add type resriction to log / event Topic Seq, to limit length to four elements
* Prettify output of log items in client transactions
* Excise Compilation from package.scala (jsonrpc) by wrapping the map that is currently its type definition
* ethAddressSenderEffective
* ethNameService*
* ethToken*
* Make stub compilation incremental
* Uncloseable wrappers for default Poller (done!), Exchanger.Factory, jsonrpc.Client.Factory
* Load-balancing exchanger / invoker / stub
* built-in solcJ-based eth-netcompile
* Integrate keystore into database? (?)
* Place a time limit on compiler checks, so that a freeze doesn't prevent sbt startup!
* Support for compiling and deploying solidity libraries and linking contracts that reference them
* Import raw private key as wallet
* Backup / restore of repository (especially db)
  * use DROP ALL OBJECTS then RUNSCRIPT to restore
* Import/Export of contract metadata between repositories
* Hide keystore stuff behind a facade, maybe incorporate local keystore in DB (only after revision of consuela keystore stuff)
* Incorporate transaction log in DB (?)
* Generate Java stubs
