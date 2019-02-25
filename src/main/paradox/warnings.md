# Warnings

It's all good fun until someone puts out an eye. Or loses all their money.

### Be careful

@@@ warning

**Be careful out there!**

Interacting with blockchains, you may find yourself putting serious economic value on the line.

This is __young, new, very lightly tested free software__. It is provided with __NO WARRANTIES__ to the extent permitted by applicable law.
It has not thus far been subject to security audits or any kind of formal verification.

Please be careful, and **try out whatever it is you mean to do with small stakes** before working with serious money.

@@@

### Backup your shoebox and passcodes

@@@ warning

**If you lose the wallet files in your shoebox, or the passcodes that unlock them, you will lose any money or value in them, irrecoverably and forever!**

_sbt-ethereum_ stores wallet files in its internal shoebox directory. You can back up that directory by hand, or use the command `ethShoeboxBackup`.

_sbt-ethereum_ **does not store the passcodes** that unlock these wallets. You need to store these yourself, preferably somewhere offline, and be sure not to lose them.

If you lose _either one of_ a wallet file or its passcode, **all of the value stored in that wallet's associated address will likely be lost forever**. Ouch.

@@@

### Keep your secrets secret

@@@ warning

**Any third party who gains access to both a wallet file and the passcode that unlocks it gains full, irrevocable control of the wallet's account,
  and any value or credentials attached to that!**

Keep your wallet files and passcodes secret.

_Managing cryptocurrency credentials is **hard**. You need to be sure to back them up generously enough to be sure you won't lose them, but simltaneously
 restrict access so that no one unauthorized might possibly get hold of both. There is a difficult tension between these two goals._

@@@

### Keep your account private

@@@ warning

**Your _sbt-ethereum_ shoebox contains data that, if corrupted by an adversary, could result in loss or theft.**

_sbt-ethereum_ should be used only via secure, unshared user accounts.

If an adversary gains access to your account, they could, for example, replace and therefore redirect @ref:[address aliases](tasks/eth/address/alias.md),
leading you to send value to their own address rather than to the address you intend.

@@@



