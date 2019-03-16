# ethKeystore*

_sbt-ethereum_ tasks related to managing keys and wallets of addresses from which this installation of _sbt-ethereum_ may originate transactions.

### ethKeystoreList

@@@ div { .keydesc}

**Usage:**
```
> ethKeystoreList
```

Displays the _Ethereum_ addresses in _sbt-ethereum_'s "shoebox" keystore, and any aliases associated with those addresses.

**Example:**
```
> ethKeystoreList
+--------------------------------------------+
| Keystore Addresses                         |
+--------------------------------------------+
| 0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2 | <-- default-sender
| 0x13e3d8d785cdeb1d18f298dcf07ea35a659e157d | <-- secondary-address
+--------------------------------------------+
[success] Total time: 11 s, completed Mar 15, 2019 11:56:14 PM
```

@@@

### ethKeystorePrivateKeyReveal

@@@ div { .keydesc}

**Usage:**
```
> ethKeystorePrivateKeyReveal <address-as-hex-or-ens-or-alias>
```

Displays the _**raw hex private key**_ associated with an _Ethereum_ addresses in _sbt-ethereum_'s "shoebox" keystore.

_**This is obviously really dangerous! Anyone who sees the raw private key and, say, takes a quick snapshot, can easily
steal any value or identity associated with your account**_

**Example:**
```
> ethKeystorePrivateKeyReveal 0xd78fe1d978ad8cc5a94484725d059bec694f215e
[info] V3 wallet(s) found for '0xd78fe1d978ad8cc5a94484725d059bec694f215e'
Enter passphrase or hex private key for address '0xd78fe1d978ad8cc5a94484725d059bec694f215e': ************************
Are you sure you want to reveal the unencrypted private key on this very insecure console? [Type YES exactly to continue, anything else aborts]: YES
0x53867ea9a256ae5481ad0c0b07591da1ec0425a60b905500aeeb10ac8fe3b33e
[success] Total time: 10 s, completed Mar 16, 2019 12:01:18 AM
```

@@@

### ethKeystoreWalletV3Create

@@@ div { .keydesc}

**Usage:**
```
> ethKeystoreWalletV3Create
```

Creates a new "V3" JSON wallet representing an _Ethereum_ address and a passcode-encrypted private key in _sbt-ethereum_'s "shoebox" keystore.

**Example:**
```
> ethKeystoreWalletV3Create 
[info] Generated keypair for address '0xa75b3640510b5073dff7dff22dd440c60cfdc72e'
[info] Generating V3 wallet, alogorithm=scrypt, n=262144, r=8, p=1, dklen=32
Enter passphrase for new wallet: *******************
Please retype to confirm: *******************
[info] Wallet generated into sbt-ethereum shoebox: '/Users/testuser/Library/Application Support/sbt-ethereum'. Please backup, via 'ethShoeboxBackup' or manually.
[info] Consider validating the wallet using 'ethKeystoreWalletV3Validate 0xa75b3640510b5073dff7dff22dd440c60cfdc72e'.
[success] Total time: 19 s, completed Mar 16, 2019 12:07:56 AM
```

@@@

### ethKeystoreWalletV3FromJsonImport

@@@ div { .keydesc}

**Usage:**
```
> ethKeystoreWalletV3FromJsonImport
```
Allows user to paste the raw JSON of a V3 wallet and have it integrated into the _sbt-ethereum_ "shoebox" keystore.

**Example:**
```
> ethKeystoreWalletV3FromJsonImport
V3 Wallet JSON: {"address":"d78fe1d978ad8cc5a94484725d059bec694f215e","crypto":{"mac":"98c53c7d05c7daeb1b8d2bef6af8643bf679a73a34d014f09a45f7035088f89b","kdf":"pbkdf2","cipherparams":{"iv":"70c4340f5305dca3fad9e54d47db4a2b"},"ciphertext":"9a2fc0212827877068d5a54f0ad64fab8767d52849535305e984682db25be23d","cipher":"aes-128-ctr","kdfparams":{"salt":"b9562670465f5ec13a4babe91aea6bd41d3400dfb0f97b1fb21617cbd62e9782","dklen":32,"c":262144,"prf":"hmac-sha256"}},"id":"f2f3bad7-4da0-424b-a363-fde7a6e3cb49","version":3}
[info] Imported JSON wallet for address '0xd78fe1d978ad8cc5a94484725d059bec694f215e', but have not validated it.
[info] Consider validating the JSON using 'ethKeystoreWalletV3Validate 0xd78fe1d978ad8cc5a94484725d059bec694f215e'.
[success] Total time: 3 s, completed Mar 16, 2019 12:35:14 AM
```

@@@

### ethKeystoreWalletV3FromPrivateKeyImport

@@@ div { .keydesc}

**Usage:**
```
> ethKeystoreWalletV3FromPrivateKeyImport
```
Allows user to enter a 32-byte hex private key, and generate a JSON V3 wallet in _sbt-ethereum_'s "shoebox" keystore, which represents
that private key password-encrypted, and its associated ethereum address.

**Example:**
```
> ethKeystoreWalletV3FromPrivateKeyImport
Please enter the private key you would like to import (as 32 hex bytes): ******************************************************************
The imported private key corresponds to address '0xd78fe1d978ad8cc5a94484725d059bec694f215e'. Is this correct? [y/n] y
Generating V3 wallet, alogorithm=pbkdf2, c=262144, dklen=32
Enter passphrase for new wallet: *****************
Please retype to confirm: *****************
[info] Wallet created and imported into sbt-ethereum shoebox: '/Users/testuser/Library/Application Support/sbt-ethereum'. Please backup, via 'ethShoeboxBackup' or manually.
[info] Consider validating the wallet using 'ethKeystoreWalletV3Validate 0xd78fe1d978ad8cc5a94484725d059bec694f215e'.
[success] Total time: 53 s, completed Mar 16, 2019 12:24:46 AM
```

@@@

### ethKeystoreWalletV3Print

@@@ div { .keydesc}

**Usage:**
```
> ethKeystoreWalletV3Print <address-as-hex-or-ens-or-alias>
```
Prints the V3 wallet representing a passcode-encrypted private key and its associated _Ethereum_ address
in raw JSON format.

**Example:**
```
> ethKeystoreWalletV3Print 0xd78fe1d978ad8cc5a94484725d059bec694f215e
[info] V3 wallet(s) found for '0xd78fe1d978ad8cc5a94484725d059bec694f215e'
{"address":"d78fe1d978ad8cc5a94484725d059bec694f215e","crypto":{"mac":"98c53c7d05c7daeb1b8d2bef6af8643bf679a73a34d014f09a45f7035088f89b","kdf":"pbkdf2","cipherparams":{"iv":"70c4340f5305dca3fad9e54d47db4a2b"},"ciphertext":"9a2fc0212827877068d5a54f0ad64fab8767d52849535305e984682db25be23d","cipher":"aes-128-ctr","kdfparams":{"salt":"b9562670465f5ec13a4babe91aea6bd41d3400dfb0f97b1fb21617cbd62e9782","dklen":32,"c":262144,"prf":"hmac-sha256"}},"id":"f2f3bad7-4da0-424b-a363-fde7a6e3cb49","version":3}
[success] Total time: 0 s, completed Mar 16, 2019 12:28:03 AM
```

@@@

### ethKeystoreWalletV3Validate

@@@ div { .keydesc}

**Usage:**
```
> ethKeystoreWalletV3Validate
```
Verifies that new "V3" JSON wallet representing an _Ethereum_ address in _sbt-ethereum_'s "shoebox" keystore can be unlocked with a given passcode or hex private key.

**Example:**
```
> ethKeystoreWalletV3Validate 0xa75b3640510b5073dff7dff22dd440c60cfdc72e
[info] V3 wallet(s) found for '0xa75b3640510b5073dff7dff22dd440c60cfdc72e'
Enter passphrase or hex private key for address '0xa75b3640510b5073dff7dff22dd440c60cfdc72e': *******************
[info] A wallet for address '0xa75b3640510b5073dff7dff22dd440c60cfdc72e' is valid and decodable with the credential supplied.
[success] Total time: 8 s, completed Mar 16, 2019 12:10:50 AM
```

@@@


