# Prerequisites

### command line

_sbt-ethereum_ offers a text-based user interface to the _ethereum_ blockchain. You work with it from a "terminal" or "command prompt" window.
On a Mac or Linux, run the _Terminal_ application to get started. On Windows, you can use "Windows Power Shell", or if you are old-school, `CMD.exe`.
You can also use the version of "bash" that comes with some versions of Windows @ref:[git](#git).


### java 8 or 11 runtime

To work with _sbt-ethereum_, you'll need a _Java 8 or 11 runtime_ installed on your computer.

@@@ warning

Early versions of Java 8 [did not include](https://stackoverflow.com/questions/34110426/does-java-support-lets-encrypt-certificates)
some important certificate authorities. _sbt-ethereum_ fails to launch because its dependencies fail to download over `https`.

Please use a version of Java 8 no older than Java 8u101 (`"1.8.0_101"`).

@@@

 <p><u>Non-"long-term-support" versions like Java 12 and 13 are not supported!</u></p>

To see whether you already have an appropriate JVM installed,
type
```
$ java -version
```
You should see something like
```
java version "1.8.0_172"
Java(TM) SE Runtime Environment (build 1.8.0_172-b11)
Java HotSpot(TM) 64-Bit Server VM (build 25.172-b11, mixed mode)
```
The exact version number doesn't matter, as long as it begins with "11", or begins with "1.8" (and is not older than `1.8.0_101`).

@@@ div {.tight}

If you do not already have a Java VM installed, you can download one from Oracle:

  * [Java 8](https://www.oracle.com/technetwork/java/javase/downloads/jre8-downloads-2133155.html)
  * [Java 11](https://www.oracle.com/technetwork/java/javase/downloads/jdk11-downloads-5066655.html)

@@@

### git

In order to download, modify, and publish _sbt-ethereum_ repositories, you will want to have the `git` command line tool installed as well. You may have it
already. Try
```
$ git --version
```
If it's already installed, you'll see something like
```
git version 2.18.0
```
Don't worry too much about what version you have. It'll probably be fine.

If it is not installed, you can download it from [here](https://git-scm.com/downloads). Alternatively, on a Mac it comes with [X Code Command Line Tools](https://developer.apple.com/download/more/) (you'll
have to sign up for a free developer account), and on Linux, it will be available through your distribution's package manager (dnf, yum, apt, etc.).

### sbt

_sbt-ethereum_ is based on a command-line development tool called _sbt_. Many _sbt-ethereum_ distributions include _sbt_ launch scripts for Mac or Linux, so it is not strictly necessary to download
_sbt_ seprately. But it doesn't hurt. And on Windows it _is_ necessary.

You can download _sbt_ [here](https://www.scala-sbt.org).

### etherscan API key (optional)

To interact with deployed _ethereum_ smart contracts, it is usually necessary to have an _ABI_ (application binary interface) that describes what users can do with
the contract. The on-line service [etherscan](https://etherscan.io/) collect the source code and ABI of deployed _ethereum_ contracts, and verifies that they match
the applications actually deployed on the blockchain. _sbt-ethereum_ can automatically import these verified ABIs into its internal database, so that it is very
easy to interact with deployed, verified, smart contracts.

To get an _etherscan_ API key, you'll need to [create an account](https://etherscan.io/), then click on "MY ACCOUNT", then "API-KEYs" under the "Developers" menu.
Finally click "Create Api Key".

Once you have created your etherscan API key, just run

```
> etherscanApiKeySet <your-etherscan-api-key>
```

You will now be able to automatically import ABIs when you run @ref:[`ethContractAbiImport`](../tasks/eth/contract/abi.md#ethcontractabiimport) or @ref:[`ethContractAbiDefaultImport`](../tasks/eth/contract/abi.md#ethcontractabidefaultimport).

Later [**TK**] we'll see how to import this key into your _sbt-ethereum_ database, which will make it extremely easy to work
with published smart contracts.


