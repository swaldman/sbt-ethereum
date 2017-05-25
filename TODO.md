# TODO

* add the default faucet as 'defaultSender' for blockchain 'testrpc' during schema generation
* in configuration test, autofind the primary key for the default faucet
* Manage testrpc from within sbt-ethereum
* Implement a setting for ephemeral blockchains, compilations on which should not be retained.
  *  or setting to control whether compilations are retained (defaul true in Compile, false in Test)?
* managedResources += propfile mapping contract names to deployed addresses
* should stubs be case classes? (probably not!)
* add code hashes for stub companion objects, and functions for looking up addresses by hash
* ethDebugListing and ethDebugInBrowser [ Desktop.getDesktop().browse( ... ) ]
  * get rid of excess lines in listings, change the suffixes to '.soldebug'
  * also for stubs and testing resources
* Richer ABI management
   let ethAbiList show deployed as well as memorized ABIs
       ethAbiShow should dump JSON text
* xethEstimateGas
* xethSendMessage
* ethSelfPing => ethPingSelf
* Prettify output of log items in client transactions
* event handling generally
* Integrate keystore into database? (?)
* Place a time limit on compiler checks, so that a freeze doesn't prevent sbt startup!
* change names of xethQueryRepositoryDatabase and xethUpdateRepositoryDatabase to xethSqlQueryRepositoryDatabase and xethSqlUpdateRepositoryDatabase
  *  figure out why failures don't throw Exceptions, and make them do so!
* Support for compiling and deploying libraries and linking contracts that reference them
* Import raw private key as wallet
* Backup / restore of repository (especially db)
  * use DROP ALL OBJECTS then RUNSCRIPT to restore
* Import/Export of contract metadata between repositories
* Hide keystore stuff behind a facade, maybe incorporate local keystore in DB (only after revision of consuela keystore stuff)
* Incorporate transaction log in DB (?)
* Generate Java stubs
