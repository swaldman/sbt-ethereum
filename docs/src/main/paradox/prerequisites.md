# Prerequisites

### Command line

_sbt-ethereum_ offers a text-based user interface to the _ethereum_ blockchain. You work with it from a "terminal" or "command prompt" window.
On a Mac or Linux, run the _Terminal_ application to get started. On Windows, you can use "Windows Power Shell", or if you are old-school, `CMD.exe`.
You can also use the version of "bash" that comes with some versions of Windows @ref:[git](#git).


### Java Virtual Machine

To work with _sbt-ethereum_, you'll need the _Java 8_ virtual machine installed on your computer. To see whether you already have this installed,
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
The exact version numbers don't matter, as long as they begin with `1.8`.

If you do not already have a Java 8 VM installed, you can download it from Oracle [here](https://www.oracle.com/technetwork/java/javase/downloads/jre8-downloads-2133155.html).

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
If it is not installed, you can download it from [here](https://git-scm.com/downloads). Alternatively, on a Mac it comes with [X Code Command Line Tools](https://developer.apple.com/download/more/) (you'll
have to sign up for a free developer account), and on Linux, it will be available through your distribution's package manager (dnf, yum, apt, etc.).

### sbt (optional)

_sbt-ethereum_ is based on a command-line development tool called _sbt_. Many _sbt-ethereum_ distributions include _sbt_ launch scripts for Mac or Linux, so it is not strictly necessary to download
_sbt_ seprately. But it doesn't hurt, and on Windows it is necessary. You can download _sbt_ [here](https://www.scala-sbt.org).
