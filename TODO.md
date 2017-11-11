# TODO

* Condense ethContractSpawnOnly and ethContractSpawnAuto into ethContractSpawn
* ethContractCompilationsAlias*
* Make constructor arg hex acessible from repository deployments.
  -- then maybe remove verbosity about arg hex from ethContractSpawnOnly
* ethDebugListing and ethDebugInBrowser [ Desktop.getDesktop().browse( ... ) ]
  * get rid of excess lines in listings, change the suffixes to '.soldebug'
  * also for stubs and testing resources
* Richer ABI management
   let ethContractAbiList show deployed as well as memorized ABIs
       ethAbiShow should dump JSON text
* richer balances (multi-unit, fiat balances)
* xethEstimateGas
* xethSendMessage
* Prettify output of log items in client transactions
* event handling generally
* ethAddressSenderEffective
* ethNameService*
* ethToken*
* Make stub compilation incremental
* Uncloseable wrappers for default Poller (done!), Exchanger.Factory, jsonrpc.Client.Factory
* Optimize stubs a bit (don't recreate Abi.Function in every call)
* Load-balancing exchanger / invoker / stub
* built-in solcJ-based eth-netcompile
* Integrate keystore into database? (?)
* Place a time limit on compiler checks, so that a freeze doesn't prevent sbt startup!
* change names of xethQueryRepositoryDatabase and xethUpdateRepositoryDatabase to xethSqlQueryRepositoryDatabase and xethSqlUpdateRepositoryDatabase
  *  figure out why failures don't throw Exceptions, and make them do so!
* Support for compiling and deploying solidity libraries and linking contracts that reference them
* Import raw private key as wallet
* Backup / restore of repository (especially db)
  * use DROP ALL OBJECTS then RUNSCRIPT to restore
* Import/Export of contract metadata between repositories
* Hide keystore stuff behind a facade, maybe incorporate local keystore in DB (only after revision of consuela keystore stuff)
* Incorporate transaction log in DB (?)
* Generate Java stubs
