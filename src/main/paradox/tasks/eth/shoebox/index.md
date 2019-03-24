# ethShoebox*

The "shoebox" is where sbt-ethereum stashes, on a per user basis...

@@@ div {.tight}
1. A keystore containing JSON wallets representing _Ethereum_ addresses and their encrypted private keys
2. A database containing
    * Address aliases users have defined for commonly used addresses
    * ABIs the user has encountered
    * ABI aliases for referring to stored ABIs
    * Full information about deployed compilations
    * Information about ENS auction bids
    * Configuration information and defaults
3. A log of transactions sbt-ethereum has executed
4. A log of ENS auction bids sbt-ethereum has placed
5. Local installations of Solidity compilers
@@@

@@@ div {.tight}
The shoebox is a directory, placed at

- Windows: `%APPDATA%\sbt-ethereum`
- Mac: `~/Library/sbt-ethereum`
- Other Unix: `~/.sbt-ethereum`
@@@

(You can, if you wish, just backup and restore this directory directly.)

But _sbt-ethereum_ offers conveniences for backing its shoebox up as a zip file, and restoring from its own backups, very conveniently.

@@@ note

**_sbt-ethereum_ skips backing up installed Solidity compilers!**

These can be reinstalled very easily after a restore via the command @ref:[`ethLanguageSolidityCompilerInstall`](../language/solidity.md#ethlanguagesoliditycompilerinstall).

&nbsp;

@@@

### ethShoeboxBackup

@@@ div { .keydesc}

**Usage:**
```
> ethShoeboxBackup
```
Interactively backs up the _sbt-ethereum_ shoebox.

_**Note: Paths must be absolute. _sbt-ethereum_ does not resolve '~' or other shell-isms.**_

**Example:**
```
> ethShoeboxBackup
[warn] No default shoebox backup directory has been selected. Please select a new shoebox backup directory.
Enter the path of the directory into which you wish to create a backup: /Volumes/Backups/sbt-ethereum
Use directory '/Volumes/Backups/sbt-ethereum' as the default sbt-ethereum shoebox backup directory? [y/n] y
[info] Creating SQL dump of sbt-ethereum shoebox database...
[info] Successfully created SQL dump of the sbt-ethereum shoebox database: '/Users/testuser/Library/Application Support/sbt-ethereum/database/h2-dumps/sbt-ethereum-v7-20190324T00h59m16s496msPDT.sql'
[info] Backing up sbt-ethereum shoebox. Reinstallable compilers will be excluded.
[info] sbt-ethereum shoebox successfully backed up to '/Volumes/Backups/sbt-ethereum/sbt-ethereum-shoebox-backup-20190324T00h59m16s521msPDT.zip'.
[success] Total time: 11 s, completed Mar 24, 2019 12:59:16 AM
```

@@@

### ethShoeboxDatabaseDumpCreate

@@@ div { .keydesc}

**Usage:**
```
> ethShoeboxDatabaseDumpCreate
```
Creates an SQL-text dump of the contents of the _sbt-ethereum_ shoebox database. The dump is created within a designated directory inside the shoebox base directory,
from which any dump can quickly be restored.

You can use this to be sure you have a textual source of information in the database, and to create easily restorable snapshots of that data.

**Example:**
```
> ethShoeboxDatabaseDumpCreate
[info] sbt-ethereum shoebox database dump successfully created at '/Users/testuser/Library/Application Support/sbt-ethereum/database/h2-dumps/sbt-ethereum-v7-20190324T01h21m21s194msPDT.sql'.
[success] Total time: 1 s, completed Mar 24, 2019 1:21:21 AM
```

@@@

### ethShoeboxDatabaseDumpRestore

@@@ div { .keydesc}

**Usage:**
```
> ethShoeboxDatabaseDumpRestore
```
Restores an SQL-text dump created by [`ethShoeboxDatabaseDumpCreate`](#ethshoeboxdatabasedumpcreate) from the dump directory inside the shoebox base directory.

**Example:**
```
> ethShoeboxDatabaseDumpRestore
The following sbt-ethereum shoebox database dump files have been found:
	1. /Users/testuser/Library/Application Support/sbt-ethereum/database/h2-dumps/sbt-ethereum-v7-20190324T01h21m21s194msPDT.sql
	2. /Users/testuser/Library/Application Support/sbt-ethereum/database/h2-dumps/sbt-ethereum-v7-20190324T00h59m16s496msPDT.sql
	3. /Users/testuser/Library/Application Support/sbt-ethereum/database/h2-dumps/sbt-ethereum-v7-20190318T23h28m02s982msPDT.sql
Which database dump should we restore? (Enter a number, or hit enter to abort) 1
[info] Restore from dump successful. (Prior, replaced database should have backed up into '/Users/testuser/Library/Application Support/sbt-ethereum/database/h2-superseded'.
[success] Total time: 24 s, completed Mar 24, 2019 1:24:49 AM
```

@@@

### ethShoeboxRestore

@@@ div { .keydesc}

**Usage:**
```
> ethShoeboxRestore
```

Interactively restores the _sbt-ethereum_ shoebox from a file created by [`ethShoeboxBackup`](#ethshoeboxbackup).

_**Note: Paths must be absolute. _sbt-ethereum_ does not resolve '~' or other shell-isms.**_

**Example:**
```
sbt:eth-command-line> ethShoeboxRestore
Search default backup directory '/Volumes/Backups/sbt-ethereum' for backups? [y/n] y
'/Volumes/Backups/sbt-ethereum/sbt-ethereum-shoebox-backup-20190324T00h59m16s521msPDT.zip' is the most recent sbt-ethereum shoebox backup file found. Use it? [y/n] y
[warn] Superseded existing shoebox directory renamed to '/Users/testuser/Library/Application Support/sbt-ethereum-superseded-20190324T01h17m55s372msPDT'. Consider deleting, eventually.
[info] sbt-ethereum shoebox restored from '/Volumes/Backups/sbt-ethereum/sbt-ethereum-shoebox-backup-20190324T00h59m16s521msPDT.zip
The current default solidity compiler ['0.4.24'] is not installed. Install? [y/n] y
[info] Installed local solcJ compiler, version 0.4.24 in '/Users/testuser/Library/Application Support/sbt-ethereum/solcJ'.
[info] Testing newly installed compiler... ok.
[info] Refreshing compiler list.
[info] Updating available solidity compiler set.
[success] Total time: 19 s, completed Mar 24, 2019 1:18:07 AM
```

@@@
