# Testing a Smart Contract

### Basic approach

We'll write some simple tests of the smart contract we developed in the @ref:[prior tutorial](creating-a-smart-contract.md).
We will be working in the same `eth-timelock` repository that we created in that tutorial.

Our tests will be written in Scala, and our testing environment will be [ganache](https://truffleframework.com/ganache).
The command `ganache-cli` must be available on the command line for the tests to work.

@@@ note

This is an **advanced tutorial**.

In order to really follow this tutorial, you'll really need to have some understanding of [Scala](https://www.scala-lang.org).
It'll still be a bit weird.

Also, if you want to try this out, you'll have to download and install [ganache](https://truffleframework.com/ganache)
and make sure that the `ganache-cli` is available in your `PATH`!

@@@

_sbt-ethereum_ by default initializes Ganache so that a test account has a very large number of ETH.
That account will deploy any smart contracts specified in the build.sbt file, and then run whatever
whatever tests are available in `src/test/scala`

### Setting up testing in `build.sbt`

At the top-level of our repository there is a file called `build.sbt`, which is our project's main configuration file.
We'll need to set some stuff up there.

#### Defining `Test / ethcfgAutoDeployContracts`

We will want our test account set up two `Timelock` smart contracts, one that locks for 0 days and 0 seconds (that is, not at all),
and the other that locks for for a very long time (999 days). To do this, we'll want to define the setting
@ref[`ethcfgAutoDeployContracts`](../settings/index.md#ethcfgautodeploycontracts) in the `Test` "configuration".

The `Test` configuration is like a parallel universe, which can interact with a different blockchain (e.g. Ganache rather than
Ethereum mainnet), and have different settings than the main configuration `sbt-ethereum` employs (which is called the `Compile`
configuration). We can set things up in the `Test` configuration that only apply to running tests. This includes defining
test-specific source code.

Anyway, in order to define the contracts we will want to be auto deployed upon testing, we will want to add the following
line to `build.sbt`:

```
Test / ethcfgAutoDeployContracts := Seq( "Timelock 0 0 1 wei", "Timelock 999 0 1 wei" )
```

This is setting up two contracts to be autodeployed. The double-quoted strings inside the `Seq` are the
arguments that we would prvide to `ethTransactionDeploy` in order to deploy the contract. Recall that in the
@ref:[previous tutoria](creating-a-smart-contract.md), we deployed our smart contract as
```
sbt:eth-timelock> ethTransactionDeploy Timelock 0 600 1 wei
```
where `Timelock` was the name of the contract we defined, the first argument `0` represented the number of days delay the contract
would enforce before permitting withdrawal of ETH, the second argument `600` represented the number of seconds delay the contract would
enforce in addition to the specified number of days, and the third (compound) argument `1 wei` represented the funds the contract should
deployed with.

If we look at our `Test / ethcfgAutoDeployContracts` setting, we can see that there are two contracts to be autodeployed, both `Timelock`
instances, both funded with just `1 wei`, but one which enforces no delay at all prior to permitting withdrawal while the other enforces
a delay of `999 days`.

#### Generating Scala stubs

We are going to write our tests in Scala, which means we will want _sbt-ethereum_ to generate Scala "stubs". These "stubs" are just Scala
classes that mirror and represent the smart contract @ref:[we have defined in _solidity_](creating-a-smart-contract.md#define-your-smart-contract-source-code).

In order to cause _sbt-ethereum_ to generate Scala stubs, you just have to define what Scala "package" the stubs should be generated into,
using the setting @ref:[`ethcfgScalaStubsPackage`](../settings/index.md#ethcfgscalastubspackage). By convention, Scala (and Java) packages
are given all lowercase names, so we will specify a package called `timelock` in our `build.sbt` file:
```
ethcfgScalaStubsPackage := "timelock"
```

#### Other test settings

We will use a Scala testing library called [Specs2](https://etorreborre.github.io/specs2/). (You can use any testing library you like!)
In order to make that available to our project, we'll need to add the following setting to `build.sbt`:
```
libraryDependencies += "org.specs2" %% "specs2-core" % "4.0.2" % "test"
```

@@@ note

Note that last element "test". This tells _sbt_ that this library will only be used for testing, and should not be considered a part of any
application we might ship.

Note also that we use the `+=` operator (adding a library) rather than the `:=` operator (which would define the
full set of libraries we depend upon).

_sbt-ethereum_ pre-defines some `libraryDependencies` for you, so it's better to add to what has already been set up than
to specify your own full list of dependencies.

@@@

Finally, we have to make a decision about testing concurrency. _sbt_ by default runs all tests concurrently, but _Ethereum_ programming
is very stateful. Generally we wish to perform some action, which changes the state of a smart contract, then check that state, then perform
some other action, all in sequence. So we configure _sbt_ **not** to run its tests concurrently:
```
Test / parallelExecution := false
```

#### Putting it all together

When you are all done, your `build.sbt` file should look something like this:
```
name := "eth-timelock"

version := "0.0.1-SNAPSHOT"

ethcfgScalaStubsPackage := "timelock"

libraryDependencies += "org.specs2" %% "specs2-core" % "4.0.2" % "test"

Test / parallelExecution := false

Test / ethcfgAutoDeployContracts := Seq( "Timelock 0 0 1 wei", "Timelock 999 0 1 wei" )
```

(The ordering in the file of all these settings does not matter.)

### Defining tests in Scala

We can put any test-specific Scala code under the directory `src/test/scala/` in our repository.
Because we've specified our stubs should live in a `timelock` package, we'll put our tests in that same package.
So we'll want a `src/test/scala/timelock` directory.

In that directory, create a file called `TimelockSpec.scala`. (By convention, _Specs2_ tests are given a name
ending in "Spec".)

Define the following contents for that file:
```scala
package timelock

import org.specs2._
import Testing._

import com.mchange.sc.v1.consuela.ethereum.stub
import com.mchange.sc.v1.consuela.ethereum.stub.sol

class TimelockSpec extends Specification with AutoSender { def is = sequential ^ s2"""

   On a vested Timelock contract...
     an arbitrary sender should not be able to withdraw                ${e1}
     the owner (default test sender) successfully withdraws            ${e2}

   On an unvested Timelock contract...
     an arbitrary sender should not be able to withdraw                ${e3}
     the owner (default test sender) should not be able to withdraw    ${e4}

"""

  val Vested = Timelock( TestSender(0).contractAddress(0) )
  val Unvested = Timelock( TestSender(0).contractAddress(1) )

  val ArbitrarySender = createRandomSender()

  def e1 = {
    try {
      Vested.transaction.withdraw()( ArbitrarySender )
      false
    }
    catch {
      case e : Exception => true
    }
  }

  def e2 =  {
    Vested.transaction.withdraw()( DefaultSender )
    true
  }

  def e3 = {
    try {
      Vested.transaction.withdraw()( ArbitrarySender )
      false
    }
    catch {
      case e : Exception => true
    }
  }

  def e4 = {
    try {
      Vested.transaction.withdraw()( ArbitrarySender )
      false
    }
    catch {
      case e : Exception => true
    }
  }
}
```

There is a lot going on here, but let's first understand the basic intention. When we set up 
@ref[`ethcfgAutoDeployContracts`](../settings/index.md#ethcfgautodeploycontracts), we defined
two `Timelock` contracts, one "vested", meaning we are able to withdraw from it immediately,
and another "unvested", meaning we would be prevented from withdrawing. The unvested contract
will remain unvested for 999 days &mdash; effectively forever in the context of this test suite.

The "specfication string" which begins `s2"""` lets us describe what we are testing and the
outcomes we expect.

We expect that on the vested contract, an arbitrary identity should not be able to withdraw
any funds, but the contract's owner _should_ be able to, immediately.

For the unvested contract, we expect that neither an arbitrary identity nor the contract's owner
should be able to withdraw.

The functions `e1` through `e4` test all of those assertions.

In order to make sense of all this code, it helps to have some exposure to Scala. But, even with
that, it'll look like magic without understanding the contents of the `Testing` object, which gets
generated as test-only code (as part of the `Test` configuration) into our @ref:[`ethcfgScalaStubsPackage`](../settings/index.md#ethcfgscalastubspackage), 
and which offers utilities that are helpful for testing code. Let's take a look at that:

```scala
package timelock

import com.mchange.sc.v1.consuela.ethereum.EthPrivateKey
import com.mchange.sc.v1.consuela.ethereum.{jsonrpc, stub, EthAddress, EthHash}
import com.mchange.sc.v1.consuela.ethereum.specification.Denominations

import stub.sol

import scala.concurrent.{Await,Future}
import scala.concurrent.duration._

import scala.collection._

object Testing {
  val EthJsonRpcUrl : String                          = "http://localhost:58545"
  val TestSender    : IndexedSeq[stub.Sender.Signing] = stub.Test.Sender
  val DefaultSender : stub.Sender.Signing             = TestSender(0)
  val Faucet        : stub.Sender.Signing             = DefaultSender
  
  val EntropySource = new java.security.SecureRandom()
  
  /** A variety of utilities often useful within tests. */
  trait Context extends Denominations {
    implicit val scontext = stub.Context.fromUrl( EthJsonRpcUrl )
    implicit val icontext = scontext.icontext
    implicit val econtext = icontext.econtext
    
    def createRandomSender() : stub.Sender.Signing = stub.Sender.Basic( EthPrivateKey( EntropySource ) )
    
    def asyncBalance( address : EthAddress )  : Future[BigInt] = jsonrpc.Invoker.getBalance( address )
    def asyncBalance( sender  : stub.Sender ) : Future[BigInt] = sender.asyncBalance()
    
    def awaitBalance( address : EthAddress )                                      : BigInt = awaitBalance( address, Duration.Inf )
    def awaitBalance( address : EthAddress,  duration : Duration )                : BigInt = Await.result( asyncBalance( address ), duration )
    def awaitBalance( sender  : stub.Sender, duration : Duration = Duration.Inf ) : BigInt = sender.awaitBalance( duration )
    
    def asyncFundAddress( address : EthAddress, amountInWei : BigInt ) : Future[EthHash] = Faucet.sendWei( address, sol.UInt256( amountInWei ) )
    def awaitFundAddress( address : EthAddress, amountInWei : BigInt, duration : Duration = Duration.Inf ) : EthHash = Await.result( asyncFundAddress( address, amountInWei ), duration )
    
    def asyncFundSender( sender : stub.Sender, amountInWei : BigInt )  : Future[EthHash] = asyncFundAddress( sender.address, amountInWei )
    def awaitFundSender( sender : stub.Sender, amountInWei : BigInt, duration : Duration = Duration.Inf ) : EthHash = awaitFundAddress( sender.address, amountInWei, duration )
    
    def asyncFundAddresses( destinations : Seq[Tuple2[EthAddress,BigInt]] ) : Future[immutable.Seq[EthHash]] = {
      destinations.foldLeft( Future.successful( immutable.Seq.empty : immutable.Seq[EthHash] ) ){ case ( accum, Tuple2( addr, amt ) ) => accum.flatMap( seq => asyncFundAddress( addr, amt ).map( seq :+ _ ) ) }
    }
    def awaitFundAddresses( destinations : Seq[Tuple2[EthAddress,BigInt]] ) : immutable.Seq[EthHash] = awaitFundAddresses( destinations, Duration.Inf )
    def awaitFundAddresses( destinations : Seq[Tuple2[EthAddress,BigInt]], duration : Duration ) : immutable.Seq[EthHash] = Await.result( asyncFundAddresses( destinations ), duration )
    
    def asyncFundSenders( destinations : Seq[Tuple2[stub.Sender,BigInt]] ) : Future[immutable.Seq[EthHash]] = asyncFundAddresses( destinations.map { case ( sender, amt ) => ( sender.address, amt ) } )
    def awaitFundSenders( destinations : Seq[Tuple2[stub.Sender,BigInt]], duration : Duration = Duration.Inf ) : immutable.Seq[EthHash] = Await.result( asyncFundSenders( destinations ), duration )
  }
  trait AutoSender extends Context {
    implicit val DefaultSender : stub.Sender.Signing = Testing.DefaultSender
  }
  final object Implicits extends AutoSender
}
```

`TestSender` refers to a sequence of currently 5 predefined accounts useful for testing.
The first one, `TestSender(0)`, is the `Faucet` account &mdash; when the Ganache test environment
is set up, this account is given all the `ETH`.

The contracts specified in @ref[`ethcfgAutoDeployContracts`](../settings/index.md#ethcfgautodeploycontracts)
will be auto deployed by this same account. So it is also referred to as `DefaultSender`.

Knowing the deployer of a contract, you can predict the addresses of the contracts it will spawn.
The first auto-deployed contract will be `TestSender(0).contractAddress(0)`, the second-deployed
contract will be `TestSender(0).contractAddress(1)` etc.

With these facts in mind, if you are a Scala programmer, hopefully you can make a little bit of sense
of the test code above. It may help to check out the `consuela` library's @scaladoc[stub](com.mchange.sc.v1.consuela.ethereum.stub.index) package.

### Running the tests

From the base of your project directory, run `sbt`. Then type the following command:
```
sbt:eth-timelock> ethDebugGanacheTest
```
A lot of stuff should happen after that. If your code is not fully generated and compiled, that will happen first. 
Then a "ganache" simulated blockchain should start up, and lots of transactions should get executed.

But in the end, you should see something like this:
```
[info] TimelockSpec
[info]    On a vested Timelock contract...
[info]      + an arbitrary sender should not be able to withdraw
[info]      + the owner (default test sender) successfully withdraws
[info]    On an unvested Timelock contract... 
[info]      + an arbitrary sender should not be able to withdraw
[info]      + the owner (default test sender) should not be able to withdraw
[info] Total for specification TimelockSpec
[info] Finished in 3 seconds, 840 ms
[info] 4 examples, 0 failure, 0 error
[info] Passed: Total 4, Failed 0, Errors 0, Passed 4
```
Woohoo! Your tests have succeeded! (Or, if not, you have something to fix. Alas.)
