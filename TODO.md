# TODO

* ethDeployAuto / ethDeployAutoContracts (and test subkey) / managedResources += propfile mapping contract names to deployed addresses
* ethDebugListing and ethDebugInBrowser [ Desktop.getDesktop().browse( ... ) ]
    -- also for stubs and testing resources
* setting to control whether compilations are retained (defaul true in Compile, false in Test)
* Richer ABI management
   let ethAbiList show deployed as well as memorized ABIs
       ethAbiShow should dump JSON text
* xethEstimateGas
* xethSendMessage
* ethSelfPing => ethPingSelf
* Prettify output of log items in client transactions
* event handling generally
* should stubs be case classes?
* Integrate keystore into database? (?)
* Place a time limit on compiler checks, so that a freeze doesn't prevent sbt startup
* Support for compiling and deploying libraries and linking contracts that reference them
* Import raw private key as wallet
* Backup / restore of repository (especially db)
   -- use DROP ALL OBJECTS then RUNSCRIPT to restore
* Import/Export of contract metadata between repositories
* Hide all Repostory stuff behind a facade in preparation for supporting multiple repositories
* Hide keystore stuff behind a facade, maybe incorporate local keystore in DB (only after revision of consuela keystore stuff)
* Incorporate transaction log in DB (?)
* Generate Java stubs
