# etherscan*

Tasks for managing an [_Etherscan_]() API Key.

If an _Etherscan_ API key is defined, then tasks like @ref:[`ethContractAbiImport`](./eth/contract/abi.md#ethcontractabiimport) and
@ref:[`ethContractAbiDefaultImport`](./eth/contract/abi.md#ethcontractabidefaultimport) can download _Etherscan_ verified ABIs, if a
user wishes. (These tasks can always accept ABIs via copy and paste.)

### etherscanApiKeyDrop

@@@ div { .keydesc }

**Usage:**
```
> etherscanApiKeyDrop
```

Drops any etherscan API key that may have been set.

**Example:**
```
> etherscanApiKeyDrop
Etherscan API key successfully dropped.
[success] Total time: 0 s, completed Apr 9, 2019 11:20:42 AM
```

@@@

### etherscanApiKeyPrint

@@@ div { .keydesc }

**Usage:**
```
> etherscanApiKeyPrint
```

Prints to the console any etherscan API key that may have been set, or a message indicating that none has been set.

**Example:**
```
> etherscanApiKeyPrint
The currently set Etherscan API key is ABCDEFGHIJKLMNOPQRSTUVWXYZ12345678
[success] Total time: 0 s, completed Apr 9, 2019 11:20:36 AM
```

@@@

### etherscanApiKeySet

@@@ div { .keydesc }

**Usage:**
```
> etherscanApiKeySet <etherscan-api-key>
```

Sets an etherscan API key

**Example:**
```
> etherscanApiKeySet QZ7GHAK7EVV442M9YHWI2MU6QMKZPA5RAR
Etherscan API key successfully set.
[success] Total time: 0 s, completed Apr 9, 2019 11:20:59 AM
```

@@@


