# ethDebug*

_sbt-ethereum_ tasks and commands related to debugging and testing _Ethereum_ smart contract applications.

Many of these commands have to do with managing a [Ganache](https://truffleframework.com/ganache) process as a test environment.

_These commands will only work if the command `ganache-cli` is preinstalled and available in the executable `PATH` of the `sbt` process!_

Usually to test a project, you will define the set of smart contracts to be deployed with @ref:[`Test / ethcfgAutoDeployContracts`](../../../settings/index.md#ethcfgautodeploycontracts),
define Scala tests under `src/main/test` (using _sbt-ethereum_ generated Scala stubs and whatever testing library you prefer), and then use @ref:[`ethDebugGanacheTest`](#ethdebugganachetest)
to cause a Ganache-simulated _Ethereum_ blockchain to be spun up, your testing contracts to be deployed, your tests to be run, and then the Ganache process to be taken down.

### ethDebugGanacheHalt

@@@ div { .keydesc}

**Usage:**
```
> ethDebugGanacheHalt
```
Stops any Ganache subprocess that might be running in this session.

_**Note:** Only works if `ganache-cli` is available on the execution path visible to your _sbt_ process._

**Example:**
```
> ethDebugGanacheHalt
[info] A local ganache environment was running but has been stopped.
[success] Total time: 0 s, completed Feb 27, 2019 3:54:53 PM
```

@@@

### ethDebugGanacheRestart

@@@ div { .keydesc}

**Usage:**
```
> ethDebugGanacheRestart
```
Stops any Ganache subprocess that might be running in this session, then starts a new one.

_**Note:** Only works if `ganache-cli` is available on the execution path visible to your _sbt_ process._

**Example:**
```
> ethDebugGanacheRestart
[warn] No local ganache process is running.
[success] Total time: 1 s, completed Feb 27, 2019 3:57:11 PM
[info] Executing command 'ganache-cli --port 58545 --account=0x0000000000000000000000000000000000000000000000000000000000007e57,115792089237316195423570985008687907853269984665640564039457584007913129639935'
[info] A local ganache process has been started.
[info] Awaiting availability of testing jsonrpc interface.
[info] ganache: Ganache CLI v6.1.8 (ganache-core: 2.2.1)
[info] ganache: 
[info] ganache: Available Accounts
[info] ganache: ==================
[info] ganache: (0) 0xaba220742442621625bb1160961d2cfcb64c7682 (~115792089237316195423570985008687907853269984665640564039458 ETH)
[info] ganache: 
[info] ganache: Private Keys
[info] ganache: ==================
[info] ganache: (0) 0x0000000000000000000000000000000000000000000000000000000000007e57
[info] ganache: 
[info] ganache: Gas Price
[info] ganache: ==================
[info] ganache: 20000000000
[info] ganache: 
[info] ganache: Gas Limit
[info] ganache: ==================
[info] ganache: 6721975
[info] ganache: 
[info] ganache: Listening on 127.0.0.1:58545
[info] ganache: eth_blockNumber
[info] Testing jsonrpc interface found.
[success] Total time: 2 s, completed Feb 27, 2019 3:57:13 PM
```

@@@

### ethDebugGanacheStart

@@@ div { .keydesc}

**Usage:**
```
> ethDebugGanacheStart
```
Starts a Ganache subprocess, if none is already running.

_**Note:** Only works if `ganache-cli` is available on the execution path visible to your _sbt_ process._

**Example:**
```
> ethDebugGanacheStart
[info] Executing command 'ganache-cli --port 58545 --account=0x0000000000000000000000000000000000000000000000000000000000007e57,115792089237316195423570985008687907853269984665640564039457584007913129639935'
[info] A local ganache process has been started.
[info] Awaiting availability of testing jsonrpc interface.
[info] ganache: Ganache CLI v6.1.8 (ganache-core: 2.2.1)
[info] ganache: 
[info] ganache: Available Accounts
[info] ganache: ==================
[info] ganache: (0) 0xaba220742442621625bb1160961d2cfcb64c7682 (~115792089237316195423570985008687907853269984665640564039458 ETH)
[info] ganache: 
[info] ganache: Private Keys
[info] ganache: ==================
[info] ganache: (0) 0x0000000000000000000000000000000000000000000000000000000000007e57
[info] ganache: 
[info] ganache: Gas Price
[info] ganache: ==================
[info] ganache: 20000000000
[info] ganache: 
[info] ganache: Gas Limit
[info] ganache: ==================
[info] ganache: 6721975
[info] ganache: 
[info] ganache: Listening on 127.0.0.1:58545
[info] ganache: eth_blockNumber
[info] Testing jsonrpc interface found.
[success] Total time: 2 s, completed Feb 27, 2019 4:00:22 PM
```

@@@

### ethDebugGanacheTest

@@@ div { .keydesc}

**Usage:**
```
> ethDebugGanacheTest
```
Starts up a Ganache subprocess, autodeploys contracts specified in @ref:[`Test / ethcfgAutoDeployContracts`](../../../settings/index.md#ethcfgautodeploycontracts),
runs the `test` command to execute all tests, then tears down the Ganache subprocess.

_**Note:** Only works if `ganache-cli` is available on the execution path visible to your _sbt_ process._

@@@

