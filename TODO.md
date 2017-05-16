# TODO

* Generate Java/Scala stubs
* ethDebugListing
* Richer ABI management
   let ethAbiList show deployed as well as memorized ABIs
       ethAbiShow should dump JSON text
* xethEstimateGas
* xethSendMessage
* Prettify output of log items in client transactions
* Integrate keystore into database? (?)
* No values permissible in constant function invokation parser
* Support for compiling and deploying libraries and linking contracts that reference them
* Import raw private key as wallet
* Backup / restore of repository (especially db)
   -- use DROP ALL OBJECTS then RUNSCRIPT to restore
* Import/Export of contract metadata between repositories
* Hide all Repostory stuff behind a facade in preparation for supporting multiple repositories
* Hide keystore stuff behind a facade, maybe incorporate local keystore in DB (only after revision of consuela keystore stuff)
* Incorporate transaction log in DB (?)
