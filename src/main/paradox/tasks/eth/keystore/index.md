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
[info] Generated keypair for address '0xb3dd7fe3e7cdf45e8c6120df6ed9ddc2136714a8'
[info] Generating V3 wallet, alogorithm=scrypt, n=262144, r=8, p=1, dklen=32
Enter passphrase for new wallet: ***************
Please retype to confirm: ***************
[info] Wallet generated into sbt-ethereum shoebox: '/Users/testuser/Library/Application Support/sbt-ethereum'. Please backup, via 'ethShoeboxBackup' or manually.
[info] Consider validating the wallet using 'ethKeystoreWalletV3Validate 0xb3dd7fe3e7cdf45e8c6120df6ed9ddc2136714a8'.
Would you like to define an alias for address '0xb3dd7fe3e7cdf45e8c6120df6ed9ddc2136714a8' (on chain with ID 1)? [y/n] y
Please enter an alias for address '0xb3dd7fe3e7cdf45e8c6120df6ed9ddc2136714a8' (on chain with ID 1): green-wallet-main
[info] Alias 'green-wallet-main' now points to address '0xb3dd7fe3e7cdf45e8c6120df6ed9ddc2136714a8' (for chain with ID 1).
[info] Refreshing caches.
[success] Total time: 25 s, completed Jul 11, 2019, 2:11:21 PM
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
V3 Wallet JSON: {"address":"aecbfff6ccd465d9a7fe84079e1f20dfdf6e46aa","crypto":{"cipher":"aes-128-ctr","ciphertext":"ab06b8aac3fffcf51ec3da63f656298d27e038816a28f4b35094a74a55450fbc","kdfparams":{"p":1,"r":8,"salt":"ca142093f3b5d0f73924b87fa521d4f2ccbab892048d01dfa8d5b025cf09b371","dklen":32,"n":262144},"cipherparams":{"iv":"9e7603b0f3db200467aef294affbfc26"},"kdf":"scrypt","mac":"fb5bc3e62f4ce7da7ad168e6dfa2c5bd2a2c7a955822a8080ef4f300103ba301"},"id":"e4ff0e97-9483-49a2-8fe5-f1eef1941ba9","version":3}
[info] Imported JSON wallet for address '0xaecbfff6ccd465d9a7fe84079e1f20dfdf6e46aa', but have not validated it.
[info] Consider validating the JSON using 'ethKeystoreWalletV3Validate 0xaecbfff6ccd465d9a7fe84079e1f20dfdf6e46aa'.
Would you like to define an alias for address '0xaecbfff6ccd465d9a7fe84079e1f20dfdf6e46aa' (on chain with ID 1)? [y/n] y
Please enter an alias for address '0xaecbfff6ccd465d9a7fe84079e1f20dfdf6e46aa' (on chain with ID 1): red-wallet-main
[info] Alias 'red-wallet-main' now points to address '0xaecbfff6ccd465d9a7fe84079e1f20dfdf6e46aa' (for chain with ID 1).
[info] Refreshing caches.
[success] Total time: 58 s, completed Jul 11, 2019, 2:07:38 PM
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
Please enter the private key you would like to import (as 32 hex bytes): ****************************************************************
The imported private key corresponds to address '0x2edb3588eef9257ad4d65e81b54c8049b0c79354'. Is this correct? [y/n] y
Generating V3 wallet, alogorithm=pbkdf2, c=262144, dklen=32
Enter passphrase for new wallet: ***************
Please retype to confirm: ***************
[info] Wallet created and imported into sbt-ethereum shoebox: '/Users/testuser/Library/Application Support/sbt-ethereum'. Please backup, via 'ethShoeboxBackup' or manually.
[info] Consider validating the wallet using 'ethKeystoreWalletV3Validate 0x2edb3588eef9257ad4d65e81b54c8049b0c79354'.
Would you like to define an alias for address '0x2edb3588eef9257ad4d65e81b54c8049b0c79354' (on chain with ID 1)? [y/n] y
Please enter an alias for address '0x2edb3588eef9257ad4d65e81b54c8049b0c79354' (on chain with ID 1): blue-wallet-main
[info] Alias 'blue-wallet-main' now points to address '0x2edb3588eef9257ad4d65e81b54c8049b0c79354' (for chain with ID 1).
[info] Refreshing caches.
[success] Total time: 146 s, completed Jul 11, 2019, 1:59:58 PM
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


