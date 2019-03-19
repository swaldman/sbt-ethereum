# Solidity*

Manage and switch between installed solidity compilers.

### ethLanguageSolidityCompilerInstall

@@@ div { .keydesc}

**Usage:**
```
> ethLanguageSolidityCompilerInstall <supported-solidity-compiler-version>
```

Installs a supported Solidity compiler into the _sbt-ethereum_ shoebox.

_**Note: Use tab-completion to see available compiler versions!**_

**Example:**
```
> ethLanguageSolidityCompilerInstall <tab>
0.4.10   0.4.18   0.4.22   0.4.24   0.4.7    0.4.8    
> ethLanguageSolidityCompilerInstall 0.4.10
[info] Installing local solidity compiler into the sbt-ethereum shoebox. This may take a few minutes.
[info] Installed local solcJ compiler, version 0.4.10 in '/Users/swaldman/Library/Application Support/sbt-ethereum/solcJ'.
[info] Testing newly installed compiler... ok.
[info] Refreshing compiler list.
[success] Total time: 1 s, completed Mar 18, 2019 10:58:31 PM

```

@@@

### ethLanguageSolidityCompilerPrint

@@@ div { .keydesc}

**Usage:**
```
> ethLanguageSolidityCompilerPrint
```

Prints to the console the currently active solidity compiler.

**Example:**
```
> ethLanguageSolidityCompilerPrint
[info] Current solidity compiler 'local-shoebox-solc-v0.4.24', which refers to LocalSolc(Some(/Users/swaldman/Library/Application Support/sbt-ethereum/solcJ/0.4.24)).
[success] Total time: 1 s, completed Mar 18, 2019 10:59:37 PM
```

@@@

### ethLanguageSolidityCompilerSelect


@@@ div { .keydesc}

**Usage:**
```
> ethLanguageSolidityCompilerSelect <available-installed-compiler>
```

Switch to a different solidity compiler version than the one currently selected.

_**Note: Use tab-completion to see available installed compilers!**_

**Example:**
```
> ethLanguageSolidityCompilerSelect <tab>
local-shoebox-solc-v0.4.10   local-shoebox-solc-v0.4.18   local-shoebox-solc-v0.4.24   local-shoebox-solc-v0.4.7    
sbt:ens-scala> ethLanguageSolidityCompilerSelect local-shoebox-solc-v0.4.10
[info] Set compiler to 'local-shoebox-solc-v0.4.10'
[success] Total time: 1 s, completed Mar 18, 2019 11:16:38 PM
```

@@@


